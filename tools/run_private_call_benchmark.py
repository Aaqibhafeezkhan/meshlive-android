"""Exercise real authenticated private calls and score the two media directions separately."""
import argparse
import concurrent.futures
import datetime as dt
import json
import re
import subprocess
import sys
import time
from pathlib import Path

from benchmark_layout import output_path, resolve
from device_log_capture import LogCapture

from run_device_benchmark import COMPONENT, PACKAGE, Runner, raw_fixture_metadata, reference_audio_metadata, media_impairment_metadata, validate_case
import acceptance_profiles as acceptance
import score_intercom_call as intercom
from score_device_run import (DEFAULT_THRESHOLDS, ENDED_EVIDENCE, aggregate_status, demote_user_gates, ended_proof, gate,
                              limits_for, records, scope_records, score_log, timestamp)

PROFILE_KEYS = {"width": "width", "height": "height", "fps": "fps",
                "bitrate_kbps": "bitrateKbps", "codec": "encoder",
                "rate_control": "rateControl", "thermal_reduction": "thermalReduction"}
PLAYBACK_MS = {"low": 30, "balanced": 250, "stable": 500, "auto": 250}  # auto starts at 250 ms and adapts to 500
FIRST_SURFACE_LINE = "Private call startup: media-to-first-surface"
# Both lines are logged only from the call TextureView's onSurfaceTextureUpdated after a frame reached it.
# The Oppo A15s (Android 10) device log dropped 80-90% of app lines (stage-record sequence gaps in
# calls-noloss-s24a15s-1756e738, mvp-smoke-s24a15s-b9029e48) including the one-off first-surface line,
# while surviving 'visible continuity: 248 frames' lines proved the picture reached the surface.
VIDEO_SURFACE_EVIDENCE = FIRST_SURFACE_LINE + r"|Private call visible continuity: [1-9]\d* frames"
CONTINUITY_FIRST_SURFACE = re.compile(r"Private call visible continuity: [1-9]\d* frames .*"
                                      r"\| first surface [0-9.]+ ms after start, ([0-9.]+) ms ago")


def first_surface_time(rows):
    """App-clock time (seconds) the first call frame reached the remote TextureView, with its source:
    the one-off first-surface line, else a periodic continuity report that dates the same first frame
    ('first surface ... ms ago'). (None, None) when neither survived; never inferred from decoder output."""
    first = next((row for row in rows if FIRST_SURFACE_LINE in row["message"]), None)
    if first:
        return first["at"], "first_surface_line"
    for row in rows:
        match = CONTINUITY_FIRST_SURFACE.search(row["message"])
        if match:
            return row["at"] - float(match[1]) / 1000, "continuity_report"
    return None, None


def broadcast_shape(case, phone):
    peer = case["callee"] if phone == case["caller"] else case["caller"]
    result = {"source": phone, "receivers": [{"phone": peer, "parent": phone}],
            "transport": case["transport"], "profile": case["profiles"][phone],
            "media_trace": case.get("media_trace", False),
            "duration_s": case.get("duration_s", 60),
            "connection_timeout_s": case.get("connection_timeout_s", 30)}
    if phone in case.get("raw_fixture_files", {}):
        result["raw_fixture_file"] = case["raw_fixture_files"][phone]
        if phone in case.get("raw_fixture_sha256s", {}):
            result["raw_fixture_sha256"] = case["raw_fixture_sha256s"][phone]
        if phone in case.get("raw_fixture_metadatas", {}):
            result["raw_fixture_metadata"] = case["raw_fixture_metadatas"][phone]
    if phone in case.get("reference_audios", {}):
        result["reference_audio"] = case["reference_audios"][phone]
    if phone in case.get("media_impairments", {}):
        result["media_impairment"] = case["media_impairments"][phone]
    if "tunables" in case:
        # Debug launch extras apply to both endpoints; dropping them here ran every call A/B arm
        # (for example test_call_ladder_from_request or test_bounded_media_age) at the app default.
        result["tunables"] = dict(case["tunables"])
    return result


# Lines BluetoothPrivateCallRelay logs through MainActivity.setStatus. Prefixes are authenticated link
# identities (each adjacent hop's signed SecureBluetoothHandshake), not endpoint content identities.
RELAY_CALLEE = r"Private-call relay authenticated callee ([0-9a-f]{12})\."
RELAY_ADVERTISING = r"Private-call (?:CoC |GATT )?relay is advertising for its caller\."
RELAY_JOINED = r"Relaying an endpoint-encrypted private call between ([0-9a-f]{12}) and ([0-9a-f]{12});"
RELAY_CLOSED = r"Private-call relay stopped:|Private-call (?:caller|callee) path closed:|Private-call relay could not start:"
RELAY_COUNTERS_REASON = ("BluetoothPrivateCallRelay logs no forwarded-record counters, queue depth or per-direction "
                         "liveness; relay forwarding and queues remain UNMEASURED")
# Launch extras of the broadcast source path that the opaque relay never uses (it has no camera,
# encoder, decoder or playback): the relay is launched with transport and tunables only.
RELAY_DROPPED_EXTRAS = {"test_auto_broadcast", "test_role", "test_width", "test_height", "test_fps",
                        "test_bitrate_kbps", "test_rate_control", "test_codec", "test_playback_delay",
                        "test_thermal_reduction", "test_media_trace", "test_viewer_scan_mode", "test_audio",
                        "test_stream_flush", "test_gatt_write_timing"}


# Relays the app builds between caller and callee: PrivateCallRelayChain.MAX_RELAYS (relay chains, on by
# default since 2026-10-09). A relay re-advertises its child's offer (callee prefix, route cost + 1) and
# admits a child that is the callee (route cost 0) or another signed relay (route cost 1..MAX-1). Each
# relay is told its exact child offer (test_relay_callee + test_relay_route_cost), so it never takes a
# shortcut offer. Builds before relay chains admitted only one relay; the control arm
# `call_relay_chain: false` restores that rule, so a longer chain is BLOCKED under it.
APP_MAX_CALL_RELAYS = 4
APP_RELAY_CHAIN_LIMIT = (
    f"BLOCKED by the app: PrivateCallRelayChain.MAX_RELAYS admits at most {APP_MAX_CALL_RELAYS} relays between "
    "caller and callee")
APP_RELAY_CHAIN_OFF = (
    "BLOCKED by the app: tunable call_relay_chain=false keeps the one-relay rule (a relay's child must "
    "authenticate as the announced callee), so a relay cannot take another relay as its child")


def call_relays(case):
    """Ordered middle hops, caller -> relays[0] -> ... -> relays[-1] -> callee.

    `relays: ["phone", ...]` is the schema; `relay: "phone"` (or a list) is accepted shorthand."""
    if "relay" in case and "relays" in case:
        raise ValueError("Name the relay chain once: `relays` (or the `relay` shorthand)")
    relays = case.get("relays", case.get("relay"))
    if relays is None:
        return []
    relays = [relays] if isinstance(relays, str) else relays
    if not isinstance(relays, list) or not relays or not all(isinstance(item, str) and item for item in relays):
        raise ValueError("A call relay chain must name phone aliases in hop order")
    return list(relays)


def call_route(case):
    return [case["caller"], *call_relays(case), case["callee"]]


def relay_chain_limit(case):
    """Why the current app cannot execute this requested relay chain, or None when it can."""
    relays = len(call_relays(case))
    if relays > APP_MAX_CALL_RELAYS:
        return APP_RELAY_CHAIN_LIMIT
    if relays > 1 and case.get("tunables", {}).get("call_relay_chain") is False:
        return APP_RELAY_CHAIN_OFF
    return None


def relay_child_offer(case, relay):
    """(callee prefix alias, route cost) of the offer this relay must take: its requested child's.

    The callee advertises route cost 0; the relay next to it advertises 1, and so on outwards."""
    relays = call_relays(case)
    return case["callee"], len(relays) - 1 - relays.index(relay)


def relay_launch_args(args, callee_prefix=None, route_cost=None):
    """Turn the broadcast source launch into the app's DEBUG private-call relay launch.

    MainActivity: test_auto_start + test_role=relay + test_private_call_relay scans for one private-call
    offer on test_transport (only the callee prefix `test_relay_callee` at `test_relay_route_cost` when
    given), authenticates that child, then advertises the callee's offer (route cost + 1) for its caller
    (BluetoothPrivateCallRelay)."""
    target = []
    if callee_prefix is not None:
        if not re.fullmatch(r"[0-9a-f]{12}", callee_prefix):
            raise ValueError("A relay target needs the callee's 12-digit signed-node prefix")
        target += ["--es", "test_relay_callee", callee_prefix]
    if route_cost is not None:
        if isinstance(route_cost, bool) or not isinstance(route_cost, int) or not 0 <= route_cost < APP_MAX_CALL_RELAYS:
            raise ValueError("A relay's child route cost must be within the app's relay chain")
        target += ["--ei", "test_relay_route_cost", str(route_cost)]
    args = list(args)
    index = args.index("-n") + 2
    head, tail, kept = args[:index], args[index:], []
    while tail:
        if len(tail) < 3 or not tail[0].startswith("--e"):
            raise ValueError(f"Unexpected launch extra layout: {tail[:3]!r}")
        flag, key, value, tail = tail[0], tail[1], tail[2], tail[3:]
        if key not in RELAY_DROPPED_EXTRAS:
            kept += [flag, key, value]
    return [*head, *kept, "--ez", "test_auto_start", "true", "--es", "test_role", "relay",
            "--ez", "test_private_call_relay", "true", *target]


def validate_call_case(case, devices):
    if {"replay_file", "replay_files", "replay_sha256", "replay_sha256s"}.intersection(case):
        raise ValueError("Encoded replay is not supported for private calls; no camera fallback")
    participants = {case["caller"], case["callee"]}
    if not set(case.get("media_impairments", {})) <= participants:
        raise ValueError("Call impairment must name an outgoing endpoint")
    if len(participants) != 2 or set(case["profiles"]) != participants:
        raise ValueError("A call needs distinct caller/callee and an independent profile for each")
    if not set(case.get("raw_fixture_files", {})) <= participants:
        raise ValueError("Raw fixtures must belong to a call endpoint")
    if not set(case.get("raw_fixture_sha256s", {})) <= set(case.get("raw_fixture_files", {})):
        raise ValueError("Raw fixture hash guards must belong to a supplied call input")
    if not set(case.get("reference_audios", {})) <= set(case.get("raw_fixture_files", {})):
        raise ValueError("Reference audio must belong to a matching raw-video call endpoint")
    relays = call_relays(case)
    if set(relays) & participants or len(set(relays)) != len(relays) or not set(relays) <= devices.keys():
        raise ValueError("A call relay must be a distinct registered phone, not an endpoint")
    for relay in relays:
        if not devices[relay].get("serial") or not re.fullmatch(r"[0-9a-f]{12}", devices[relay].get("node_prefix", "")):
            raise ValueError("A call relay needs a serial and a 12-digit signed-node prefix")
    if relays and any(len({devices[phone][field] for phone in {*participants, *relays}}) != len(participants) + len(relays)
                      for field in ("serial", "node_prefix")):
        raise ValueError("Aliases must not assign one physical phone to a relay and an endpoint")
    if relays and {"preserve_call_bitrate", "gatt_defer_connection_priority"} & set(case):
        raise ValueError("The call bitrate and GATT priority diagnostics are direct-call only")
    # Exact-rate (CBR/VBR) calls keep the GATT 180 / Classic 250 kbps starting budgets; this DEBUG
    # diagnostic challenges them on GATT and, since 2026-10-09, Classic.
    if "preserve_call_bitrate" in case and (
            not isinstance(case["preserve_call_bitrate"], bool) or case["transport"] not in ("gatt", "classic")):
        raise ValueError("Preserving selected call bitrate is a boolean direct GATT or Classic diagnostic")
    if "gatt_defer_connection_priority" in case and (
            not isinstance(case["gatt_defer_connection_priority"], bool) or case["transport"] != "gatt"):
        raise ValueError("Connection priority ordering is a boolean direct GATT diagnostic")
    if "video_presentation_timing" in case or "release_video_at_playout" in case:
        raise ValueError("Video presentation timing currently measures broadcast receivers only")
    if "call_pipeline_timing" in case and not isinstance(case["call_pipeline_timing"], bool):
        raise ValueError("Call pipeline timing must be a boolean")
    for phone in participants:
        validate_case(broadcast_shape(case, phone), devices)
        profile = case["profiles"][phone]
        if not isinstance(profile.get("codec"), str) or not profile["codec"]:
            raise ValueError("Every endpoint must explicitly request its encoder")
        if profile["fps"] > 60 or profile["bitrate_kbps"] < 50:
            raise ValueError("Requested profile would be silently clamped by Android launch extras")
        if "thermal_reduction" in profile and not isinstance(profile["thermal_reduction"], bool):
            raise ValueError("Thermal reduction must be a boolean")
    if case.get("setup_action", "accept") not in {"accept", "decline", "cancel"}:
        raise ValueError("Setup action must be accept, decline, or cancel")
    if case.get("setup_action", "accept") != "accept" and case.get("actions"):
        raise ValueError("Media controls need an accepted call")
    for action in case.get("actions", []):
        at = action.get("at_s")
        if (action.get("phone") not in participants or isinstance(at, bool) or
                not isinstance(at, (int, float)) or not 0 <= at < case.get("duration_s", 60)):
            raise ValueError("Actions must target an endpoint during the measurement")
        if action.get("action") not in {"mute", "unmute", "video_off", "video_on", "flip",
                                         "background", "foreground", "end", "kill", *intercom.PTT_ACTIONS}:
            raise ValueError("Unknown private-call action")
        if action.get("action") == "flip" and action["phone"] in case.get("raw_fixture_files", {}):
            raise ValueError("Recorded raw input cannot measure a physical camera flip")
    intercom.validate_intercom_case(case, participants)
    return participants | set(relays)


def profile_rows(rows, kind):
    result = []
    for row in rows:
        match = re.search(rf"BENCHMARK call {kind}: (.*)", row["message"])
        if match:
            values = dict(re.findall(r"(\w+)=([^|]+?)(?=\s*\||$)", match[1]))
            values = {key: value.strip() for key, value in values.items()}
            result.append({"time": row["time"], **values})
    return result


def requested_matches(requested, observed):
    if not observed:
        return None
    expected = {actual: str(requested.get(key, "cbr" if key == "rate_control" else False
                                         if key == "thermal_reduction" else "")).lower()
                for key, actual in PROFILE_KEYS.items()}
    expected["playbackMs"] = str(PLAYBACK_MS[requested.get("playback", "balanced")])
    # The count of mismatching fields (0 = the selected profile is the requested one). Since fe2acce the app annotates
    # some values ("thermalReduction=false (setting=false)"), so only the value before a note is compared.
    return sum(observed.get(key, "").split(" (", 1)[0].strip().lower() != value for key, value in expected.items())


def score_call_manifest(manifest, base_path, thresholds=None):
    limits = limits_for(thresholds)
    case, scores = manifest["scenario"], []
    link, _ = acceptance.link_class(manifest)
    shared = {"media_kind": "call", "acceptance": manifest.get("acceptance"),
              "scenario": {"media_impairments": case.get("media_impairments")}}
    scopes = []
    voice = intercom.is_voice(case)
    clocks = intercom.clock_maps(manifest)
    relays, relay_scopes = call_relays(case), []
    for spec in manifest.get("logs", []):
        text = resolve(spec["path"], base_path).read_text(encoding="utf-8", errors="replace")
        # The opaque relay holds no call media or keys: it is scored as a hop, never as an endpoint.
        (relay_scopes if spec["device"] in relays else scopes).append(
            {"spec": spec, "text": text, "rows": scope_records(records(text), spec)[0]})
    for scope in scopes:
        spec, text, rows = scope["spec"], scope["text"], scope["rows"]
        phone = spec["device"]
        peer = case["callee"] if phone == case["caller"] else case["caller"]
        requested = case["profiles"][phone]
        identities = {item.get("callId") for item in profile_rows(rows, "identity") if item.get("callId")}
        peer_scopes = [item for item in scopes if item["spec"]["device"] == peer]
        if len(peer_scopes) > 1:
            peer_scopes = [item for item in peer_scopes if len(identities) == 1 and identities.intersection(
                row.get("callId") for row in profile_rows(item["rows"], "identity"))]
        peer_rows = [row for item in peer_scopes for row in item["rows"]]
        own = profile_rows(rows, "effective")
        remote = profile_rows(peer_rows, "effective")
        own_profile, remote_profile = (own[0] if own else {}), (remote[0] if remote else {})
        audio_profile = own_profile
        if voice:  # A voice call exercises no video profile; none of its video gates or expectations apply.
            own_profile = remote_profile = {}
        selected_profiles = profile_rows(rows, "selected")
        remote_headers = profile_rows(rows, "remote")
        # The endpoint's encoder FPS and the peer's visible FPS are independent.
        outgoing = score_log(text, {**spec, "role": "sender",
                             "expected_fps": int(own_profile["fps"]) if own_profile else None,
                             "expected_width": int(own_profile["width"]) if own_profile else None,
                             "expected_height": int(own_profile["height"]) if own_profile else None,
                             "expected_bitrate_kbps": int(own_profile["bitrateKbps"]) if own_profile else None},
                             {**shared, "planned_intervals": manifest.get("outgoing_intervals", []),
                              "control_events": manifest.get("control_events", []),
                              "source_kind": manifest.get("source_kinds", {}).get(phone,
                                  "live_camera" if manifest.get("source_kind") == "live_camera_bidirectional"
                                  else manifest.get("source_kind"))}, thresholds)
        incoming = score_log(text, {**spec, "role": "receiver",
                             "expected_fps": int(remote_profile["fps"]) if remote_profile else None},
                             {**shared, "planned_intervals": manifest.get("incoming_intervals", []),
                              "control_events": manifest.get("control_events", [])}, thresholds)
        if not voice:
            incoming["gates"]["profile_fps_mismatches"] = gate(
                sum(int(header["fps"]) != incoming["expected_fps"] for header in remote_headers)
                if remote_headers and incoming["expected_fps"] is not None and all(
                    re.fullmatch(r"\d+", header.get("fps", "")) for header in remote_headers) else None, 0,
                reason="A scoped authenticated private-call remote header is required to measure FPS")
        for result in (outgoing, incoming):
            for key in ("discovery_ms", "selection_to_first_surface_ms"):
                result["gates"].pop(key, None)
        peer_identities = {item.get("callId") for item in profile_rows(peer_rows, "identity") if item.get("callId")}
        call_id = next(iter(identities)) if len(identities) == 1 else None
        tx = [item for item in profile_rows(rows, "tx") if call_id and item.get("callId") == call_id]
        measured = [item for item in tx if re.fullmatch(r"\d+", item.get("queueDrops", ""))]
        final = [item for item in measured if item.get("final") == "true"]
        # Queue losses count actual discarded queued messages, before End cleanup.
        # Audio inputs precede batching; writer totals count successful application writes.
        drops = [int(match[1]) for row in rows if
                 (match := re.search(r"Call video recovered at a keyframe after (\d+) media drops", row["message"]))]
        if measured:
            drops = [int(item["queueDrops"]) for item in measured]
        total_drops = max(drops, default=None)
        complete = bool(final and final[-1] == measured[-1])
        outgoing["gates"]["sender_drops"] = gate(total_drops if complete or total_drops else None, 0,
            reason="A final cumulative TX report for the measured call is required to prove zero queue loss")
        outgoing["gates"]["call_tx_counter_regressions"] = gate(
            sum(int(b["queueDrops"]) < int(a["queueDrops"]) for a, b in zip(measured, measured[1:])) if measured else None, 0)
        outgoing["call_tx"] = {"call_id": call_id, "reports": tx, "final_report": final[-1] if complete else None,
            "queue_drop_unit": "Discarded queued media messages, including batched audio/config; excludes deliberate End cleanup",
            "input_unit": "Encoded callbacks before audio batching; muted/inactive callbacks are separate",
            "write_scope": "Successful application writer calls; receiver evidence is required for delivery"}
        rx = any("Call RX " in row["message"] for row in rows)
        if not rx:
            for key in ("audio_drops", "decoder_drops"):
                incoming["gates"][key] = gate(None, 0, reason="No measured Call RX counters")
        rx_final = [item for item in profile_rows(rows, "rx") if call_id and
                    item.get("callId") == call_id and item.get("final") == "true"]
        for key, field in (("audio_drops", "audioDrops"), ("decoder_drops", "decoderDrops")):
            values = [int(item[field]) for item in rx_final if re.fullmatch(r"\d+", item.get(field, ""))]
            prior = incoming["gates"][key].get("value")
            incoming["gates"][key] = gate(max([prior or 0, *values]) if values or prior else None, 0,
                reason="A final RX counter for the measured call is required to prove zero loss")
        incoming["call_rx"] = {"call_id": call_id, "final_reports": rx_final}
        if voice:
            for result in (outgoing, incoming):
                intercom.prune_voice(result)
            planned_loss = [item for item in manifest.get("incoming_intervals", []) if
                            item.get("device") == phone and item.get("action") == "planned_peer_loss"]
            outgoing["gates"].update(voice_video_packets_sent=intercom.video_sent_gate(rows, call_id),
                                     audio_packets_sent=intercom.audio_sent_gate(case, phone, rows, call_id))
            incoming["gates"].update(voice_video_packets_received=intercom.video_received_gate(rows, call_id),
                                     audio_packets_received=intercom.audio_received_gate(case, peer, rows, call_id),
                                     audio_receive_stall_windows=intercom.audio_stall_gate(case, peer, rows, call_id, planned_loss))
        for result in (outgoing, incoming):
            if limits["version"] == acceptance.V4:
                demote_user_gates(result["gates"], link)  # The counters above were rebuilt from final reports.
            result["log_status"] = aggregate_status(result["gates"].values())
            result["overall_status"] = aggregate_status([{"status": result["log_status"]},
                                                        {"status": result["quality_status"]},
                                                        {"status": result["lifecycle_status"]}])
        media_expected = case.get("setup_action", "accept") == "accept"
        authenticated = [match.groups() for row in rows if
                         (match := re.search(r"Encrypted private-call link to ([0-9a-f]{12}) over (.+)", row["message"]))]
        prefix = manifest.get("devices", {}).get(peer, {}).get("node_prefix")
        transport_label = {"classic": "Classic", "ble": "LE L2CAP", "gatt": "BLE GATT"}[case["transport"]]
        peers = gate(sum(node != prefix for node, _ in authenticated) if authenticated and prefix else None, 0)
        links = gate(sum(transport_label.lower() not in description.lower() for _, description in authenticated)
                     if authenticated else None, 0)
        setup = next((item for item in manifest.get("setup_anchors", []) if item["device"] == phone), None)
        first_at, _ = first_surface_time(rows)
        startup_ms = (first_at - timestamp(setup["time"])) * 1000 if first_at is not None and setup else None
        header_mismatches = []
        if remote_profile:
            for header in remote_headers:
                header_mismatches.append(sum(header.get(key) != remote_profile.get(key)
                                             for key in ("codec", "width", "height", "fps")))
        gates = {"authenticated_peer": peers, "selected_transport": links,
                 "call_id_mismatches": gate(0 if identities == peer_identities and len(identities) == 1 else
                                             1 if identities and peer_identities else None, 0),
                 "discovery_ms": intercom.discovery_gate(case, manifest, phone, rows, peer_rows, prefix,
                                                         DEFAULT_THRESHOLDS["max_discovery_ms"], clocks)}
        allocation_modes = [match[1] for row in rows if
                            (match := re.search(r"Call camera: Streaming .* mode (\d+|null)", row["message"]))]
        expected_mode = {"cbr": "2", "vbr": "1", "cbr_fd": "3"}.get(requested.get("rate_control", "cbr"))
        if media_expected and voice:
            gates.update(voice_call_type=intercom.local_call_type_gate(case, phone, rows, call_id),
                         voice_layout=intercom.voice_layout_gate(rows, call_id),
                         voice_no_video_pipeline=intercom.no_video_pipeline_gate(rows),
                         setup_to_active_ms=intercom.setup_to_active_gate(rows, setup, limits.get(intercom.VOICE_SETUP_LIMIT_KEY)))
        elif media_expected:
            gates.update(selected_profile_mismatches=gate(
                sum(requested_matches(requested, item) for item in selected_profiles) if selected_profiles else None, 0),
                negotiated_remote_header_mismatches=gate(sum(header_mismatches) if header_mismatches else None, 0),
                actual_allocation_mismatches=gate(sum(mode != expected_mode for mode in allocation_modes)
                                                if allocation_modes and expected_mode else 0 if allocation_modes else None, 0),
                setup_to_first_surface_ms=gate(startup_ms, DEFAULT_THRESHOLDS["max_startup_ms"]))
        changes = {key: {"requested": value, "negotiated": own_profile.get(actual)}
                   for key, actual in PROFILE_KEYS.items() if key not in {"thermal_reduction"} and
                   (value := requested.get(key, "cbr" if key == "rate_control" else None)) is not None and
                   str(value).lower() != own_profile.get(actual, "").lower()} if own_profile else {}
        if own_profile and int(own_profile["playbackMs"]) != PLAYBACK_MS[requested.get("playback", "balanced")]:
            changes["playback"] = {"requested": requested.get("playback", "balanced"),
                                   "negotiated_ms": int(own_profile["playbackMs"])}
        if audio_profile and int(audio_profile["audioKbps"]) != 32:
            changes["audio_kbps"] = {"requested_default": 32, "negotiated": int(audio_profile["audioKbps"])}
        profile_evidence = [gates.get("selected_profile_mismatches", {}),
                            gates.get("actual_allocation_mismatches", {}),
                            outgoing["gates"].get("profile_resolution_mismatches", {}),
                            outgoing["gates"].get("profile_bitrate_mismatches", {})]
        coverage = "NOT_APPLICABLE_VOICE" if voice else "MEASURED" if own_profile and not changes and all(
            item.get("status") == "PASS" for item in profile_evidence) else "UNMEASURED"
        active_gates = [*gates.values(), *outgoing["gates"].values(), *incoming["gates"].values()] if media_expected else list(gates.values())
        scores.append({"device": phone, "path": spec["path"], "scope": outgoing["scope"],
                       "setup_role": "caller" if phone == case["caller"] else "callee",
                       "peer": peer, "requested_profile": requested, "selected_profiles": selected_profiles,
                       "effective_profiles": own, "remote_headers": remote_headers, "negotiated_changes": changes,
                       "actual_encoder_allocation_modes": allocation_modes,
                       "call_type": intercom.call_type(case),
                       "requested_profile_coverage": coverage, "authenticated_links": authenticated,
                       "outgoing": outgoing, "incoming": incoming, "gates": gates,
                       "log_status": aggregate_status(active_gates), "quality_status": "UNMEASURED"})
    lifecycle, ptt_records = [], []
    scope_rows = {item["spec"]["device"]: item["rows"] for item in scopes}
    call_ids = {device: (lambda ids: next(iter(ids)) if len(ids) == 1 else None)(
        {item.get("callId") for item in profile_rows(rows, "identity") if item.get("callId")})
        for device, rows in scope_rows.items()}
    ptt_events = [item for item in manifest.get("control_events", []) if item["action"] in intercom.PTT_ACTIONS]
    for action in manifest.get("control_events", []):
        name = action["action"]
        evidence = {"app_ack": {"status": "PASS" if action.get("acknowledged") else "FAIL"}}
        if action.get("state") == "FAIL":
            evidence["state"] = {"status": "FAIL"}
        invite = name in {"invite", "invite_voice"} or name.startswith(("call:", "voice_call:"))
        if name in {"mute", "unmute", "video_off", "video_on", "kill"} or invite:
            evidence["remote_ack"] = {"status": "PASS" if action.get("remote_ack") else "UNMEASURED"}
        if invite:
            evidence["outgoing_phase"] = {"status": "PASS" if action.get("outgoing_phase") else "UNMEASURED"}
        if name in intercom.PTT_ACTIONS:
            ptt_evidence, record = intercom.ptt_event_evidence(action, case, scope_rows, clocks, call_ids, ptt_events)
            evidence.update(ptt_evidence)
            ptt_records.append(record)
        if name.startswith("voice_call:") or name == "invite_voice":
            offer = action.get("voice_offer")
            evidence["voice_offer_seen"] = {"status": "PASS" if offer else "UNMEASURED"}
        if name in {"video_on", "flip", "foreground"}:
            evidence["surface_recovery_ms"] = gate(action.get("surface_recovery", {}).get("ms"),
                                                    DEFAULT_THRESHOLDS["max_startup_ms"])
        if name == "background":
            evidence["activity_background"] = {"status": "PASS" if action.get("lifecycle_time") else "UNMEASURED"}
        if name == "accept":
            surfaces = action.get("surface_evidence", {})
            # A voice call has no surface; its recorded evidence is each endpoint's ACTIVE phase.
            evidence["both_active_voice_media" if voice else "both_visible_surfaces"] = {"status": "PASS" if all(
                surfaces.get(phone) for phone in (case["caller"], case["callee"])) else "UNMEASURED"}
        if name in {"end", "decline", "cancel", "kill"}:
            terminal = action.get("terminal_evidence", {})
            endpoints = ({case["caller"], case["callee"]} - {action["device"]}) if name == "kill" else {
                case["caller"], case["callee"]}
            evidence["ended_and_available"] = {"status": "PASS" if all(
                terminal.get(phone, {}).get("ended") and terminal.get(phone, {}).get("available")
                for phone in endpoints) else "UNMEASURED"}
        lifecycle.append({"device": action["device"], "action": action["action"],
                          "acknowledged": action.get("acknowledged", False),
                          "remote_ack": action.get("remote_ack"), "state": action.get("state"),
                          "surface_recovery": action.get("surface_recovery"), "evidence": evidence,
                          "gate": {"status": aggregate_status(evidence.values())}})
    if manifest.get("runner_error"):
        lifecycle.append({"action": "runner", "gate": {"status": "FAIL"}, "reason": manifest["runner_error"]})
    relay_hops = [score_relay_hop(manifest, index, [item for item in relay_scopes if item["spec"]["device"] == relay],
                                  scope_rows) for index, relay in enumerate(relays)]
    log_status = aggregate_status([*(dict(status=item["log_status"]) for item in scores),
                                   *(item["gate"] for item in lifecycle),
                                   *(dict(status=item["log_status"]) for item in relay_hops)])
    if not scores:
        log_status = "FAIL" if manifest.get("runner_error") else "UNMEASURED"
    ptt = {"ptt": intercom.ptt_summary(ptt_records)} if ptt_records else {}
    relayed = {"relays": relay_hops, "requested_route": call_route(case)} if relays else {}
    return {"thresholds_version": limits["version"], "call_type": intercom.call_type(case), "log_status": log_status,
            **ptt, **relayed,
            "quality_status": "UNMEASURED", "overall_status": "FAIL" if log_status == "FAIL" else "UNMEASURED",
            "devices": scores, "lifecycle": lifecycle,
            "limitations": [*(["Voice call: no video gate is measured; a voice call proves no video was sent by packet counters and "
                               "the absence of camera, encoder and surface log lines. Acoustic quality and gaps under one 2 s RX report are UNMEASURED"]
                              if voice else []),
                            "Live camera is measured; physical motion and aligned optical quality are separate",
                            "Negotiated profiles do not count as execution of a higher requested profile",
                            "Missing final owned private-call TX counters remain unmeasured; writer success is not receiver delivery",
                            "Planned camera gaps affect only that outgoing stream and its peer's incoming view",
                            *(["Relayed call: endpoint gates are end-to-end; the relay is scored only by its authenticated "
                               "adjacent identities and stability. " + RELAY_COUNTERS_REASON] if relays else [])]}


def score_relay_hop(manifest, index, scopes, endpoint_rows):
    """Prove one requested opaque hop: route[index] -> relay -> route[index + 2], with no shortcut.

    Each relay logs the authenticated link identities of its child (after connecting to it) and of its
    parent (when it joins them). The callee accepts exactly one call connection and stops advertising once
    it holds it, and each relay stops advertising once its parent connects, so a chain in which every relay
    joined its requested parent to its requested child carries the only call link of both endpoints. An
    active call without every join cannot have used the requested chain: a shortcut or direct fallback is a
    failed requested chain, never a pass."""
    case = manifest["scenario"]
    route = call_route(case)
    relay, parent, child = route[index + 1], route[index], route[index + 2]
    prefixes = {phone: manifest.get("devices", {}).get(phone, {}).get("node_prefix") for phone in route}
    rows = [row for item in scopes for row in item["rows"]]
    child_auth = [match[1] for row in rows if (match := re.search(RELAY_CALLEE, row["message"]))]
    joined = [match.groups() for row in rows if (match := re.search(RELAY_JOINED, row["message"]))]
    closures = [row for row in rows if re.search(RELAY_CLOSED, row["message"])]
    unplanned = [item for item in manifest.get("health_events", []) if item.get("device") == relay and not item.get("planned")]
    active = {phone: any("BENCHMARK call phase ACTIVE" in row["message"] for row in endpoint_rows.get(phone, []))
              for phone in (case["caller"], case["callee"])}
    expected_child, expected_parent = prefixes[child], prefixes[parent]
    gates = {
        "relay_adjacent_child_identity": gate(
            sum(node != expected_child for node in child_auth) if child_auth and expected_child else None, 0,
            reason="The relay logged no authenticated child link"),
        "relay_adjacent_parent_identity": gate(
            sum(pair != (expected_parent, expected_child) for pair in joined)
            if joined and expected_parent and expected_child else None, 0,
            reason="The relay logged no authenticated parent joined to its child"),
        "relay_unplanned_closures": gate(len(unplanned) if scopes else None, 0,
                                         reason="No relay log was captured"),
    }
    if joined:
        route_gate = gate(0 if gates["relay_adjacent_child_identity"]["status"] == "PASS" and
                          gates["relay_adjacent_parent_identity"]["status"] == "PASS" else 1, 0)
    elif any(active.values()):
        route_gate = {"status": "FAIL", "value": 1, "limit": 0,
                      "reason": "The call became active without this requested relay joining it: a shortcut, "
                                "direct fallback or unknown path is a failed requested chain"}
    else:
        route_gate = gate(None, 0, reason="The call never became active through the requested chain")
    gates["requested_relay_route"] = route_gate
    counters = {"relay_forwarded_records": gate(None, 0, reason=RELAY_COUNTERS_REASON),
                "relay_queue_depth": gate(None, 0, reason=RELAY_COUNTERS_REASON)}
    return {"device": relay, "role": "call_relay", "hop": index + 1, "parent": parent, "child": child,
            "node_prefix": prefixes[relay], "paths": [item["spec"]["path"] for item in scopes],
            "authenticated_child_links": child_auth, "authenticated_joins": [list(item) for item in joined],
            "closure_lines": [{"time": row["time"], "message": row["message"]} for row in closures],
            "endpoint_active": active, "gates": gates, "forwarding": counters,
            "log_status": aggregate_status(gates.values())}


class CallRunner(Runner):
    def __init__(self, adb, devices, case, output):
        validate_call_case(case, devices)
        if relay_chain_limit(case):
            raise ValueError(relay_chain_limit(case))  # never touch a phone for a chain the app cannot build
        super().__init__(adb, devices, broadcast_shape(case, case["caller"]), output)
        self.case = case
        self.endpoints = set(self.participants)
        self.relays = call_relays(case)
        self.relay_launch = None
        self.relay_target = None
        self.relay_events = []
        # Relays are participants for inventory, APK identity, health, log capture and cleanup only.
        self.participants = self.endpoints | set(self.relays)
        self.raw_fixtures = {phone: raw_fixture_metadata(broadcast_shape(case, phone)) for phone in self.endpoints}
        self.reference_audios = {phone: reference_audio_metadata(broadcast_shape(case, phone)) for phone in self.endpoints}
        self.media_impairments = {phone: media_impairment_metadata(broadcast_shape(case, phone)) for phone in self.endpoints}
        self.control_events, self.setup_anchors = [], []
        self.outgoing_intervals, self.incoming_intervals = [], []
        self.video_off_intervals, self.background_intervals = {}, {}
        self.expected_terminal = False
        self.brackets = []
        self.dump_polled = {}

    def ptt_scheduled(self):
        return any(item.get("action") in intercom.PTT_ACTIONS for item in self.case.get("actions", []))

    def log_text(self, phone):
        return (self.output / next(item["path"] for item in self.logs if item["device"] == phone)).read_text(
            encoding="utf-8", errors="replace")

    def wait_for(self, phone, expression, since=None, timeout=None):
        deadline = time.monotonic() + (timeout or self.case.get("connection_timeout_s", 30))
        while time.monotonic() < deadline:
            self.check_cancelled()
            found = [row for row in records(self.log_text(phone)) if
                     (since is None or row["at"] >= timestamp(since)) and re.search(expression, row["message"])]
            if found:
                return found[0]
            for endpoint in sorted({row["device"] for row in self.logs}):
                rejected = next((row for row in records(self.log_text(endpoint)) if
                                 row["message"].startswith("BENCHMARK native call input rejected:")), None)
                if rejected:
                    raise RuntimeError(f"{endpoint}: {rejected['message']}")
            if "FATAL EXCEPTION" in self.log_text(phone):
                raise RuntimeError(f"{phone}: call application crashed")
            self.supervise_captures()
            found = self.poll_dump_evidence(phone, expression, since)
            if found:
                return found
            time.sleep(0.2)
        # The live stream is not a clock: it can attach after an early line, and on the A15s (Android 10)
        # it ran 4-21 s behind the app (voice-speech-s24a15s-51cc8028), longer than a 5 s acknowledgement
        # wait. Before failing, take one bounded `logcat -d --pid` dump and search (a) the live capture
        # again, because the line may have arrived while the dump ran, and (b) the complete dump. Both
        # keep the same gates as the live wait: this execution's PID and a time after `since`.
        found = self.late_evidence(phone, expression, since)
        if found:
            return found
        raise RuntimeError(f"{phone}: no app evidence for {expression!r} within the deadline")

    DUMP_POLL_S = 3.0

    def live_capture(self, phone):
        log = next((item for item in reversed(self.logs) if item["device"] == phone), None)
        capture = self.captures[self.logs.index(log)][0] if log is not None and self.logs.index(log) < len(self.captures) else None
        return capture if isinstance(capture, LogCapture) else None

    def poll_dump_evidence(self, phone, expression, since):
        """While the live stream has been silent for more than 3 s, look in the device buffer every 3 s
        rather than only at the deadline (the A15s stream stalls or runs seconds behind the app)."""
        capture = self.live_capture(phone)
        now = time.perf_counter()
        if capture is None or capture.silent_s() <= self.DUMP_POLL_S or \
                now - self.dump_polled.get(phone, float("-inf")) < self.DUMP_POLL_S:
            return None
        found = self.late_evidence(phone, expression, since, phase="poll")
        self.dump_polled[phone] = time.perf_counter()
        return found

    def late_evidence(self, phone, expression, since, phase="deadline"):
        pid = int(self.pids.get(phone) or 0)
        lower = None if since is None else timestamp(since)
        def matches(row):
            return ((not pid or row["pid"] == pid) and (lower is None or row["at"] >= lower)
                    and re.search(expression, row["message"]))
        every = self.dump_log_lines(phone)[0]
        live = next((row for row in records(self.log_text(phone)) if matches(row)), None)
        source, row = ("live_capture_after_deadline", live) if live else (
            "logcat_dump", next((row for row in records(chr(10).join(every)) if matches(row)), None))
        if row is None:
            return None
        action = "evidence_after_deadline" if phase == "deadline" else "evidence_from_dump_poll"
        self.events.append({"device": phone, "action": action, "source": source, "pid": pid,
                            "pattern": expression, "app_time": row["time"], "since": since,
                            "reason": "Live logcat stream lagged behind the app; the line carries the app's own timestamp",
                            "host_monotonic_ns": time.monotonic_ns()})
        return {**row, "source": source}

    def launch(self, phone, source=False):
        # Reuse inventory/wake safety and scoped log capture from the broadcast runner.
        original_case = self.case
        self.case = {**broadcast_shape(original_case, phone), "media_trace": original_case.get("media_trace", False),
                     "preserve_call_bitrate": original_case.get("preserve_call_bitrate", False),
                     "gatt_defer_connection_priority": original_case.get("gatt_defer_connection_priority", False),
                     "call_pipeline_timing": original_case.get("call_pipeline_timing", False)}
        self.raw_fixture = self.raw_fixtures[phone]
        self.reference_audio = self.reference_audios[phone]
        self.media_impairment = self.media_impairments[phone]
        try:
            self.check_cancelled()
            super().launch(phone, source=True)
        finally:
            self.case = original_case

    def launch_relay(self, phone):
        """Launch the opaque call relay through the shared wake/launch/log-capture path."""
        original_case = self.case
        self.case = {"source": phone, "receivers": [], "transport": original_case["transport"],
                     "profile": original_case["profiles"][original_case["caller"]],
                     **({"tunables": dict(original_case["tunables"])} if "tunables" in original_case else {})}
        self.raw_fixture = self.reference_audio = self.media_impairment = None
        trace_session, self.trace_session = self.trace_session, None  # the relay decodes no media
        callee, route_cost = relay_child_offer(original_case, phone)
        # The relay takes only its requested child's offer: the callee's prefix at that child's route cost.
        self.relay_target = (self.devices[callee]["node_prefix"], route_cost)
        self.relay_launch = phone
        try:
            self.check_cancelled()
            Runner.launch(self, phone, source=True)
        finally:
            self.case, self.relay_launch, self.trace_session = original_case, None, trace_session
            self.relay_target = None
        self.logs[-1]["role"] = "call_relay"

    def adb(self, phone, *args, timeout=20):
        # The existing launch path supplies exact settings; replace only its mode flag.
        args = list(args)
        if "test_auto_broadcast" in args and phone == self.relay_launch:
            return super().adb(phone, *relay_launch_args(args, *(self.relay_target or (None, None))), timeout=timeout)
        if "test_auto_broadcast" in args:
            args[args.index("test_auto_broadcast")] = "test_private_call_browser"
            if self.case.get("preserve_call_bitrate"):
                args.extend(("--ez", "test_preserve_call_bitrate", "true"))
            if self.case.get("gatt_defer_connection_priority"):
                args.extend(("--ez", "test_gatt_defer_connection_priority", "true"))
            if self.case.get("call_pipeline_timing"):
                args.extend(("--ez", "test_call_pipeline_timing", "true"))
        return super().adb(phone, *args, timeout=timeout)

    def control(self, phone, action):
        when = self.phone_time(phone)
        event = {"device": phone, "action": action, "time": when, "host_monotonic_ns": time.monotonic_ns()}
        self.control_events.append(event)
        self.adb(phone, "shell", "am", "start", "-n", COMPONENT, "--es", "test_control", action)
        result = self.wait_for(phone, rf"BENCHMARK control {re.escape(action)} (?:applied|rejected)", when, timeout=5)
        event.update(acknowledged=result["message"].endswith("applied"), ack_time=result["time"])
        if result.get("source"):
            event["ack_source"] = result["source"]  # absent = seen on the live capture inside the deadline
        if not event["acknowledged"]:
            event["state"] = "FAIL"
            raise RuntimeError(f"{phone}: application rejected {action}")
        return event

    def invite(self):
        caller, callee = self.case["caller"], self.case["callee"]
        voice = intercom.is_voice(self.case)
        # voice_call:PREFIX is the experimental voice invite (Invite then Camera off); call:PREFIX is a video call.
        event = self.control(caller, ("voice_call:" if voice else "call:") + self.devices[callee]["node_prefix"])
        outgoing = self.wait_for(caller, r"BENCHMARK call phase (?:OUTGOING_INVITE|OUTGOING_RINGING)", event["ack_time"])
        event["outgoing_phase"] = {"time": outgoing["time"], "message": outgoing["message"]}
        ringing = self.wait_for(callee, r"BENCHMARK call phase INCOMING_RINGING")
        event["remote_ack"] = {"device": callee, "time": ringing["time"], "message": ringing["message"]}
        if voice:
            # The authenticated Camera off that follows the invite is the callee's voice offer.
            # The callee logs the offer on its link thread, sometimes just before INCOMING_RINGING on the
            # UI thread (M31 24 ms earlier, voice-ptt-51cc8028), so search from the invite, not the ring.
            offer = self.wait_for(callee, r"BENCHMARK call_type offer: .*offered=voice", event["ack_time"])
            event["voice_offer"] = {"device": callee, "time": offer["time"], "message": offer["message"]}
        return event

    def clock_brackets(self, phone, label, samples=5):
        """adb `date` calls bracketed by host time; PTT latency joins two phones' clocks only through these."""
        stamp = lambda: dt.datetime.now().strftime("%m-%d %H:%M:%S.%f")[:-3]
        result = []
        for _ in range(samples):
            before = stamp()
            value = self.phone_time(phone)
            result.append({"device": phone, "label": label, "host_before": before, "host_after": stamp(),
                           "device_time": value})
        return result

    def action(self, action):
        phone, name = action["phone"], action["action"]
        peer = self.case["callee"] if phone == self.case["caller"] else self.case["caller"]
        before_remote = self.phone_time(peer)
        print(f"{phone}: planned call {name}", flush=True)
        if name == "kill":
            self.expected_terminal = True
            event = {"device": phone, "action": name, "time": self.phone_time(phone), "acknowledged": True}
            self.control_events.append(event)
            self.adb(phone, "shell", "am", "force-stop", PACKAGE)
            self.expected_stopped.add(phone)
            ended = self.wait_for(peer, ENDED_EVIDENCE, before_remote)
            available = self.wait_for(peer, r"BENCHMARK call available:", ended["time"], timeout=10)
            for intervals in (self.incoming_intervals, self.outgoing_intervals):
                intervals.append({"device": peer, "action": "planned_peer_loss", "start": before_remote,
                                  "end": ended["time"], "source": "runner_planned_kill"})
            event["remote_ack"] = {"device": peer, "time": ended["time"], "message": ended["message"]}
            event["terminal_evidence"] = {peer: ended_proof(ended, available)}
            return
        if name == "end":
            self.expected_terminal = True
        event = self.control(phone, name)
        if name in intercom.PTT_ACTIONS:
            pressed = name == "ptt_press"
            logged = self.wait_for(phone, rf"BENCHMARK call_ptt: .*action={'press' if pressed else 'release'} ", event["time"], timeout=5)
            changed = "changed=true" in logged["message"]
            event["ptt"] = {"time": logged["time"], "message": logged["message"], "changed": changed}
            if changed:  # Only a real mute transition sends an authenticated Microphone signal.
                remote = self.wait_for(peer, rf"BENCHMARK call remote_mute muted={str(not pressed).lower()}",
                                       before_remote, timeout=5)
                event["remote_ack"] = {"device": peer, "time": remote["time"], "message": remote["message"]}
        elif name in {"mute", "unmute", "video_off", "video_on"}:
            marker = (f"BENCHMARK call remote_mute muted={str(name == 'mute').lower()}" if name in {"mute", "unmute"}
                      else f"BENCHMARK call remote_camera enabled={str(name == 'video_on').lower()}")
            remote = self.wait_for(peer, re.escape(marker), before_remote, timeout=5)
            event["remote_ack"] = {"device": peer, "time": remote["time"], "message": remote["message"]}
            if name == "video_off":
                self.video_off_intervals[phone] = (event["ack_time"], remote["time"])
            elif name == "video_on" and phone in self.video_off_intervals:
                surface = self.wait_for(peer, r"BENCHMARK call first_surface:", remote["time"], timeout=5)
                local_start, remote_start = self.video_off_intervals.pop(phone)
                self.outgoing_intervals.append({"device": phone, "action": "camera_off", "start": local_start,
                                                "end": event["ack_time"], "source": "app_control_ack"})
                self.incoming_intervals.append({"device": peer, "action": "remote_camera_off", "start": remote_start,
                                                "end": surface["time"], "source": "authenticated_remote_camera_and_surface"})
                event["surface_recovery"] = {"device": peer, "time": surface["time"],
                                             "ms": (surface["at"] - remote["at"]) * 1000}
        elif name == "flip":
            surface = self.wait_for(peer, r"BENCHMARK call first_surface:", before_remote, timeout=5)
            self.outgoing_intervals.append({"device": phone, "action": "camera_flip", "start": event["time"],
                                            "end": self.phone_time(phone), "source": "app_control_and_peer_surface"})
            self.incoming_intervals.append({"device": peer, "action": "remote_camera_flip", "start": before_remote,
                                            "end": surface["time"], "source": "peer_surface"})
            event["surface_recovery"] = {"device": peer, "time": surface["time"],
                                         "ms": (surface["at"] - timestamp(before_remote)) * 1000}
        elif name == "background":
            marker = self.wait_for(phone, r"BENCHMARK call lifecycle background applied", event["time"], timeout=5)
            self.background_intervals[phone] = marker["time"]
            event["lifecycle_time"] = marker["time"]
        elif name == "foreground" and phone in self.background_intervals:
            marker = self.wait_for(phone, r"BENCHMARK call lifecycle foreground applied", event["time"], timeout=5)
            surface = self.wait_for(phone, r"BENCHMARK call first_surface:", marker["time"], timeout=5)
            self.incoming_intervals.append({"device": phone, "action": "background",
                                            "start": self.background_intervals.pop(phone), "end": surface["time"],
                                            "source": "activity_lifecycle"})
            event["surface_recovery"] = {"device": phone, "time": surface["time"],
                                         "ms": (surface["at"] - marker["at"]) * 1000}
        elif name == "end":
            event["terminal_evidence"] = {}
            for endpoint, since in ((phone, event["time"]), (peer, before_remote)):
                ended = self.wait_for(endpoint, ENDED_EVIDENCE, since, timeout=5)
                available = self.wait_for(endpoint, r"BENCHMARK call available:", ended["time"], timeout=10)
                event["terminal_evidence"][endpoint] = ended_proof(ended, available)

    def health_check(self, phone):
        if phone in self.relays:
            return self.relay_health_check(phone)
        if self.adb(phone, "get-state", timeout=5) != "device":
            raise RuntimeError(f"{phone}: wireless debugging disconnected")
        if phone in self.expected_stopped:
            return
        if self.adb(phone, "shell", "pidof", PACKAGE, timeout=5) != self.pids[phone]:
            raise RuntimeError(f"{phone}: application exited or restarted unexpectedly")
        log = next(item for item in self.logs if item["device"] == phone)
        self.supervise_captures()
        if self.captures[self.logs.index(log)][0].poll() is not None:
            raise RuntimeError(f"{phone}: log capture stopped unexpectedly")
        lines = self.log_text(phone).splitlines()
        for line in lines[self.observed_lines.get(log["path"], 0):]:
            ended_line = "Private call ended:" in line or "BENCHMARK call ended_ui:" in line
            if ended_line and not self.expected_terminal or "FATAL EXCEPTION" in line:
                self.beep()
                self.health_events.append({"device": phone, "message": line, "planned": False})
                raise RuntimeError(f"{phone}: unexpected call termination: {line}")
        self.observed_lines[log["path"]] = len(lines)

    def relay_health_check(self, phone):
        """The relay must keep its process, log capture and both adjacent links until the planned End."""
        if self.adb(phone, "get-state", timeout=5) != "device":
            raise RuntimeError(f"{phone}: wireless debugging disconnected")
        if self.adb(phone, "shell", "pidof", PACKAGE, timeout=5) != self.pids[phone]:
            raise RuntimeError(f"{phone}: call relay exited or restarted unexpectedly")
        log = next(item for item in self.logs if item["device"] == phone)
        self.supervise_captures()
        if self.captures[self.logs.index(log)][0].poll() is not None:
            raise RuntimeError(f"{phone}: log capture stopped unexpectedly")
        lines = self.log_text(phone).splitlines()
        for line in lines[self.observed_lines.get(log["path"], 0):]:
            if re.search(RELAY_CLOSED, line) and not self.expected_terminal or "FATAL EXCEPTION" in line:
                self.beep()
                self.health_events.append({"device": phone, "message": line, "planned": False})
                raise RuntimeError(f"{phone}: call relay closed before the planned end: {line}")
        self.observed_lines[log["path"]] = len(lines)

    def start_relayed_route(self, caller, callee):
        """BluetoothPrivateCallRelay is downstream-first: it scans once (8 s) for a private-call offer,
        authenticates that child, and only then advertises the callee's offer at route cost + 1 for its
        parent. The chain is therefore built from the callee outwards: callee, the relay next to it, ...,
        the relay next to the caller, then the caller. Each relay takes only the callee's offer at its
        requested child's route cost, and each child stops advertising once its parent holds its only call
        connection (a Classic relay too, with relay chains on), so each phone can reach the callee only
        through its requested child."""
        route = call_route(self.case)
        self.launch(callee)
        self.endpoint_ready(callee)
        for index in reversed(range(len(self.relays))):
            relay, child = route[index + 1], route[index + 2]
            self.launch_relay(relay)
            authenticated = self.wait_for(relay, RELAY_CALLEE)
            node = re.search(RELAY_CALLEE, authenticated["message"])[1]
            self.relay_events.append({"device": relay, "action": "relay_child_authenticated", "hop": index + 1,
                                      "child": child, "time": authenticated["time"], "node": node,
                                      "message": authenticated["message"]})
            if node != self.devices[child]["node_prefix"]:
                # The offer filter selects by announced callee and route cost, which are unauthenticated
                # BLE data: the authenticated child identity is the proof of the requested hop.
                raise RuntimeError(f"{relay}: relay authenticated {node}, not its requested child {child}")
            advertising = self.wait_for(relay, RELAY_ADVERTISING, authenticated["time"])
            self.relay_events.append({"device": relay, "action": "relay_advertising", "hop": index + 1,
                                      "time": advertising["time"], "message": advertising["message"]})
        self.launch(caller)
        self.endpoint_ready(caller)
        # This discovery is the first relay's re-advertisement: its child left the air when it connected.
        self.wait_for(caller, rf"BENCHMARK call discovered: node={self.devices[callee]['node_prefix']}")

    def confirm_relayed_join(self, caller, callee):
        route = call_route(self.case)
        for index, relay in enumerate(self.relays):
            parent, child = route[index], route[index + 2]
            joined = self.wait_for(relay, RELAY_JOINED)
            nodes = re.search(RELAY_JOINED, joined["message"]).groups()
            self.relay_events.append({"device": relay, "action": "relay_joined", "hop": index + 1, "time": joined["time"],
                                      "parent": nodes[0], "child": nodes[1], "message": joined["message"]})
            if nodes != (self.devices[parent]["node_prefix"], self.devices[child]["node_prefix"]):
                raise RuntimeError(f"{relay}: relay joined {nodes[0]} to {nodes[1]}, not its requested hop "
                                   f"{parent} -> {relay} -> {child}")

    def endpoint_ready(self, phone):
        ready = self.wait_for(phone, r"BENCHMARK call available: node=([0-9a-f]{12})")
        if not ready["message"].endswith(self.devices[phone]["node_prefix"]):
            raise RuntimeError(f"{phone}: current app identity differs from the registry")
        self.logs[-1]["role"] = "private"

    def run(self):
        self.output.mkdir(parents=True, exist_ok=False)
        manifest = {"schema_version": 1, "run_id": self.run_id, "mode": "private_call",
                    "scenario": self.case, "source_kind": "live_camera_bidirectional", "optical_results": []}
        manifest["source_kinds"] = {phone: "raw_fixture_live_encoder" if self.raw_fixtures[phone] else "live_camera"
                                    for phone in self.endpoints}
        manifest["source_fixtures"] = {phone: fixture for phone, fixture in self.raw_fixtures.items() if fixture}
        manifest["reference_audios"] = {phone: entry for phone, entry in self.reference_audios.items() if entry}
        manifest["media_trace_session"] = self.trace_session
        manifest["media_impairments"] = {phone: entry for phone, entry in self.media_impairments.items() if entry}
        if manifest["source_fixtures"]:
            manifest["source_kind"] = "recorded_motion_bidirectional" if len(manifest["source_fixtures"]) == 2 else "mixed_camera_recorded_motion"
        caller, callee = self.case["caller"], self.case["callee"]
        try:
            self.check_cancelled()
            with concurrent.futures.ThreadPoolExecutor() as executor:
                phones = sorted(self.participants)
                manifest["devices"] = dict(zip(phones, executor.map(self.inventory, phones)))
            if len({item["apk_sha256"] for item in manifest["devices"].values()}) != 1:
                raise RuntimeError("Call participants have different APK hashes")
            if self.case.get("expected_apk_sha256") and any(
                item["apk_sha256"].lower() != self.case["expected_apk_sha256"].lower() for item in manifest["devices"].values()
            ):
                raise RuntimeError("A call participant does not have the required frozen APK hash")
            if self.relays:
                self.start_relayed_route(caller, callee)
            else:
                for phone in (callee, caller):
                    self.launch(phone)
                    self.endpoint_ready(phone)
                for phone, peer in ((caller, callee), (callee, caller)):
                    self.wait_for(phone, rf"BENCHMARK call discovered: node={self.devices[peer]['node_prefix']}")
            self.setup_anchors = [{"device": phone, "action": "call_setup", "time": self.phone_time(phone)}
                                  for phone in (callee, caller)]
            if self.ptt_scheduled():
                for phone in (callee, caller):
                    self.brackets += self.clock_brackets(phone, "start")
            self.invite()
            if self.relays:
                self.confirm_relayed_join(caller, callee)
            setup_action = self.case.get("setup_action", "accept")
            if setup_action in {"decline", "cancel"}:
                self.expected_terminal = True
            setup_event = self.control(callee if setup_action != "cancel" else caller, setup_action)
            if setup_action == "accept":
                setup_event["surface_evidence"] = {}
                for phone in (caller, callee):
                    # A voice call has no surface: its media is ready when the call turns ACTIVE.
                    surface = self.wait_for(phone, r"BENCHMARK call phase ACTIVE" if intercom.is_voice(self.case)
                                            else VIDEO_SURFACE_EVIDENCE)
                    setup_event["surface_evidence"][phone] = surface["time"]
                start = time.monotonic()
                actions = iter(sorted(self.case.get("actions", []), key=lambda item: item["at_s"]))
                next_action = next(actions, None)
                last_health = start
                while time.monotonic() < start + self.case.get("duration_s", 60):
                    self.check_cancelled()
                    if next_action and time.monotonic() - start >= next_action["at_s"]:
                        self.action(next_action)
                        next_action = next(actions, None)
                    if time.monotonic() - last_health >= 3:
                        for phone in sorted(self.participants):
                            self.health_check(phone)
                        last_health = time.monotonic()
                    time.sleep(0.2)
                if next_action:
                    raise RuntimeError("Measurement ended before every planned action executed")
                if not self.expected_terminal:
                    self.action({"phone": caller, "action": "end"})
            else:
                setup_event["terminal_evidence"] = {}
                for phone in (caller, callee):
                    ended = self.wait_for(phone, ENDED_EVIDENCE)
                    available = self.wait_for(phone, r"BENCHMARK call available:", ended["time"], timeout=10)
                    setup_event["terminal_evidence"][phone] = ended_proof(ended, available)
        except Exception as error:
            manifest["runner_error"] = str(error) or type(error).__name__
            self.beep()
            print(f"FAIL: {manifest['runner_error']}", flush=True)
        finally:
            if self.trace_session:
                for phone in sorted(self.endpoints):
                    for role in ("sender", "receiver"):
                        self.collect_media_traces(phone, role)
            if self.ptt_scheduled() and self.brackets:
                for phone in sorted(self.endpoints):
                    try:
                        self.brackets += self.clock_brackets(phone, "end")
                    except Exception:
                        pass  # Missing end brackets leave PTT latency UNMEASURED; never invent drift bounds
            end_times = {}
            for phone in sorted(self.participants):
                try:
                    when = self.phone_time(phone)
                    end_times[phone] = when
                    for log in self.logs:
                        if log["device"] == phone:
                            log["end"] = when
                except Exception:
                    pass
            for phone, (local_start, remote_start) in self.video_off_intervals.items():
                peer = callee if phone == caller else caller
                if phone in end_times:
                    self.outgoing_intervals.append({"device": phone, "action": "camera_off", "start": local_start,
                                                    "end": end_times[phone], "source": "app_control_ack"})
                if peer in end_times:
                    self.incoming_intervals.append({"device": peer, "action": "remote_camera_off", "start": remote_start,
                                                    "end": end_times[peer], "source": "authenticated_remote_camera"})
            for phone, start in self.background_intervals.items():
                if phone in end_times:
                    self.incoming_intervals.append({"device": phone, "action": "background", "start": start,
                                                    "end": end_times[phone], "source": "activity_lifecycle"})
            for process, handle in [*self.captures, *self.stack_captures]:
                process.terminate()
                try:
                    process.wait(timeout=3)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=3)
                handle.close()
            self.restore_log_buffers()
            for phone in sorted(self.participants):
                self.graceful_stop(phone)
            self.merge_device_logs()
            manifest.update(logs=self.logs, setup_anchors=self.setup_anchors, control_events=self.control_events,
                            health_events=self.health_events, outgoing_intervals=self.outgoing_intervals,
                            incoming_intervals=self.incoming_intervals, media_traces=self.trace_artifacts,
                            bluetooth_stack_logs=self.stack_logs, log_buffers=self.log_buffers)
            manifest["call_type"] = intercom.call_type(self.case)
            if self.relays:
                manifest["relays"] = list(self.relays)
                manifest["relay_route_events"] = self.relay_events
            if self.events:
                manifest["runner_events"] = self.events
            if self.brackets:
                manifest["clock_brackets"] = self.brackets
            (self.output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
        score = score_call_manifest(manifest, self.output)
        (self.output / "score.json").write_text(json.dumps(score, indent=2) + "\n", encoding="utf-8")
        print(f"Objective call score: {score['overall_status']}", flush=True)
        return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--devices", type=Path, required=True)
    parser.add_argument("--suite", type=Path, required=True)
    parser.add_argument("--case", required=True)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--apk-sha256", help="Require this frozen APK on both endpoints before launch")
    parser.add_argument("--output", type=Path, help="Explicit run-owned output directory; required for execution")
    parser.add_argument("--plan", action="store_true")
    args = parser.parse_args()
    if not args.plan and args.output is None:
        parser.error("Execution requires --output with a run-owned directory")
    if args.output is not None:
        args.output = output_path(args.output, "runs")
    args.devices, args.suite = resolve(args.devices), resolve(args.suite)
    devices = json.loads(args.devices.read_text(encoding="utf-8"))["devices"]
    case = next((item for item in json.loads(args.suite.read_text(encoding="utf-8"))["cases"] if item["id"] == args.case), None)
    if case is None:
        parser.error(f"Unknown call scenario: {args.case}")
    if args.apk_sha256:
        case = {**case, "expected_apk_sha256": args.apk_sha256}
    validate_call_case(case, devices)
    if args.plan:
        print(json.dumps(case, indent=2))
        return
    if relay_chain_limit(case):
        parser.error(relay_chain_limit(case))
    folder = args.output
    manifest = CallRunner(args.adb, devices, case, folder).run()
    print(f"Evidence: {folder / 'manifest.json'}", flush=True)
    if manifest.get("runner_error"):
        sys.exit(1)


if __name__ == "__main__":
    main()

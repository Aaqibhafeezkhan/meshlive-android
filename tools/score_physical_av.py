"""Measure independent source/screen events and microphone cues in one recording.

benchmark-physical-av-observation-v1 selects exact recording bytes, two native
ROIs, declared detector/search controls and the entire planned source interval.
Optional audio is a benchmark-audio-observation-v1 with an extracted microphone
WAV. cue_edges associates a cue identity with one observed source edge; these
associations are constraints, never timestamps or inferred clock anchors.
source_audio may select a separate native microphone channel in the same
recording to measure actual source acoustic stimulus to receiver speaker events.
An independently played reference preview cannot prove encoder dispatch timing.
Only the execution adapter supplies _capture_provenance. Acceptance additionally
requires hashed instrument/detector validation; synthetic math checks are not
instrument calibration. Speaker routing, echo, speech fidelity and encoder
dispatch to physical speaker timing remain separate, unmeasured obligations.
"""

import argparse
from collections import Counter
from copy import deepcopy
from fractions import Fraction
import json
from pathlib import Path
import wave

import numpy as np

import analyze_optical_latency as optical
import score_audio_sync as audio
from benchmark_layout import original, output_path, resolve as resolve_input


def controls(spec):
    result = {name: spec[name] for name in ("source_roi", "receiver_roi", "delay_search", "detector", "planned")}
    if spec.get("audio") is not None:
        result["cue_edges"] = spec.get("cue_edges", [])
        result["audio_plans"] = spec["audio"]["plans"]
        result["audio_correlation"] = spec["audio"].get("correlation", {})
    if spec.get("source_audio") is not None:
        result["source_audio_plans"] = spec["source_audio"]["plans"]
        result["source_audio_correlation"] = spec["source_audio"].get("correlation", {})
        result["microphone_channels"] = {name: spec[name]["capture"]["channel_selection"] for name in ("audio", "source_audio")}
    return result


def microphone_time_uncertainty_ms(spec, event):
    tick = spec["capture"].get("clock", {}).get("container_tick_ns")
    quantization = 0
    if tick is not None:
        numerator = audio.integer(tick["numerator"], "container tick numerator", 0)
        denominator = audio.integer(tick["denominator"], "container tick denominator", 1)
        quantization = float(Fraction(numerator, denominator))
    return event["capture_time_uncertainty_ns"] / 1e6 + quantization / 1e6 + 1000 / audio.RATE


def validation(spec, base, retained):
    acceptance = spec.get("acceptance")
    if acceptance is None:
        return None
    path, retained["validation"] = audio.evidence(acceptance["validation"], base)
    value = json.loads(path.read_text(encoding="utf-8-sig"))
    if value.get("schema") != "benchmark-physical-av-validation-v1" or value.get("status") != "VALIDATED":
        raise ValueError("Physical acceptance requires VALIDATED instrument/detector evidence")
    if value.get("detector_controls") != controls(spec):
        raise ValueError("Physical validation detector/ROI/search/interval controls differ")
    provenance = value.get("provenance", {})
    if provenance.get("origin") != "EXTERNAL_RECORDING" or not provenance.get("captures"):
        raise ValueError("Generated or reference-only checks cannot validate a physical instrument")
    for index, record in enumerate(provenance["captures"]):
        _, retained[f"validation_capture_{index}"] = audio.evidence(record, original(path).parent)
    for field in ("instrument_uncertainty_ns", "av_clock_uncertainty_ns"):
        audio.number(value.get(field), field, 0)
    # These must describe measured calibration and match the actual acquisition.
    if not isinstance(value.get("conditions"), dict) or not value["conditions"]:
        raise ValueError("Physical validation must record exposure/rolling-shutter/clock capture conditions")
    audio.integer(value["conditions"].get("exposure_ns"), "validated exposure_ns", 1)
    audio.integer(value["conditions"].get("rolling_shutter_ns"), "validated rolling_shutter_ns", 0)
    if value["conditions"].get("av_clock_validation") != "measured":
        raise ValueError("Physical validation must measure camera/microphone acquisition clock skew")
    if spec.get("source_audio") is not None:
        for field in ("cross_channel_uncertainty_ns", "room_path_uncertainty_ns"):
            audio.number(value.get(field), field, 0)
        for field in ("source_microphone_distance_m", "receiver_microphone_distance_m"):
            audio.number(value["conditions"].get(field), field, 0)
    return value


def cue_levels(spec, result, base):
    """Aligned signal diagnostics only; microphone gain/room are not calibrated."""
    reference_path, _ = audio.evidence(spec["reference"]["wav"], base)
    capture_path, _ = audio.evidence(spec["capture"]["wav"], base)
    reference, capture = audio.Pcm(reference_path), audio.Pcm(capture_path)
    rows = []
    try:
        for event in result["events"]:
            if event["status"] != "MATCHED":
                continue
            count = event["source_sample_count"]
            expected = reference.samples(event["source_sample"], count)
            observed = capture.samples(event["capture_sample"], count)
            expected -= np.mean(expected)
            observed -= np.mean(observed)
            energy = float(np.dot(expected, expected))
            if energy <= 0:
                continue
            gain = float(np.dot(observed, expected) / energy)
            residual = observed - gain * expected
            signal_rms = float(np.sqrt(np.mean((gain * expected) ** 2)))
            noise_rms = float(np.sqrt(np.mean(residual ** 2)))
            rows.append({"id": event["id"], "stream_generation": event["stream_generation"],
                         "loop_iteration": event["loop_iteration"], "capture_sample": event["capture_sample"],
                         "fitted_gain": gain, "aligned_signal_rms_pcm": signal_rms,
                         "aligned_residual_rms_pcm": noise_rms,
                         "residual_to_signal_ratio": noise_rms / signal_rms if signal_rms else None})
    finally:
        reference.close()
        capture.close()
    return rows


def microphone_observation(spec, base, role):
    copied = deepcopy(spec)
    copied.pop("acceptance", None)  # Physical instrument validation owns gates.
    copied.pop("visual_observations", None)
    if copied.get("capture", {}).get("sample_clock") is not None or copied["capture"].get("role") != role:
        raise ValueError("Only independently observed native microphone samples can supply this acoustic role")
    try:
        result = audio.analyze(copied, base)
        result["aligned_cues"] = cue_levels(copied, result, base)
    except wave.Error as error:
        raise ValueError(f"Invalid microphone PCM container: {error}") from error
    if "planned_timeline_complete" not in result.get("metrics", {}):
        raise ValueError("Microphone/reference format unsupported; native mono PCM16/48000 Hz required")
    return copied, result


def analyze(spec, base):
    base = Path(base)
    if spec.get("schema") != "benchmark-physical-av-observation-v1":
        raise ValueError("Expected benchmark-physical-av-observation-v1")
    retained = {}
    recording, retained["recording"] = audio.evidence(spec["recording"], base)
    planned = spec["planned"]
    if not isinstance(planned.get("clock"), str) or not planned["clock"]:
        raise ValueError("Named original recording clock required")
    start = audio.integer(planned.get("start_ns"), "planned start_ns")
    end = audio.integer(planned.get("end_ns"), "planned end_ns")
    minimum_events = audio.integer(planned.get("minimum_events"), "minimum_events", 1)
    expected_events = audio.integer(planned.get("expected_source_events"), "expected_source_events", 1)
    maximum_gap = audio.integer(planned.get("max_frame_gap_ns"), "max_frame_gap_ns", 1)
    if end <= start:
        raise ValueError("Complete nonempty planned source interval required")
    if minimum_events > expected_events:
        raise ValueError("Minimum accepted event count exceeds the entire expected source plan")
    source_box, receiver_box = spec["source_roi"], spec["receiver_roi"]
    for box in (source_box, receiver_box):
        if not isinstance(box, (list, tuple)) or len(box) != 4 or any(type(value) is not int for value in box) or (
                min(box[:2]) < 0 or min(box[2:]) <= 0):
            raise ValueError("Physical ROIs need four native integer coordinates with nonnegative origin and positive size")
    if (max(source_box[0], receiver_box[0]) < min(source_box[0] + source_box[2], receiver_box[0] + receiver_box[2])
            and max(source_box[1], receiver_box[1]) < min(source_box[1] + source_box[3], receiver_box[1] + receiver_box[3])):
        raise ValueError("Source target and receiver screen ROIs must be disjoint")
    search = spec["delay_search"]
    minimum = audio.number(search["minimum_ms"], "minimum_ms", 0)
    maximum = audio.number(search["maximum_ms"], "maximum_ms", minimum)
    try:
        observed = optical.analyze_video(recording, spec["source_roi"], spec["receiver_roi"],
                                         minimum, maximum, spec["detector"])
    except RuntimeError as error:
        raise ValueError(str(error)) from error
    if observed["video_sha256"] != retained["recording"]["sha256"]:
        raise ValueError("Recording changed during physical observation")
    times = [round(value * 1e9) for value in observed["frame_times_seconds"]]
    source = [(frame, sign) for frame, sign in observed["source_edges"] if start <= times[frame] < end]
    pairing = optical.pair_edges(source, observed["receiver_edges"], observed["frame_times_seconds"], minimum, maximum)
    counts = dict(Counter(row["status"] for row in pairing["events"]))
    interval_gaps = [b - a for a, b in zip(times, times[1:]) if a < end + maximum * 1e6 and b > start]
    prefix = max(0, times[0] - start)
    tail = max(0, end + round(maximum * 1e6) - times[-1])
    complete = (prefix == 0 and tail == 0 and bool(interval_gaps) and max(interval_gaps) <= maximum_gap
                and len(source) == expected_events and len(source) >= minimum_events)
    optical_complete = complete and len(pairing["matched"]) == len(source)
    proof = spec.get("_capture_provenance", {})
    validated = validation(spec, base, retained)
    qualified = (validated is not None and proof.get("physical_observation_status") == "MEASURED"
                 and proof.get("observation_plan") == controls(spec)
                 and proof.get("conditions") == validated["conditions"])
    instrument = validated["instrument_uncertainty_ns"] / 1e6 if validated else 0
    av_clock = validated["av_clock_uncertainty_ns"] / 1e6 if validated else 0
    result = {"schema": "benchmark-physical-av-v1", "measurement_status": "MEASURED" if pairing["matched"] else "UNMEASURED",
              "scope": {"clock": planned["clock"], "capture_provenance": proof,
                        "encoder_dispatch_to_speaker_latency": "UNMEASURED"},
              "evidence": retained, "planned": planned, "optical": {"pairing": pairing, "counts": counts,
                  "recording_frames": len(times), "frame_times_ns": times,
                  "source_events": len(source), "expected_source_events": expected_events,
                  "prefix_uncovered_ns": prefix, "tail_uncovered_ns": tail,
                  "maximum_observed_frame_gap_ns": max(interval_gaps) if interval_gaps else None,
                  "timeline_complete": complete, "all_events_matched": optical_complete,
                  "delay_ms": audio.summary([row["delay_ms"] for row in pairing["matched"]])},
              "audio": None, "source_audio": None, "av_events": [], "acoustic_path_events": [], "measurements": [],
              "limitations": ["Brightness edges identify sampled visible transitions, not unique displayed frames or blur.",
                  "Exposure, rolling shutter and acquisition A/V skew require measured instrument validation.",
                  "Source target to receiver screen is an observed optical path; source timestamps are not physical speaker anchors.",
                  "Microphone cues measure the recorded speaker path, including room/travel/microphone effects.",
                  "Aligned cue gain/residual are signal diagnostics, not perceptual speech/echo/routing/double-talk quality.",
                  "No acceptance without the complete predeclared interval and validated detector/capture conditions."]}
    # Compare upper bounds, not point estimates, with declared maxima.
    optical_upper = [row["delay_bounds_ms"][1] + instrument for row in pairing["matched"]]
    values = {"optical_delay_max_ms": max(optical_upper, default=None),
              "optical_delay_p95_ms": optical.percentile(optical_upper, .95),
              "optical_missing_events": counts.get("MISSING", 0), "optical_ambiguous_events": counts.get("AMBIGUOUS", 0)}
    audio_complete, audio_window_complete, av_complete, acoustic_complete = False, False, False, False
    if spec.get("source_audio") is not None and spec.get("audio") is None:
        raise ValueError("A source microphone needs its separate receiver microphone observation")
    if spec.get("audio") is not None:
        audio_spec, audio_result = microphone_observation(spec["audio"], base, "physical_microphone")
        result["audio"] = audio_result
        retained.update({"audio_" + key: value for key, value in audio_result["evidence"].items()})
        microphone_clock = audio_result["scope"].get("clock")
        same_clock = microphone_clock == planned["clock"]
        associations = spec.get("cue_edges", [])
        identities, edge_users = set(), Counter()
        for association in associations:
            identity = (audio.generation(association["stream_generation"]),
                        audio.integer(association["loop_iteration"], "loop_iteration", 0), association["id"])
            index = audio.integer(association["source_event_index"], "source_event_index", 0)
            if identity in identities or index >= len(pairing["events"]):
                raise ValueError("Cue associations must be unique and refer to an observed planned source event")
            identities.add(identity)
            edge_users[index] += 1
        associations = {(audio.generation(row["stream_generation"]), row["loop_iteration"], row["id"]): row["source_event_index"]
                        for row in associations}
        for cue in audio_result["events"]:
            identity = (cue["stream_generation"], cue["loop_iteration"], cue["id"])
            index = associations.get(identity)
            event = pairing["events"][index] if index is not None else None
            row = {"id": cue["id"], "stream_generation": identity[0], "loop_iteration": identity[1],
                   "source_event_index": index, "status": "UNMEASURED"}
            if (cue["status"] == "MATCHED" and "capture_time_ns" in cue and same_clock and event is not None
                    and event["status"] == "MATCHED" and edge_users[index] == 1):
                frame = event["receiver_frame"]
                delay = (cue["capture_time_ns"] - times[frame]) / 1e6
                uncertainty = microphone_time_uncertainty_ms(audio_spec, cue) + instrument + av_clock
                bounds = [delay - uncertainty, (cue["capture_time_ns"] - times[frame - 1]) / 1e6 + uncertainty]
                row.update(status="MEASURED", audio_minus_video_ms=delay, bounds_ms=bounds,
                           receiver_frame=frame, microphone_sample=cue["capture_sample"],
                           audio_time_ns=cue["capture_time_ns"])
            else:
                row["reason"] = "Cue/optical identity missing or ambiguous, or original microphone/screen clock coverage differs"
            result["av_events"].append(row)
        if identities - {(row["stream_generation"], row["loop_iteration"], row["id"]) for row in audio_result["events"]}:
            raise ValueError("Cue associations name an unplanned audio identity")
        audio_complete = (audio_result["metrics"]["planned_timeline_complete"]
                          and audio_result["metrics"]["planned_clock_interval_complete"] and same_clock)
        audio_window_complete = (audio_result["metrics"]["nominal_uncovered_samples"] == 0
                                 and audio_result["metrics"]["planned_clock_interval_complete"] and same_clock)
        av_complete = (audio_complete and optical_complete and bool(result["av_events"])
                       and all(row["status"] == "MEASURED" for row in result["av_events"]))
        audio_counts = audio_result["metrics"]["cue_counts"]
        values.update({"audio_missing_cues": audio_counts.get("MISSING", 0) + audio_counts.get("UNCOVERED", 0),
                       "audio_ambiguous_cues": audio_counts.get("AMBIGUOUS", 0),
                       "audio_clipped_samples": audio_result["metrics"]["capture_pcm"]["clipped_samples"],
                       "physical_av_offset_abs_ms": max((max(abs(value) for value in row["bounds_ms"])
                            for row in result["av_events"] if row["status"] == "MEASURED"), default=None)})
        measured_av = sorted((row for row in result["av_events"] if row["status"] == "MEASURED"),
                             key=lambda row: row["audio_time_ns"])
        result["av_offset_change"] = {"measurement_status": "UNMEASURED", "reason": "Two matched distinct observed cue times required"}
        if len(measured_av) >= 2 and measured_av[-1]["audio_time_ns"] > measured_av[0]["audio_time_ns"]:
            first, last = measured_av[0], measured_av[-1]
            result["av_offset_change"] = {"measurement_status": "MEASURED",
                "elapsed_capture_seconds": (last["audio_time_ns"] - first["audio_time_ns"]) / 1e9,
                "change_ms": last["audio_minus_video_ms"] - first["audio_minus_video_ms"],
                "bounds_ms": [last["bounds_ms"][0] - first["bounds_ms"][1], last["bounds_ms"][1] - first["bounds_ms"][0]],
                "basis": "Observed first/last microphone-minus-screen event, not a nominal source-time anchor"}
        if spec.get("source_audio") is not None:
            source_spec, source_result = microphone_observation(spec["source_audio"], base, "physical_source_microphone")
            result["source_audio"] = source_result
            retained.update({"source_audio_" + key: value for key, value in source_result["evidence"].items()})
            source_events = {(row["stream_generation"], row["loop_iteration"], row["id"]): row for row in source_result["events"]}
            source_clock_matches = source_result["scope"].get("clock") == microphone_clock == planned["clock"]
            path_uncertainty = ((validated["cross_channel_uncertainty_ns"] + validated["room_path_uncertainty_ns"]) / 1e6
                                if validated else 0)
            for cue in audio_result["events"]:
                identity = (cue["stream_generation"], cue["loop_iteration"], cue["id"])
                source_cue = source_events.get(identity)
                row = {"id": identity[2], "stream_generation": identity[0], "loop_iteration": identity[1], "status": "UNMEASURED"}
                if (source_cue is not None and cue["status"] == source_cue["status"] == "MATCHED"
                        and "capture_time_ns" in source_cue and "capture_time_ns" in cue and source_clock_matches):
                    delay = (cue["capture_time_ns"] - source_cue["capture_time_ns"]) / 1e6
                    uncertainty = (microphone_time_uncertainty_ms(audio_spec, cue)
                                   + microphone_time_uncertainty_ms(source_spec, source_cue) + path_uncertainty)
                    row.update(status="MEASURED", delay_ms=delay, bounds_ms=[delay - uncertainty, delay + uncertainty],
                        source_sample=source_cue["capture_sample"], receiver_sample=cue["capture_sample"],
                        basis="Actual same-clock source/receiver microphone cue onsets; no encoder-dispatch claim")
                else:
                    row["reason"] = "Source or receiver acoustic cue missing/ambiguous or microphone clock coverage differs"
                result["acoustic_path_events"].append(row)
            source_identities = set(source_events)
            receiver_identities = {(row["stream_generation"], row["loop_iteration"], row["id"]) for row in audio_result["events"]}
            acoustic_complete = (audio_complete and source_result["metrics"]["planned_timeline_complete"]
                and source_result["metrics"]["planned_clock_interval_complete"] and source_clock_matches
                and source_identities == receiver_identities and bool(result["acoustic_path_events"])
                and all(row["status"] == "MEASURED" for row in result["acoustic_path_events"]))
            values["observed_acoustic_path_max_ms"] = max((row["bounds_ms"][1] for row in result["acoustic_path_events"]
                                                            if row["status"] == "MEASURED"), default=None)
            values["observed_acoustic_negative_events"] = sum(row["bounds_ms"][1] < 0 for row in result["acoustic_path_events"]
                                                              if row["status"] == "MEASURED")
    for name, value in values.items():
        family = "acoustic" if name.startswith("audio_") or name.startswith("physical_av_") else "physical_latency"
        limit = spec.get("acceptance", {}).get("limits", {}).get(name)
        if limit is not None:
            limit = audio.number(limit, name + " limit", 0)
        usable = complete and (not name.startswith("optical_delay_") or optical_complete) and (
            not name.startswith("audio_") or audio_window_complete) and (not name.startswith("physical_av_") or av_complete)
        if name.startswith("observed_acoustic_"):
            usable = usable and acoustic_complete and proof.get("source_emission_role") == "source_acoustic_stimulus"
        approved = qualified and name in validated.get("validated_measurements", []) if validated else False
        verdict = "UNMEASURED"
        reason = "Requires full planned coverage, registered physical acquisition and matching instrument/detector validation"
        if approved and usable and value is not None and limit is not None:
            verdict = "PASS" if value <= limit else "FAIL"
            reason = "Compared upper sampling/instrument bounds with predeclared validated maximum"
        result["measurements"].append({"name": name, "family": family, "status": verdict,
            "measurement_status": "MEASURED" if value is not None else "UNMEASURED", "value": value, "limit": limit, "reason": reason})
    result["measurements"].append({"name": "acoustic_quality", "family": "acoustic", "status": "UNMEASURED", "value": None,
                                 "reason": "Physical cues do not measure speech fidelity, routing, echo or double-talk"})
    if spec.get("source_audio") is None:
        result["measurements"].append({"name": "source_speaker_latency", "family": "physical_latency", "status": "UNMEASURED", "value": None,
            "reason": "No independently observed source acoustic emission/capture anchor; nominal cue time is not absolute speaker delay"})
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--spec", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        destination = output_path(args.output, "runs")
        if destination.exists():
            raise ValueError("Output already exists; use a fresh explicit path")
        path = resolve_input(args.spec)
        spec = json.loads(path.read_text(encoding="utf-8-sig"))
        spec.pop("_capture_provenance", None)  # Standalone analysis cannot claim an execution binding.
        result = analyze(spec, original(path).parent)
        destination.parent.mkdir(parents=True, exist_ok=True)
        with destination.open("x", encoding="utf-8") as handle:
            handle.write(json.dumps(result, indent=2, allow_nan=False) + "\n")
    except (ValueError, OSError, KeyError, TypeError, RuntimeError) as error:
        parser.error(str(error))
    print(f"{result['measurement_status']}: physical observations; no whole-case acceptance. {destination}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

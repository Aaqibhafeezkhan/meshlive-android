"""Voice-call and push-to-talk requests, runner control mapping and offline scoring (experimental, default off)."""
import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import run_device_benchmark as device_runner
import run_private_call_benchmark as runner
import score_intercom_call as intercom
import test_run_private_call_benchmark as existing

CALL = "11111111-2222-3333-4444-555555555555"
VOICE_TUNABLES = {"voice_calls": True}
PTT_TUNABLES = {"voice_calls": True, "ptt_mode": True}


def line(second, message):
    return existing.PrivateCallBenchmarkTest.line(second, message)


class IntercomTestCase(unittest.TestCase):
    def setUp(self):
        base = existing.PrivateCallBenchmarkTest("test_independent_profiles_and_duplicate_identity_validation")
        base.setUp()
        self.devices, self.case = base.devices, base.case
        self.voice = {**copy.deepcopy(self.case), "call_type": "voice", "tunables": dict(VOICE_TUNABLES)}


class IntercomRequestTest(IntercomTestCase):
    def test_features_are_tunables_mapped_to_their_debug_extras_and_must_be_boolean(self):
        self.assertEqual({"test_voice_calls": True, "test_push_to_talk": False, "test_ptt_mode": True},
                         device_runner.tunable_extras({"voice_calls": True, "push_to_talk": False, "ptt_mode": True}))
        for name in ("voice_calls", "push_to_talk", "ptt_mode"):
            with self.assertRaises(ValueError):
                device_runner.tunable_extras({name: 1})
        with self.assertRaisesRegex(ValueError, "Unknown tunable"):
            device_runner.tunable_extras({"voice_call": True})

    def test_voice_request_is_valid_only_with_the_voice_tunable_and_a_known_type(self):
        self.assertEqual({"a", "b"}, runner.validate_call_case(self.voice, self.devices))
        for tunables in ({}, {"voice_calls": False}):
            with self.assertRaisesRegex(ValueError, "voice_calls"):
                runner.validate_call_case({**self.voice, "tunables": tunables}, self.devices)
        with self.assertRaisesRegex(ValueError, "call_type"):
            runner.validate_call_case({**self.voice, "call_type": "audio"}, self.devices)
        # An unspecified type is the existing video call and needs nothing.
        self.assertEqual({"a", "b"}, runner.validate_call_case(self.case, self.devices))
        self.assertEqual({"a", "b"}, runner.validate_call_case(
            {**self.case, "call_type": "video", "tunables": dict(VOICE_TUNABLES)}, self.devices))

    def test_voice_call_rejects_actions_that_need_video(self):
        for action in ("video_on", "video_off", "flip", "background", "foreground"):
            with self.subTest(action=action), self.assertRaisesRegex(ValueError, "no video"):
                runner.validate_call_case({**self.voice, "actions": [{"phone": "a", "action": action, "at_s": 5}]}, self.devices)
        ok = {**self.voice, "actions": [{"phone": "a", "action": "mute", "at_s": 5}, {"phone": "a", "action": "unmute", "at_s": 9}]}
        self.assertEqual({"a", "b"}, runner.validate_call_case(ok, self.devices))

    def test_ptt_schedule_needs_a_button_tunable_and_alternating_times(self):
        schedule = [{"phone": "a", "action": "ptt_press", "at_s": 5}, {"phone": "a", "action": "ptt_release", "at_s": 9}]
        with self.assertRaisesRegex(ValueError, "push_to_talk or ptt_mode"):
            runner.validate_call_case({**self.case, "actions": schedule}, self.devices)
        for tunables in ({"push_to_talk": True}, {"ptt_mode": True}):
            case = {**self.case, "tunables": tunables, "actions": schedule}
            self.assertEqual({"a", "b"}, runner.validate_call_case(case, self.devices))
        ptt = {**self.case, "tunables": {"ptt_mode": True}}
        for bad in ([{**schedule[1], "at_s": 5}, {**schedule[0], "at_s": 9}],  # release before press in time
                    [schedule[0], {**schedule[0], "at_s": 9}],  # two presses
                    [schedule[0], {**schedule[1], "at_s": 5}],  # same instant
                    [*schedule, {"phone": "a", "action": "mute", "at_s": 20}]):
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                runner.validate_call_case({**ptt, "actions": bad}, self.devices)

    def test_ptt_and_voice_changes_do_not_alter_other_case_identity(self):
        import benchmark_case_state as state
        self.assertNotEqual(state.request_key(self.case), state.request_key(self.voice))
        self.assertEqual(state.request_key(self.case), state.request_key(copy.deepcopy(self.case)))
        self.assertEqual("video", intercom.call_type(self.case))

    def test_both_endpoints_launch_with_the_feature_extras(self):
        case = {**self.voice, "tunables": {"voice_calls": True, "ptt_mode": True}}
        for phone in ("a", "b"):
            tunables = runner.broadcast_shape(case, phone)["tunables"]
            self.assertEqual({"test_voice_calls": True, "test_ptt_mode": True}, device_runner.tunable_extras(tunables))


class IntercomRunnerTest(IntercomTestCase):
    def make(self, case):
        instance = runner.CallRunner("adb", self.devices, case, Path("unused-intercom"))
        instance.wait_for = Mock(side_effect=lambda phone, expression, since=None, timeout=None:
                                 {"time": "10-03 00:00:09.000", "at": 9.0, "message": expression})
        instance.control = Mock(side_effect=lambda phone, name: {
            "device": phone, "action": name, "time": "10-03 00:00:01.000", "ack_time": "10-03 00:00:01.100"})
        instance.phone_time = Mock(return_value="10-03 00:00:00.000")
        return instance

    def test_voice_invite_uses_the_voice_control_and_waits_for_the_voice_offer(self):
        instance = self.make(self.voice)
        event = instance.invite()
        self.assertEqual(("a", "voice_call:000000000002"), instance.control.call_args.args)
        self.assertIn("offered=voice", event["voice_offer"]["message"])
        self.assertEqual("b", event["voice_offer"]["device"])

    def test_video_invite_is_unchanged(self):
        instance = self.make(self.case)
        event = instance.invite()
        self.assertEqual(("a", "call:000000000002"), instance.control.call_args.args)
        self.assertNotIn("voice_offer", event)

    def test_ptt_action_waits_for_the_local_line_then_the_peers_authenticated_signal(self):
        instance = self.make({**self.case, "tunables": {"ptt_mode": True}})
        messages = {"call_ptt": "BENCHMARK call_ptt: callId=x | action=press | muted=false | changed=true | source=debug | atNs=1"}
        def wait(phone, expression, since=None, timeout=None):
            text = messages["call_ptt"] if "call_ptt" in expression else expression
            return {"time": "10-03 00:00:09.000", "at": 9.0, "message": text}
        instance.wait_for = Mock(side_effect=wait)
        instance.control = Mock(side_effect=lambda phone, name: {
            "device": phone, "action": name, "time": "10-03 00:00:01.000", "ack_time": "10-03 00:00:01.100"})
        instance.control_events = []
        instance.action({"phone": "a", "action": "ptt_press", "at_s": 3})
        event = instance.control.return_value if False else None
        calls = [call.args for call in instance.wait_for.call_args_list]
        self.assertTrue(any("action=press " in expression for _, expression, *_ in calls))
        self.assertTrue(any(phone == "b" and "remote_mute muted=false" in expression for phone, expression, *_ in calls))

    def test_unchanged_ptt_press_expects_no_remote_signal(self):
        instance = self.make({**self.case, "tunables": {"push_to_talk": True}})
        def wait(phone, expression, since=None, timeout=None):
            return {"time": "10-03 00:00:09.000", "at": 9.0, "message":
                    "BENCHMARK call_ptt: callId=x | action=press | muted=false | changed=false | source=debug | atNs=1"}
        instance.wait_for = Mock(side_effect=wait)
        events = []
        instance.control = Mock(side_effect=lambda phone, name: events.append(
            {"device": phone, "action": name, "time": "t", "ack_time": "t"}) or events[-1])
        instance.action({"phone": "a", "action": "ptt_press", "at_s": 3})
        self.assertFalse(events[0]["ptt"]["changed"])
        self.assertNotIn("remote_ack", events[0])
        self.assertEqual({"a"}, {call.args[0] for call in instance.wait_for.call_args_list})

    def test_clock_brackets_record_host_time_around_each_device_clock_read(self):
        instance = self.make(self.case)
        instance.phone_time = Mock(side_effect=["10-03 00:00:01.000", "10-03 00:00:02.000"])
        result = instance.clock_brackets("a", "start", samples=2)
        self.assertEqual(["a", "a"], [item["device"] for item in result])
        self.assertEqual(["start", "start"], [item["label"] for item in result])
        self.assertEqual("10-03 00:00:02.000", result[1]["device_time"])
        self.assertTrue(all(len(item["host_before"]) == len("10-03 00:00:00.000") for item in result))

    def test_clock_brackets_are_taken_only_for_ptt_cases(self):
        self.assertFalse(self.make(self.case).ptt_scheduled())
        ptt = {**self.case, "tunables": {"ptt_mode": True}, "actions": [{"phone": "a", "action": "ptt_press", "at_s": 3}]}
        self.assertTrue(self.make(ptt).ptt_scheduled())


class VoiceFixture(IntercomTestCase):
    def log(self, phone, audio=(0, 40, 90, 140, 190), video=0, tx_video=0, extra="", mute=(), call=CALL, tx_audio=190,
            rx_final_audio=None, drops=0):
        peer = "b" if phone == "a" else "a"
        caller = phone == "a"
        text = line(0.1, f"BENCHMARK call available: node={self.devices[phone]['node_prefix']}")
        text += line(0.2, f"BENCHMARK call discovered: node={self.devices[peer]['node_prefix']} | transport=CLASSIC_RFCOMM")
        text += line(1, f"Encrypted private-call link to {self.devices[peer]['node_prefix']} over Classic RFCOMM (MPS 4096)")
        text += line(1.5, f"BENCHMARK call identity: callId={call}")
        if not caller:
            text += line(1.6, f"BENCHMARK call_type offer: callId={call} | offered=voice")
        text += line(2, f"BENCHMARK call_type: callId={call} | role={'caller' if caller else 'callee'} | requested=voice | "
                     f"local=voice | peerOfferedVoice={'false' if caller else 'true'} | voiceCallsEnabled=true")
        text += line(2.1, f"BENCHMARK call_mode: callId={call} | event=media_start | callType=voice | localVideo=false | "
                     "remoteVideo=false | layout=voice | atNs=1")
        text += line(2.5, "BENCHMARK call phase ACTIVE")
        for second, state in mute:
            text += line(second, f"BENCHMARK call remote_mute muted={state}")
        for index, count in enumerate(audio):
            second = 4 + 2 * index
            text += line(second, f"BENCHMARK media_age: role=call_rx | callId={call} | final=false | policy=off | videoPackets={video} | audioPackets={count}")
            text += line(second, f"Call RX 32 kbps | {video} video/{count} audio | audio drops {drops} | starting")
        text += line(20, f"BENCHMARK call tx: callId={call} | final=true | queue=0/32 | pendingAudio=0 | videoInputs={tx_video} | "
                     f"audioInputs={tx_audio} | configInputs=1 | videoWrites={tx_video} | audioWrites={tx_audio} | configWrites=1 | "
                     "queueDrops=0 | inactiveInputs=0 | mutedAudio=0")
        final = audio[-1] if rx_final_audio is None else rx_final_audio
        text += line(20.1, f"BENCHMARK media_age: role=call_rx | callId={call} | final=true | policy=off | videoPackets={video} | audioPackets={final}")
        text += line(20.1, f"BENCHMARK call rx: callId={call} | final=true | audioDrops={drops} | decoderDrops=unavailable | "
                     f"videoPackets={video} | audioPackets={final}")
        return text + extra

    def score(self, logs=None, case=None, thresholds=None, launch="ab", **extra):
        logs = logs or {phone: self.log(phone) for phone in "ab"}
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            for phone, text in logs.items():
                (folder / f"{phone}.log").write_text(text, encoding="utf-8")
            manifest = {"scenario": case or self.voice, "devices": self.devices,
                        "logs": [{"device": phone, "path": phone + ".log", "pid": 123} for phone in launch],
                        "setup_anchors": [{"device": phone, "time": "10-03 00:00:00.500"} for phone in "ab"], **extra}
            return runner.score_call_manifest(manifest, folder, thresholds)

    VIDEO_GATES = {"surface_p95_ms", "surface_p99_ms", "visible_fps", "unexpected_freeze_events", "encoder_fps",
                   "decoder_drops", "profile_fps_mismatches", "profile_resolution_mismatches", "profile_bitrate_mismatches",
                   "profile_encoder_mismatches", "longest_stall_ms", "frozen_time_share_percent", "effective_profile_fps",
                   "camera_input_drops", "raw_input_drops", "selected_profile_mismatches", "setup_to_first_surface_ms",
                   "actual_allocation_mismatches", "negotiated_remote_header_mismatches"}


class VoiceScoringTest(VoiceFixture):
    def test_clean_voice_call_measures_audio_and_no_video_gate(self):
        for thresholds in (None, {"version": "device-run-v4-user"}):
            with self.subTest(thresholds=thresholds):
                result = self.score(thresholds=thresholds)
                self.assertEqual("voice", result["call_type"])
                for device in result["devices"]:
                    names = {*device["gates"], *device["outgoing"]["gates"], *device["incoming"]["gates"]}
                    self.assertFalse(names & self.VIDEO_GATES, names & self.VIDEO_GATES)
                    self.assertEqual("NOT_APPLICABLE_VOICE", device["requested_profile_coverage"])
                    self.assertEqual({}, device["outgoing"]["optical_gates"])
                    for section in (device["gates"], device["outgoing"]["gates"], device["incoming"]["gates"]):
                        for name, item in section.items():
                            if name in {"discovery_ms", "setup_to_active_ms"}:
                                continue
                            self.assertEqual("PASS", item["status"], (device["device"], name, item))
                self.assertIn("Voice call", result["limitations"][0])

    def test_zero_video_is_proven_in_both_directions_by_packet_counters(self):
        result = self.score()
        for device in result["devices"]:
            sent = device["outgoing"]["gates"]["voice_video_packets_sent"]
            received = device["incoming"]["gates"]["voice_video_packets_received"]
            self.assertEqual((0, "PASS"), (sent["value"], sent["status"]))
            self.assertEqual((0, "PASS"), (received["value"], received["status"]))
            self.assertIn("video bytes", sent["basis"])

    def test_video_packets_in_either_direction_fail_a_voice_call(self):
        sent = self.score({"a": self.log("a", tx_video=3), "b": self.log("b")})
        self.assertEqual("FAIL", sent["devices"][0]["outgoing"]["gates"]["voice_video_packets_sent"]["status"])
        self.assertEqual("FAIL", sent["log_status"])
        received = self.score({"a": self.log("a"), "b": self.log("b", video=2)})
        self.assertEqual("FAIL", received["devices"][1]["incoming"]["gates"]["voice_video_packets_received"]["status"])
        periodic_only = self.score({"a": self.log("a", video=1), "b": self.log("b")})
        self.assertEqual("FAIL", periodic_only["devices"][0]["incoming"]["gates"]["voice_video_packets_received"]["status"])

    def test_missing_final_counters_leave_the_no_video_proof_unmeasured(self):
        logs = {phone: self.log(phone).replace("final=true", "final=false") for phone in "ab"}
        result = self.score(logs)
        gates = result["devices"][0]
        self.assertEqual("UNMEASURED", gates["outgoing"]["gates"]["voice_video_packets_sent"]["status"])
        self.assertEqual("UNMEASURED", gates["incoming"]["gates"]["voice_video_packets_received"]["status"])
        self.assertNotEqual("PASS", result["log_status"])

    def test_camera_encoder_or_surface_lines_fail_the_voice_pipeline_gate(self):
        for extra in ("Call camera: Streaming VP9 at 640x360, 15 FPS, 150 kbps, CBR mode 2", "Encoder output: 15.0 FPS, 150 kbps",
                      "BENCHMARK call first_surface: frames=1"):
            with self.subTest(extra=extra):
                result = self.score({"a": self.log("a", extra=line(10, extra)), "b": self.log("b")})
                self.assertEqual("FAIL", result["devices"][0]["gates"]["voice_no_video_pipeline"]["status"])

    def test_a_video_layout_or_wrong_call_type_fails(self):
        logs = {"a": self.log("a").replace("layout=voice", "layout=video"), "b": self.log("b")}
        self.assertEqual("FAIL", self.score(logs)["devices"][0]["gates"]["voice_layout"]["status"])
        logs = {"a": self.log("a").replace("local=voice", "local=video"), "b": self.log("b")}
        self.assertEqual("FAIL", self.score(logs)["devices"][0]["gates"]["voice_call_type"]["status"])
        logs = {"a": self.log("a"), "b": self.log("b").replace("offered=voice", "offered=video")}
        self.assertEqual("FAIL", self.score(logs)["devices"][1]["gates"]["voice_call_type"]["status"])

    def test_audio_stall_is_a_whole_report_window_with_no_new_audio(self):
        stalled = (0, 40, 40, 90, 140)
        result = self.score({"a": self.log("a", audio=stalled), "b": self.log("b")})
        stall = result["devices"][0]["incoming"]["gates"]["audio_receive_stall_windows"]
        self.assertEqual(("FAIL", 1), (stall["status"], stall["value"]))
        self.assertEqual(2000, stall["longest_stalled_run_ms"])
        self.assertEqual("PASS", result["devices"][1]["incoming"]["gates"]["audio_receive_stall_windows"]["status"])

    def test_peer_mute_excuses_its_silent_windows_but_not_others(self):
        stalled = (0, 40, 40, 40, 90)  # report windows 6-8 and 8-10 gain no audio
        excused = self.score({"a": self.log("a", audio=stalled, mute=((7.5, "true"), (12.5, "false"))), "b": self.log("b")})
        gate = excused["devices"][0]["incoming"]["gates"]["audio_receive_stall_windows"]
        self.assertEqual(("PASS", 0, 2), (gate["status"], gate["value"], gate["excused_silent_windows"]))
        partial = self.score({"a": self.log("a", audio=stalled, mute=((6.5, "true"), (7.0, "false"))), "b": self.log("b")})
        self.assertEqual(1, partial["devices"][0]["incoming"]["gates"]["audio_receive_stall_windows"]["value"])

    def test_no_audio_at_all_fails_the_audio_received_and_sent_gates(self):
        silent = self.score({"a": self.log("a", audio=(0, 0, 0, 0, 0), tx_audio=0), "b": self.log("b")})
        self.assertEqual("FAIL", silent["devices"][0]["incoming"]["gates"]["audio_packets_received"]["status"])
        self.assertEqual("FAIL", silent["devices"][0]["outgoing"]["gates"]["audio_packets_sent"]["status"])

    def test_audio_drops_still_decide_continuity(self):
        result = self.score({"a": self.log("a", drops=3), "b": self.log("b")})
        self.assertEqual("FAIL", result["devices"][0]["incoming"]["gates"]["audio_drops"]["status"])

    def test_ptt_mode_peer_that_never_presses_is_expected_silent(self):
        case = {**self.voice, "tunables": dict(PTT_TUNABLES)}
        quiet = {phone: self.log(phone, audio=(0, 0, 0, 0, 0), tx_audio=0) for phone in "ab"}
        result = self.score(quiet, case)
        for device in result["devices"]:
            self.assertEqual("PASS", device["incoming"]["gates"]["audio_packets_received"]["status"])
            self.assertEqual("PASS", device["outgoing"]["gates"]["audio_packets_sent"]["status"])
            self.assertEqual("PASS", device["incoming"]["gates"]["audio_receive_stall_windows"]["status"])
        leaking = self.score({"a": self.log("a"), "b": self.log("b", tx_audio=5)}, case)
        self.assertEqual("FAIL", leaking["devices"][1]["outgoing"]["gates"]["audio_packets_sent"]["status"])

    def test_declined_voice_call_needs_no_media_gates(self):
        case = {**self.voice, "setup_action": "decline"}
        result = self.score(case=case)
        self.assertNotIn("voice_call_type", result["devices"][0]["gates"])

    def test_voice_accept_is_evidenced_by_active_phases_not_surfaces(self):
        accept = {"device": "b", "action": "accept", "acknowledged": True, "surface_evidence": {"a": "t", "b": "t"}}
        evidence = self.score(control_events=[accept])["lifecycle"][0]["evidence"]
        self.assertEqual("PASS", evidence["both_active_voice_media"]["status"])
        self.assertNotIn("both_visible_surfaces", evidence)

    def test_voice_invite_control_is_scored_with_its_offer_evidence(self):
        event = {"device": "a", "action": "voice_call:000000000002", "acknowledged": True,
                 "remote_ack": {"device": "b"}, "outgoing_phase": {"time": "t"}, "voice_offer": {"device": "b"}}
        result = self.score(control_events=[event])
        evidence = result["lifecycle"][0]["evidence"]
        self.assertEqual("PASS", evidence["voice_offer_seen"]["status"])
        self.assertEqual("PASS", evidence["outgoing_phase"]["status"])
        missing = self.score(control_events=[{k: v for k, v in event.items() if k != "voice_offer"}])
        self.assertEqual("UNMEASURED", missing["lifecycle"][0]["evidence"]["voice_offer_seen"]["status"])


class PushToTalkScoringTest(VoiceFixture):
    # a is the talker; clocks: a = host + 0.100 s, b = host - 0.300 s, each with 40 ms round trips.
    BRACKETS = [
        {"device": "a", "label": "start", "host_before": "10-03 00:00:00.000", "host_after": "10-03 00:00:00.040", "device_time": "10-03 00:00:00.120"},
        {"device": "a", "label": "end", "host_before": "10-03 00:01:00.000", "host_after": "10-03 00:01:00.040", "device_time": "10-03 00:01:00.120"},
        {"device": "b", "label": "start", "host_before": "10-03 00:00:10.000", "host_after": "10-03 00:00:10.040", "device_time": "10-03 00:00:09.720"},
        {"device": "b", "label": "end", "host_before": "10-03 00:01:10.000", "host_after": "10-03 00:01:10.040", "device_time": "10-03 00:01:09.720"},
    ]

    def ptt_case(self):
        return {**self.voice, "tunables": dict(PTT_TUNABLES),
                "actions": [{"phone": "a", "action": "ptt_press", "at_s": 5}, {"phone": "a", "action": "ptt_release", "at_s": 12}]}

    def events(self, changed=True, hold=True):
        press = {"device": "a", "action": "ptt_press", "acknowledged": True, "time": "10-03 00:00:24.000",
                 "ack_time": "10-03 00:00:25.150", "ptt": {"time": "10-03 00:00:25.200", "changed": changed},
                 **({"remote_ack": {"device": "b", "time": "10-03 00:00:25.000"}} if changed else {})}
        release = {"device": "a", "action": "ptt_release", "acknowledged": True, "time": "10-03 00:00:30.000",
                   "ack_time": "10-03 00:00:31.150", "ptt": {"time": "10-03 00:00:31.200", "changed": True},
                   "remote_ack": {"device": "b", "time": "10-03 00:00:31.000"}}
        return [press, release] if hold else [press]

    def b_log(self, rows, mute=True):
        text = self.log("b", audio=(0,), extra="")
        text = "\n".join(item for item in text.splitlines() if "role=call_rx" not in item and "Call RX" not in item and
                         "call rx:" not in item) + "\n"
        text += line(25.0, "BENCHMARK call remote_mute muted=false")
        text += line(31.0, "BENCHMARK call remote_mute muted=true")
        for second, count in rows:
            text += line(second, f"BENCHMARK media_age: role=call_rx | callId={CALL} | final=false | policy=off | videoPackets=0 | audioPackets={count}")
        text += line(40, f"BENCHMARK call rx: callId={CALL} | final=true | audioDrops=0 | decoderDrops=unavailable | videoPackets=0 | audioPackets=0")
        return text

    def run_ptt(self, rows, events=None, brackets=True, case=None):
        logs = {"a": self.log("a"), "b": self.b_log(rows)}
        return self.score(logs, case or self.ptt_case(), control_events=events or self.events(),
                          **({"clock_brackets": self.BRACKETS} if brackets else {}))

    def test_press_to_remote_unmute_is_measured_across_phones_with_its_uncertainty(self):
        result = self.run_ptt([(20, 0), (24, 0), (26, 40), (28, 90), (32, 100)])
        press = next(item for item in result["lifecycle"] if item["action"] == "ptt_press")
        latency = press["evidence"]["press_to_remote_unmute_ms"]
        self.assertAlmostEqual(200, latency["value"], delta=1)
        self.assertAlmostEqual(40, latency["uncertainty_ms"], delta=1)
        self.assertEqual("DIAGNOSTIC", latency["status"])
        self.assertIsNone(latency["limit"])
        self.assertEqual("PASS", press["evidence"]["audio_arrival_observed"]["status"])
        bound = press["evidence"]["press_to_audio_report_upper_bound_ms"]
        self.assertAlmostEqual(1200, bound["value"], delta=1)
        self.assertEqual("DIAGNOSTIC", bound["status"])
        self.assertIn("exact_press_to_audio_arrival", result["ptt"])
        self.assertTrue(result["ptt"]["exact_press_to_audio_arrival"].startswith("UNMEASURED"))
        self.assertEqual("PASS", press["gate"]["status"])

    def test_release_signal_latency_is_reported_without_an_audio_gate(self):
        result = self.run_ptt([(20, 0), (24, 0), (26, 40), (28, 90), (32, 100)])
        release = next(item for item in result["lifecycle"] if item["action"] == "ptt_release")
        self.assertIn("release_to_remote_mute_ms", release["evidence"])
        self.assertNotIn("audio_arrival_observed", release["evidence"])

    def test_without_clock_brackets_latency_is_unmeasured_not_guessed(self):
        result = self.run_ptt([(20, 0), (24, 0), (26, 40), (28, 90), (32, 100)], brackets=False)
        press = next(item for item in result["lifecycle"] if item["action"] == "ptt_press")
        self.assertEqual("UNMEASURED", press["evidence"]["press_to_remote_unmute_ms"]["status"])
        self.assertIsNone(press["evidence"]["press_to_remote_unmute_ms"]["value"])
        self.assertEqual("UNMEASURED", press["evidence"]["press_to_audio_report_upper_bound_ms"]["status"])
        self.assertEqual("UNMEASURED", press["gate"]["status"])

    def test_a_single_clock_end_leaves_drift_unbounded_and_latency_unmeasured(self):
        brackets = [item for item in self.BRACKETS if item["label"] == "start"]
        with patch.object(self, "BRACKETS", brackets):
            result = self.run_ptt([(20, 0), (24, 0), (26, 40), (32, 100)])
        press = next(item for item in result["lifecycle"] if item["action"] == "ptt_press")
        self.assertEqual("UNMEASURED", press["evidence"]["press_to_remote_unmute_ms"]["status"])

    def test_an_accepted_press_with_no_new_audio_fails(self):
        result = self.run_ptt([(20, 0), (24, 0), (26, 0), (28, 0), (32, 0)])
        press = next(item for item in result["lifecycle"] if item["action"] == "ptt_press")
        self.assertEqual("FAIL", press["evidence"]["audio_arrival_observed"]["status"])
        self.assertEqual("FAIL", press["gate"]["status"])
        self.assertEqual("FAIL", result["log_status"])

    def test_a_hold_shorter_than_one_second_cannot_attribute_audio(self):
        events = self.events()
        events[1]["remote_ack"]["time"] = "10-03 00:00:25.500"
        result = self.run_ptt([(20, 0), (24, 0), (26, 0), (32, 0)], events)
        press = next(item for item in result["lifecycle"] if item["action"] == "ptt_press")
        self.assertEqual("UNMEASURED", press["evidence"]["audio_arrival_observed"]["status"])

    def test_a_press_that_changes_nothing_expects_no_signal(self):
        events = self.events(changed=False, hold=False)
        result = self.run_ptt([(20, 0), (26, 40)], events)
        press = result["lifecycle"][0]
        self.assertNotIn("remote_ack", press["evidence"])
        self.assertNotIn("audio_arrival_observed", press["evidence"])
        self.assertFalse(result["ptt"]["events"][0]["state_changed"])

    def test_no_ptt_summary_without_ptt_actions(self):
        self.assertNotIn("ptt", self.score())

    def test_clock_map_interpolates_drift_and_rejects_a_single_end(self):
        maps = intercom.clock_maps({"clock_brackets": self.BRACKETS})
        host, uncertainty = maps["a"].host_time(intercom.timestamp("10-03 00:00:25.200"))
        self.assertAlmostEqual(intercom.timestamp("10-03 00:00:25.100"), host, places=3)
        self.assertAlmostEqual(0.02, uncertainty, places=3)
        self.assertIsNone(intercom.clock_maps({"clock_brackets": self.BRACKETS[:1]})["a"].host_time(1.0))


class SuiteAnalysisTest(VoiceFixture):
    def test_voice_analysis_scores_no_picture_and_marks_video_families_not_applicable(self):
        import run_benchmark_suite as suite
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            for phone in "ab":
                (folder / f"{phone}.log").write_text(self.log(phone), encoding="utf-8")
            manifest = {"mode": "private_call", "scenario": {**self.voice, "id": "voice-call"}, "devices": self.devices,
                        "logs": [{"device": phone, "path": phone + ".log", "pid": 123} for phone in "ab"],
                        "setup_anchors": [{"device": phone, "time": "10-03 00:00:00.500"} for phone in "ab"],
                        "source_kind": "live_camera_bidirectional", "run_id": "voice-run"}
            (folder / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
            with patch.object(suite, "score_phase_delivery", side_effect=AssertionError("picture scoring on a voice call")):
                result = suite.analyze(folder / "manifest.json", folder / "analysis", ["transport", "connection", "topology"])
        for name in ("picture", "smoothness"):
            self.assertEqual("UNMEASURED", result["families"][name]["status"])
            self.assertIn("voice call carries no video", result["families"][name]["reason"])
        self.assertEqual([], result["measurement_errors"])
        transport = {item["name"]: item["status"] for item in result["families"]["transport"]["measurements"]}
        self.assertEqual("PASS", transport["voice_video_packets_sent"])
        self.assertEqual("PASS", transport["audio_receive_stall_windows"])
        self.assertTrue(set(transport) .isdisjoint(VoiceScoringTest.VIDEO_GATES))
        self.assertEqual([], [item for item in result["families"]["transport"]["measurements"]
                              if item["status"] not in {"PASS", "DIAGNOSTIC"}])
        self.assertEqual("PASS", result["families"]["transport"]["status"])


class DiscoveryAndSetupGateTest(VoiceFixture):
    """Discovery is judged on the endpoint that scans an already-advertising peer; voice setup has its own, undecided limit."""

    def logs_in_launch_order(self, order):
        return {"logs": [{"device": phone} for phone in order]}

    def slow_discovery(self, phone, seconds):
        peer = "b" if phone == "a" else "a"
        message = f"BENCHMARK call discovered: node={self.devices[peer]['node_prefix']} | transport=CLASSIC_RFCOMM"
        return self.log(phone).replace(line(0.2, message), line(seconds, message))

    def discovery(self, result, phone):
        return next(item for item in result["devices"] if item["device"] == phone)["gates"]["discovery_ms"]

    def test_the_first_launched_callee_includes_the_runner_gap_so_it_is_diagnostic_and_the_caller_decides(self):
        # The runner launches the callee first; its scan finds the caller only after the caller is launched.
        result = self.score({"a": self.log("a"), "b": self.slow_discovery("b", 2.6)}, launch="ba")
        callee, caller = self.discovery(result, "b"), self.discovery(result, "a")
        self.assertEqual(("DIAGNOSTIC", "FAIL"), (callee["status"], callee["measured_status"]))
        self.assertAlmostEqual(2500, callee["value"], delta=1)
        self.assertEqual(1500, callee["limit"])
        self.assertFalse(callee["decides"])
        self.assertIn("launch gap", callee["basis"])
        self.assertEqual("PASS", caller["status"])
        self.assertEqual("PASS", result["log_status"])

    def test_a_slow_scan_of_an_already_advertising_peer_still_fails(self):
        result = self.score({"a": self.slow_discovery("a", 2.1), "b": self.log("b")}, launch="ba")
        caller = self.discovery(result, "a")
        self.assertEqual("FAIL", caller["status"])
        self.assertAlmostEqual(2000, caller["value"], delta=1)
        self.assertEqual("FAIL", result["log_status"])

    def test_launch_order_comes_from_the_scoped_logs_not_the_role(self):
        # Caller launched first: its value carries the gap, and the callee scanning the advertising caller decides.
        result = self.score({"a": self.slow_discovery("a", 2.6), "b": self.log("b")}, launch="ab")
        self.assertEqual("DIAGNOSTIC", self.discovery(result, "a")["status"])
        self.assertEqual("PASS", self.discovery(result, "b")["status"])
        self.assertEqual("PASS", result["log_status"])
        self.assertEqual(["b", "a"], intercom.launch_order(self.case, self.logs_in_launch_order("ba")))
        self.assertEqual(["b", "a"], intercom.launch_order(self.case, {"logs": [{"device": "a"}]}))  # runner order fallback

    def test_missing_discovery_evidence_stays_unmeasured_for_both_endpoints(self):
        for phone in "ab":
            with self.subTest(phone=phone):
                text = "\n".join(item for item in self.log(phone).splitlines() if "call discovered" not in item) + "\n"
                other = "b" if phone == "a" else "a"
                result = self.score({phone: text, other: self.log(other)})
                gate = self.discovery(result, phone)
                self.assertIsNone(gate["value"])
                self.assertEqual("UNMEASURED", gate.get("measured_status", gate["status"]))
                self.assertNotEqual("PASS", gate["status"])

    def test_video_calls_use_the_same_discovery_rule(self):
        case = {"caller": "a", "callee": "b", "call_type": "video"}
        manifest = self.logs_in_launch_order("ba")
        rows = [{"message": "BENCHMARK call available: node=aaa", "at": 10.0},
                {"message": "BENCHMARK call discovered: node=bbb | transport=BLE_GATT", "at": 12.8}]
        first = intercom.discovery_gate(case, manifest, "b", rows, [], "bbb", 1500)
        second = intercom.discovery_gate(case, manifest, "a", rows, [], "bbb", 1500)
        self.assertEqual(("DIAGNOSTIC", "FAIL"), (first["status"], first["measured_status"]))
        self.assertEqual(("FAIL", 2800), (second["status"], round(second["value"])))

    def test_clock_brackets_give_discovery_from_the_later_availability_as_a_diagnostic(self):
        case = {"caller": "a", "callee": "b"}
        manifest = self.logs_in_launch_order("ba")
        # Both phone clocks equal the host clock (40 ms round trips). b is available at 0.1 s, a at 2.4 s; b finds a at 2.9 s.
        brackets = [{"device": phone, "label": label, "host_before": f"10-03 {clock}.000", "host_after": f"10-03 {clock}.040",
                     "device_time": f"10-03 {clock}.020"}
                    for phone in "ab" for label, clock in (("start", "00:00:00"), ("end", "00:01:00"))]
        clocks = intercom.clock_maps({"clock_brackets": brackets})
        at = lambda second: intercom.timestamp(f"10-03 00:00:{second:06.3f}")
        own = [{"message": "BENCHMARK call available: node=bbb", "at": at(0.1)},
               {"message": "BENCHMARK call discovered: node=aaa | transport=BLE_GATT", "at": at(2.9)}]
        peer = [{"message": "BENCHMARK call available: node=aaa", "at": at(2.4)}]
        result = intercom.discovery_gate(case, manifest, "b", own, peer, "aaa", 1500, clocks)
        self.assertEqual("DIAGNOSTIC", result["status"])
        self.assertAlmostEqual(2800, result["value"], delta=1)
        self.assertAlmostEqual(500, result["peer_ready_to_discovery_ms"], delta=1)
        self.assertAlmostEqual(40, result["peer_ready_uncertainty_ms"], delta=1)
        without = intercom.discovery_gate(case, manifest, "b", own, peer, "aaa", 1500, {})
        self.assertIsNone(without["peer_ready_to_discovery_ms"])
        self.assertIn("clock brackets", without["peer_ready_reason"])

    def slow_active(self, seconds):
        return self.log("a").replace(line(2.5, "BENCHMARK call phase ACTIVE"), line(seconds, "BENCHMARK call phase ACTIVE"))

    def setup_gate(self, result, phone="a"):
        return next(item for item in result["devices"] if item["device"] == phone)["gates"]["setup_to_active_ms"]

    def test_voice_setup_to_active_is_reported_but_undecided_by_default(self):
        result = self.score({"a": self.slow_active(8.5), "b": self.log("b")})
        gate = self.setup_gate(result)
        self.assertEqual(("DIAGNOSTIC", "UNMEASURED"), (gate["status"], gate["measured_status"]))
        self.assertAlmostEqual(8000, gate["value"], delta=1)
        self.assertIsNone(gate["limit"])
        self.assertIn("GATT", gate["basis"])
        self.assertEqual("PASS", result["log_status"])

    def test_voice_setup_to_active_never_borrows_the_video_startup_limit(self):
        for thresholds in ({"max_startup_ms": 1}, {"max_startup_ms": 3000}, {"version": "device-run-v4-user"}):
            with self.subTest(thresholds=thresholds):
                gate = self.setup_gate(self.score({"a": self.slow_active(8.5), "b": self.log("b")}, thresholds=thresholds))
                self.assertEqual("DIAGNOSTIC", gate["status"])
                self.assertIsNone(gate["limit"])

    def test_an_explicit_voice_setup_threshold_decides(self):
        slow = {"a": self.slow_active(8.5), "b": self.log("b")}
        failed = self.score(slow, thresholds={intercom.VOICE_SETUP_LIMIT_KEY: 5000})
        self.assertEqual(("FAIL", 5000), (self.setup_gate(failed)["status"], self.setup_gate(failed)["limit"]))
        self.assertEqual("FAIL", failed["log_status"])
        passed = self.score(slow, thresholds={intercom.VOICE_SETUP_LIMIT_KEY: 9000})
        self.assertEqual("PASS", self.setup_gate(passed)["status"])

    def test_missing_active_phase_leaves_voice_setup_unmeasured(self):
        text = "\n".join(item for item in self.log("a").splitlines() if "phase ACTIVE" not in item) + "\n"
        self.assertEqual("UNMEASURED", self.setup_gate(self.score({"a": text, "b": self.log("b")}))["status"])

    def test_video_startup_and_discovery_limits_are_unchanged(self):
        self.assertEqual(3000, runner.DEFAULT_THRESHOLDS["max_startup_ms"])
        self.assertEqual(1500, runner.DEFAULT_THRESHOLDS["max_discovery_ms"])
        self.assertNotIn(intercom.VOICE_SETUP_LIMIT_KEY, runner.DEFAULT_THRESHOLDS)


class SuiteIntegrationTest(IntercomTestCase):
    def test_voice_jobs_drop_only_the_video_families_and_say_so(self):
        import run_benchmark_suite as suite
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            video = {**copy.deepcopy(self.case), "id": "video-call"}
            voice = {**self.voice, "id": "voice-call"}
            suite.write(folder / "suite.json", {"cases": [video, voice]})
            jobs = suite.load_jobs(folder / "suite.json", self.devices)
        by_id = {job["case"]["id"]: job for job in jobs}
        self.assertIn("picture", by_id["video-call"]["required_families"])
        self.assertNotIn("not_applicable_families", by_id["video-call"])
        self.assertEqual(["picture", "smoothness"], by_id["voice-call"]["not_applicable_families"])
        self.assertNotIn("picture", by_id["voice-call"]["required_families"])
        self.assertNotIn("smoothness", by_id["voice-call"]["required_families"])
        self.assertIn("transport", by_id["voice-call"]["required_families"])
        self.assertIn("acoustic", by_id["voice-call"]["required_families"])

    def test_voice_gate_names_land_in_the_right_families(self):
        import run_benchmark_suite as suite
        self.assertEqual("connection", suite.family_for("setup_to_active_ms"))
        for name in ("voice_video_packets_sent", "audio_receive_stall_windows", "audio_packets_received", "voice_layout"):
            self.assertEqual("transport", suite.family_for(name))


if __name__ == "__main__":
    unittest.main()


class NoVideoPipelineFinalLineTest(__import__("unittest").TestCase):
    def test_a_zero_frame_final_continuity_line_is_not_video(self):
        import score_intercom_call as scorer
        rows = [{"message": "Private call visible continuity FINAL: 0 frames | 0 freeze event(s)", "at": 0.0}]
        self.assertEqual(0, scorer.no_video_pipeline_gate(rows)["value"])
        rows.append({"message": "Private call visible continuity FINAL: 412 frames | 0 freeze event(s)", "at": 1.0})
        self.assertEqual(1, scorer.no_video_pipeline_gate(rows)["value"])

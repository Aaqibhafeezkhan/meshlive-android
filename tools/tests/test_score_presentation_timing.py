import contextlib
import io
import json
import tempfile
import unittest
from collections import Counter
from pathlib import Path

from tools.score_presentation_timing import main, parse_log


PREFIX = "BENCHMARK video presentation v1 "
COLUMNS = "seq,kind,match,generation,ptsUs,targetNs,releaseCallNs,reportedNs,observedNowNs,observedMinusTargetNs,reportedMinusTargetNs,codecListenerDelayNs,unseenEarlierTextureReleases"


def event(seq, kind="RELEASE", match="RECORDED", generation=1, pts=1, target=100_000_000,
          release=80_000_000, reported=None, observed=80_000_000):
    row = [seq, kind, match, generation, pts, target, release, reported, observed,
           observed - target if target is not None else None,
           reported - target if reported is not None and target is not None else None,
           observed - reported if kind == "CODEC" and reported is not None else None, 0]
    return ",".join("NA" if value is None else str(value) for value in row)


def snapshot(scope, report, rows, final=True, cumulative=None, parts=None, columns=COLUMNS):
    counts = Counter(".".join(row.split(",")[1:3]) for row in (cumulative if cumulative is not None else rows))
    count_text = ",".join(f"{key}:{value}" for key, value in counts.items()) or "NONE"
    lines = [f"{PREFIX}META scope={scope} report={report} generation=1 final={str(final).lower()} decoder=c2.test "
             f"rollingSamples={len(rows)} capacity=512 counts={count_text} unobservedCodec=1 unobservedTexture=1 "
             "releaseOverwrites=0 unobservedCodecOverflows=0 unobservedTextureOverflows=0 "
             f"eventOverwrites={max(0, len(cumulative or rows) - len(rows))} generationDiscarded=0 "
             "generationScope=listener_or_current_decoder_not_buffer_epoch optical=UNMEASURED"]
    if columns is not None:
        lines.append(f"{PREFIX}COLUMNS scope={scope} report={report} {columns}")
    parts = parts if parts is not None else [(1, 1, rows)] if rows else []
    lines.extend(f"{PREFIX}DATA scope={scope} report={report} part={part}/{total} rows={';'.join(part_rows)}"
                 for part, total, part_rows in parts)
    return "\n".join(lines)


class PresentationTimingTest(unittest.TestCase):
    def test_signed_early_timing_and_null_fields_survive_cli_json(self):
        rows = [event(1), event(2, "CODEC", "MATCHED", reported=91_000_000, observed=80_000_000),
                event(3, "TEXTURE", "MATCHED", reported=100_000_000, observed=95_000_000)]
        with tempfile.TemporaryDirectory() as directory:
            log, output = Path(directory) / "input.log", Path(directory) / "result.json"
            log.write_text("10-03 17:30:00.000 I BluetoothVideo: " + snapshot(500, 1, rows), encoding="utf-8")
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(0, main(["--log", str(log), "--output", str(output)]))
            result = json.loads(output.read_text(encoding="utf-8"))
        self.assertEqual("MEASURED", result["state"])
        self.assertEqual("UNMEASURED", result["optical"])
        self.assertIsNone(result["events"][0]["reportedNs"])
        groups = {group["kind"]: group for group in result["scopes"][0]["groups"]}
        codec = groups["CODEC"]["timing"]
        self.assertEqual(-9_000_000, codec["reportedMinusTargetNs"]["p50"])
        self.assertEqual(-20_000_000, codec["observedMinusTargetNs"]["min"])
        self.assertEqual(-11_000_000, codec["reportedToCallbackNs"]["max"])
        texture = groups["TEXTURE"]["timing"]
        self.assertEqual(-5_000_000, texture["reportedToCallbackNs"]["p95"])
        self.assertEqual(1, texture["reportedToCallbackNs"]["negative_samples"])

    def test_snapshot_repeats_dedupe_by_scope_and_seq_not_timestamp_or_generation(self):
        first = [event(1), event(2, "CODEC", "MATCHED", reported=100_000_000, observed=101_000_000),
                 event(3, "TEXTURE", "MATCHED", reported=100_000_000, observed=102_000_000)]
        fourth = event(4, "CODEC", "DUPLICATE", reported=100_000_000, observed=103_000_000)
        other_scope = [row.replace(",1,1,", ",2,1,", 1) for row in first]
        text = "\n".join([snapshot(500, 1, first, final=False),
                          snapshot(500, 2, [*first[1:], fourth], cumulative=[*first, fourth]),
                          snapshot(600, 1, other_scope)])
        result = parse_log(text)
        self.assertEqual(7, result["usable_events"])
        self.assertEqual(2, result["identical_repeated_event_rows"])
        self.assertEqual([], result["measurement_gaps"])
        self.assertEqual(4, result["scopes"][0]["cumulative_event_total"])
        self.assertEqual(1, result["scopes"][0]["final_meta"]["eventOverwrites"])
        self.assertEqual([1, 2], result["events"][1]["source_reports"])
        self.assertEqual({2}, {group["generation"] for group in result["scopes"][1]["groups"]})

    def test_missing_part_report_and_event_sequence_are_measurement_gaps(self):
        rows = [event(1), event(2, "CODEC", "MATCHED", reported=100_000_000),
                event(3, "TEXTURE", "MATCHED", reported=100_000_000)]
        result = parse_log(snapshot(500, 2, rows, parts=[(1, 3, rows[:1]), (3, 3, rows[2:])]))
        self.assertEqual("MEASURED_WITH_GAPS", result["state"])
        codes = {gap["code"] for gap in result["measurement_gaps"]}
        self.assertTrue({"missing_parts", "missing_reports", "missing_event_sequences", "rolling_sample_count_mismatch"} <= codes)
        scope = result["scopes"][0]
        self.assertEqual([{"start": 2, "end": 2, "count": 1}], scope["missing_event_sequence_ranges"])
        self.assertEqual(3, scope["cumulative_event_total"])
        self.assertEqual(2, scope["usable_events"])
        self.assertEqual(1, scope["final_meta"]["unobservedTexture"])
        self.assertNotIn("media_loss", result)

    def test_ambiguous_unmatched_duplicate_and_stale_events_are_never_rematched(self):
        rows = [event(1), event(2, "TEXTURE", "AMBIGUOUS", pts=None, target=None, release=None,
                                reported=99_999_999, observed=101_000_000),
                event(3, "CODEC", "UNMATCHED", pts=2, target=None, release=None,
                      reported=100_000_001, observed=101_000_000),
                event(4, "CODEC", "DUPLICATE", reported=100_000_000, observed=101_000_000),
                event(5, "TEXTURE", "STALE_GENERATION", target=None, release=None, pts=None,
                      reported=100_000_000, observed=101_000_000)]
        result = parse_log(snapshot(500, 1, rows))
        self.assertEqual(5, result["usable_events"])
        self.assertEqual("UNMEASURED", result["scopes"][0]["timing_state"])
        self.assertTrue(all(group["timing"] is None for group in result["scopes"][0]["groups"]))
        self.assertIsNone(result["events"][1]["targetNs"])
        self.assertEqual(99_999_999, result["events"][1]["reportedNs"])
        self.assertEqual(0, result["identical_repeated_event_rows"])

    def test_conflicting_identity_keeps_variants_and_excludes_both_from_summary(self):
        first = event(1, "CODEC", "MATCHED", reported=90_000_000, observed=91_000_000)
        changed = event(1, "CODEC", "MATCHED", reported=95_000_000, observed=98_000_000)
        result = parse_log(snapshot(500, 1, [first], final=False) + "\n" + snapshot(500, 2, [changed]))
        self.assertEqual(1, result["observed_event_identities"])
        self.assertEqual(1, result["conflicting_event_identities"])
        self.assertEqual(0, result["usable_events"])
        self.assertEqual(2, len(result["events"]))
        self.assertTrue(all(row["identity_conflict"] for row in result["events"]))
        self.assertEqual([], result["scopes"][0]["groups"])
        self.assertIn("conflicting_event_identity", {gap["code"] for gap in result["measurement_gaps"]})

    def test_missing_schema_final_meta_and_off_logs_stay_unmeasured(self):
        off = parse_log("I BluetoothVideo: decoder stats without the DEBUG observer\n")
        self.assertEqual("UNMEASURED", off["state"])
        self.assertEqual([], off["scopes"])
        self.assertEqual([], off["events"])
        for columns in (None, COLUMNS.replace("targetNs", "unknownNs")):
            with self.subTest(columns=columns):
                result = parse_log(snapshot(500, 1, [event(1)], final=False, columns=columns))
                self.assertEqual("UNMEASURED", result["state"])
                self.assertTrue(result["scopes"][0]["reports"][0]["raw_data"])
                self.assertIsNone(result["scopes"][0]["final_meta"])
                self.assertIn("missing_final_meta", {gap["code"] for gap in result["measurement_gaps"]})

    def test_invalid_numeric_input_fails_cli_with_line_and_field(self):
        with tempfile.TemporaryDirectory() as directory:
            log = Path(directory) / "bad.log"
            log.write_text(snapshot(500, 1, [event(1).replace("80000000", "bad", 1)]), encoding="utf-8")
            error = io.StringIO()
            with contextlib.redirect_stderr(error), self.assertRaises(SystemExit) as stopped:
                main(["--log", str(log), "--output", str(Path(directory) / "out.json")])
            self.assertEqual(2, stopped.exception.code)
            self.assertIn("line 3: releaseCallNs must be an integer", error.getvalue())
            self.assertNotIn("Traceback", error.getvalue())


if __name__ == "__main__":
    unittest.main()

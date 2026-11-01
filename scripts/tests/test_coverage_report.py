"""Coverage must count final display and keep a parser-independent denominator."""
import contextlib
import copy
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "coverage_report.py"
SPEC = importlib.util.spec_from_file_location("coverage_report", SCRIPT)
coverage = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(coverage)


def line(index, intent="TRANSLATE", outcome="TRANSLATED", source="a" * 64, channel="item"):
    return dict(line=index, sourceSha256=source, intent=intent, intentReason="reviewed fixture intent",
                parserRole="DESCRIPTION", channel=channel, outcome=outcome,
                stage="DISPLAY" if outcome == "TRANSLATED" else "CLASSIFICATION",
                reason="ACCEPTED" if outcome == "TRANSLATED" else "INSUFFICIENT_CONTEXT",
                matchMode="exact", japanese=outcome == "TRANSLATED",
                renderedSha256="b" * 64, renderedStyleSha256="c" * 64)


def report(lines=None, weight=1):
    if lines is None:
        lines = [line(0), line(1, outcome="UNCLASSIFIED", source="d" * 64),
                 line(2, intent="PRESERVE", outcome="KEEP_ORIGINAL", source="e" * 64)]
    return dict(schemaVersion=1, inputKind="REVIEWED_FIXTURE", inputSha256="f" * 64,
                dictionaryBuildId="fixture", apiCalls=0, skippedInputRecords=0,
                duplicateLogPayloads=0, scope="offline fixture", problems=[],
                cases=[dict(id="case-a", screen="SkyBlock Menu", weight=weight,
                            sourceLineCount=len(lines), plannedParagraphs=1,
                            appliedParagraphs=1, lines=lines, problems=[])])


def overall(document):
    return next(g for g in coverage.summarize(document)["groups"] if g["dimension"] == "all")


class CoverageReportTest(unittest.TestCase):
    def test_unclassified_targets_and_keep_results_do_not_inflate_translation_rate(self):
        data = report(weight=4)
        summary = overall(data)
        self.assertEqual((4, 8, .5), (summary["translatedTargets"], summary["translationTargets"], summary["translationRate"]))
        self.assertEqual(4, summary["intentionalPreservation"])
        self.assertEqual({"stage": "CLASSIFICATION", "reason": "INSUFFICIENT_CONTEXT", "sourceLines": 4}, summary["failureStages"][0])
        data["cases"][0]["lines"][0]["outcome"] = "KEEP_ORIGINAL"
        self.assertEqual(0, overall(data)["translatedTargets"])

    def test_paragraph_resegmentation_cannot_change_the_source_line_denominator(self):
        before = report()
        after = copy.deepcopy(before)
        after["cases"][0]["plannedParagraphs"] = 20
        after["cases"][0]["appliedParagraphs"] = 19
        self.assertEqual(overall(before), overall(after))

    def test_unique_text_reports_both_any_and_every_native_variant(self):
        data = report([line(0), line(1, outcome="NOT_DISPLAYED")], weight=3)
        summary = overall(data)
        self.assertEqual(6, summary["translationTargets"])
        self.assertEqual(1, summary["uniqueTargetTexts"])
        self.assertEqual(1, summary["uniqueTranslatedAnyVariant"])
        self.assertEqual(0, summary["uniqueTranslatedEveryVariant"])

    def test_baseline_rejects_lost_translation_and_style_changes(self):
        data = report()
        baseline = coverage.baseline(data)
        lost = copy.deepcopy(data)
        lost["cases"][0]["lines"][0]["outcome"] = "KEEP_ORIGINAL"
        self.assertTrue(any("stopped reaching display" in f for f in coverage.compare(lost, baseline)))
        changed = copy.deepcopy(data)
        changed["cases"][0]["lines"][0]["renderedStyleSha256"] = "0" * 64
        self.assertTrue(any("text/RGB/style" in f for f in coverage.compare(changed, baseline)))

    def test_baseline_rejects_denominator_weight_and_preserved_source_edits(self):
        data = report()
        baseline = coverage.baseline(data)
        for field, value, message in [("intent", "PRESERVE", "intent/range"),
                                      ("sourceSha256", "0" * 64, "intent/range")]:
            changed = copy.deepcopy(data)
            changed["cases"][0]["lines"][1][field] = value
            self.assertTrue(any(message in f for f in coverage.compare(changed, baseline)))
        changed = copy.deepcopy(data)
        changed["cases"][0]["weight"] = 2
        self.assertTrue(any("weight" in f for f in coverage.compare(changed, baseline)))
        changed = copy.deepcopy(data)
        changed["cases"][0]["lines"][2]["renderedSha256"] = "0" * 64
        self.assertTrue(any("preserved source" in f for f in coverage.compare(changed, baseline)))

    def test_newly_translated_failures_are_allowed_without_erasing_the_old_baseline(self):
        data = report()
        baseline = coverage.baseline(data)
        improved = copy.deepcopy(data)
        new = improved["cases"][0]["lines"][1]
        new.update(outcome="TRANSLATED", japanese=True, stage="DISPLAY", reason="ACCEPTED",
                   renderedSha256="0" * 64, renderedStyleSha256="1" * 64)
        self.assertEqual([], coverage.compare(improved, baseline))
        self.assertGreater(overall(improved)["translationRate"], overall(data)["translationRate"])

    def test_untranslated_targets_cannot_change_native_output_by_claiming_preservation(self):
        data = report()
        baseline = coverage.baseline(data)
        for outcome in ("KEEP_ORIGINAL", "ACCEPTED_UNCHANGED", "NOT_DISPLAYED", "PARSER_SKIPPED"):
            for changed_field in ("renderedSha256", "renderedStyleSha256"):
                changed = copy.deepcopy(data)
                target = changed["cases"][0]["lines"][1]
                self.assertEqual("TRANSLATE", target["intent"])
                target.update(outcome=outcome, stage="DISPLAY", reason="KEEP_ORIGINAL", japanese=False)
                target[changed_field] = "0" * 64
                self.assertTrue(any("without a new translation" in failure
                                    for failure in coverage.compare(changed, baseline)),
                                (outcome, changed_field))

    def test_capture_intent_remains_unreviewed_instead_of_inferred_from_the_parser(self):
        data = report([line(0, intent="UNREVIEWED")])
        data["inputKind"] = "UNREVIEWED_CAPTURE"
        summary = overall(data)
        self.assertIsNone(summary["translationRate"])
        self.assertEqual(1, summary["unreviewedIntent"])
        with self.assertRaises(ValueError):
            coverage.baseline(data)
        data["cases"][0]["lines"][0]["intent"] = "PRESERVE"
        with self.assertRaises(ValueError):
            coverage.summarize(data)

    def test_invalid_ranges_unsupported_input_and_api_calls_fail_closed(self):
        mutations = [lambda d: d.update(apiCalls=1), lambda d: d.update(schemaVersion=99),
                     lambda d: d.update(problems=["MISSING_OUTPUT_LINE"]),
                     lambda d: d["cases"].append(copy.deepcopy(d["cases"][0])),
                     lambda d: d["cases"][0]["lines"][1].update(line=0),
                     lambda d: d["cases"][0]["lines"][0].update(japanese=False),
                     lambda d: d["cases"][0]["lines"][0].update(renderedSha256="not-a-hash")]
        for mutate in mutations:
            data = report()
            mutate(data)
            with self.assertRaises(ValueError):
                coverage.validate(data)

    def test_changed_fixture_requires_explicit_baseline_review(self):
        data = report()
        baseline = coverage.baseline(data)
        data["inputSha256"] = "0" * 64
        self.assertEqual(["Fixed input corpus changed; review the corpus and baseline together"], coverage.compare(data, baseline))

    def test_command_writes_summary_and_gates_regressions(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source, baseline, summary = root / "input.json", root / "baseline.json", root / "summary.json"
            source.write_text(json.dumps(report()), encoding="utf-8")
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(0, coverage.main([str(source), "--output", str(summary), "--write-baseline", str(baseline)]))
                self.assertEqual(0, coverage.main([str(source), "--baseline", str(baseline)]))
            self.assertEqual(1, json.loads(summary.read_text(encoding="utf-8"))["caseCount"])
            failed = report()
            failed["cases"][0]["lines"][0]["outcome"] = "NOT_DISPLAYED"
            source.write_text(json.dumps(failed), encoding="utf-8")
            with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(1, coverage.main([str(source), "--baseline", str(baseline)]))


if __name__ == "__main__":
    unittest.main()

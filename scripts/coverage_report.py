#!/usr/bin/env python3
"""Summarize real Java display replay output; never reimplement dictionary matching.

Run ``gradlew dictionaryCoverage`` first. This tool only aggregates the resulting
hash-only JSON and optionally compares a reviewed fixed-cohort baseline.
"""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
import json
from pathlib import Path
import re
import sys

SCHEMA = 1
INTENTS = {"TRANSLATE", "PRESERVE", "UNREVIEWED"}
OUTCOMES = {"TRANSLATED", "KEEP_ORIGINAL", "ACCEPTED_UNCHANGED", "NOT_DISPLAYED",
            "BLANK", "UNCLASSIFIED", "PARSER_SKIPPED"}
HASH = re.compile(r"[0-9a-f]{64}\Z")


def load_report(path: Path) -> dict:
    document = json.loads(path.read_text(encoding="utf-8"))
    validate(document)
    return document


def validate(document: dict) -> None:
    if document.get("schemaVersion") != SCHEMA:
        raise ValueError("Unknown coverage report schema")
    if document.get("inputKind") not in {"REVIEWED_FIXTURE", "UNREVIEWED_CAPTURE"}:
        raise ValueError("Unknown coverage input kind")
    if not HASH.fullmatch(document.get("inputSha256", "")):
        raise ValueError("Missing input fingerprint")
    if document.get("apiCalls") != 0:
        raise ValueError("Coverage replay attempted an external API")
    if document.get("problems"):
        raise ValueError("Java replay invariants failed")
    cases = document.get("cases")
    if not isinstance(cases, list) or not cases:
        raise ValueError("Empty coverage report")
    ids = set()
    for case in cases:
        if case.get("id") in ids:
            raise ValueError("Duplicate case id")
        ids.add(case.get("id"))
        if not isinstance(case.get("weight"), int) or not 1 <= case["weight"] <= 1_000_000:
            raise ValueError("Invalid observation weight")
        if case.get("problems"):
            raise ValueError("Case replay invariants failed")
        lines = case.get("lines", [])
        if len(lines) != case.get("sourceLineCount"):
            raise ValueError("Source line count mismatch")
        for index, line in enumerate(lines):
            if line.get("line") != index:
                raise ValueError("Missing, duplicate or reordered source range")
            if line.get("intent") not in INTENTS or line.get("outcome") not in OUTCOMES:
                raise ValueError("Unknown line classification")
            for key in ("sourceSha256", "renderedSha256", "renderedStyleSha256"):
                if not HASH.fullmatch(line.get(key, "")):
                    raise ValueError("Invalid line fingerprint")
            if line["outcome"] == "TRANSLATED" and not line.get("japanese"):
                raise ValueError("Non-Japanese output counted as a translation")
            if not all(isinstance(line.get(k), str) for k in ("stage", "reason", "matchMode", "channel")):
                raise ValueError("Missing Java pipeline diagnosis")
            if document["inputKind"] == "UNREVIEWED_CAPTURE" and line["intent"] != "UNREVIEWED":
                raise ValueError("Captured intent cannot be inferred by the parser")


def summarize(document: dict) -> dict:
    validate(document)
    buckets = defaultdict(list)
    for case in document["cases"]:
        for line in case["lines"]:
            item = (line, case["weight"])
            buckets[("all", "all")].append(item)
            buckets[("channel", line["channel"])].append(item)
            buckets[("screen", case["screen"])].append(item)
    groups = []
    for (dimension, name), entries in sorted(buckets.items()):
        intent = Counter()
        outcome = Counter()
        failures = Counter()
        pipeline = Counter()
        matches = Counter()
        targets = defaultdict(set)
        for line, weight in entries:
            intent[line["intent"]] += weight
            outcome[line["outcome"]] += weight
            pipeline[(line["outcome"], line["stage"], line["reason"])] += weight
            if line["intent"] == "TRANSLATE":
                targets[line["sourceSha256"]].add(line["outcome"])
                if line["outcome"] != "TRANSLATED":
                    failures[(line["stage"], line["reason"])] += weight
            if line["outcome"] == "TRANSLATED":
                matches[line["matchMode"]] += weight
        translated = sum(weight for line, weight in entries
                         if line["intent"] == "TRANSLATE" and line["outcome"] == "TRANSLATED")
        denominator = intent["TRANSLATE"]
        groups.append({
            "dimension": dimension, "name": name,
            "sourceLines": sum(weight for _, weight in entries),
            "translationTargets": denominator,
            "translatedTargets": translated,
            "translationRate": translated / denominator if denominator else None,
            "dictionaryTranslatedSourceLines": outcome["TRANSLATED"],
            "dictionaryKeptSourceLines": outcome["KEEP_ORIGINAL"],
            "japaneseDisplaySourceLines": sum(weight for line, weight in entries if line["japanese"]),
            "unclassifiedSourceLines": outcome["UNCLASSIFIED"],
            "parserSkippedSourceLines": outcome["PARSER_SKIPPED"],
            "intentionalPreservation": intent["PRESERVE"],
            "unreviewedIntent": intent["UNREVIEWED"],
            "intentCounts": dict(sorted(intent.items())),
            "outcomeCounts": dict(sorted(outcome.items())),
            "translatedMatchModes": dict(sorted(matches.items())),
            "uniqueTargetTexts": len(targets),
            "uniqueTranslatedEveryVariant": sum(s == {"TRANSLATED"} for s in targets.values()),
            "uniqueTranslatedAnyVariant": sum("TRANSLATED" in s for s in targets.values()),
            "failureStages": [{"stage": stage, "reason": reason, "sourceLines": count}
                              for (stage, reason), count in sorted(failures.items(), key=lambda x: (-x[1], x[0]))],
            "pipelineStages": [{"outcome": result, "stage": stage, "reason": reason, "sourceLines": count}
                               for (result, stage, reason), count in sorted(pipeline.items(), key=lambda x: (-x[1], x[0]))],
        })
    return {
        "schemaVersion": SCHEMA,
        "inputKind": document["inputKind"], "inputSha256": document["inputSha256"],
        "dictionaryBuildId": document.get("dictionaryBuildId"),
        "scope": document.get("scope"),
        "caseCount": len(document["cases"]),
        "observationWeight": sum(c["weight"] for c in document["cases"]),
        "skippedInputRecords": document.get("skippedInputRecords", 0),
        "duplicateLogPayloads": document.get("duplicateLogPayloads", 0),
        "groups": groups,
        "limits": [
            "The fixed reviewed translation intent is the denominator, never the parser's chosen paragraphs.",
            "Frequency is stored display observations/authored fixture weight, not render frames or play time.",
            "Unreviewed capture rows have no target-translation rate; they are not silently excluded as names.",
            "This measures local tooltip callback output, not viewport wrapping, later mods, all screens or the whole game.",
            "Repeated source lines remain in the weighted rate; unique-text any/every-variant counts are reported separately.",
        ],
    }


def baseline(document: dict) -> dict:
    validate(document)
    if document["inputKind"] != "REVIEWED_FIXTURE":
        raise ValueError("Only a reviewed fixed fixture can become a regression baseline")
    return {
        "schemaVersion": SCHEMA, "inputSha256": document["inputSha256"],
        "policy": "Keep the source cohort and intent fixed; protect every already translated native result. New translations are allowed.",
        "cases": [{"id": case["id"], "weight": case["weight"], "lines": [
            {key: line[key] for key in ("line", "sourceSha256", "intent", "outcome", "renderedSha256", "renderedStyleSha256")}
            for line in case["lines"]]} for case in document["cases"]],
    }


def compare(document: dict, reference: dict) -> list[str]:
    validate(document)
    failures = []
    if reference.get("schemaVersion") != SCHEMA:
        return ["Unknown baseline schema"]
    if document["inputKind"] != "REVIEWED_FIXTURE" or document["inputSha256"] != reference.get("inputSha256"):
        return ["Fixed input corpus changed; review the corpus and baseline together"]
    current = {case["id"]: case for case in document["cases"]}
    expected = {case["id"]: case for case in reference.get("cases", [])}
    if set(current) != set(expected):
        failures.append("Fixed case cohort changed")
    for case_id, before in expected.items():
        after = current.get(case_id)
        if not after:
            continue
        if before["weight"] != after["weight"]:
            failures.append(f"{case_id}: observation weight changed")
        if len(before["lines"]) != len(after["lines"]):
            failures.append(f"{case_id}: source range count changed")
            continue
        for old, new in zip(before["lines"], after["lines"]):
            ref = f"{case_id}:{old['line']}"
            if any(old[k] != new[k] for k in ("line", "sourceSha256", "intent")):
                failures.append(f"{ref}: fixed source intent/range changed")
            if old["outcome"] == "TRANSLATED":
                if new["outcome"] != "TRANSLATED":
                    failures.append(f"{ref}: translated target stopped reaching display ({new['stage']}/{new['reason']})")
                elif any(old[k] != new[k] for k in ("renderedSha256", "renderedStyleSha256")):
                    failures.append(f"{ref}: translated text/RGB/style changed; explicit review required")
            # A still-untranslated target must retain its old native display too. Changing
            # it to KEEP_ORIGINAL/ACCEPTED_UNCHANGED is not permission to alter text or RGB.
            if old["outcome"] != "TRANSLATED" and (old["intent"] == "PRESERVE" or new["outcome"] != "TRANSLATED"):
                if any(old[k] != new[k] for k in ("renderedSha256", "renderedStyleSha256")):
                    failures.append(f"{ref}: preserved source text/RGB/style changed without a new translation")
    return failures


def render_table(summary: dict) -> str:
    rows = ["dimension\tname\ttranslated/targets\trate\tapplied-lines\tdictionary-kept\tkeep-intent\tunreviewed\tunique(all/any/total)"]
    for group in summary["groups"]:
        rate = "n/a" if group["translationRate"] is None else f"{group['translationRate']:.1%}"
        rows.append("\t".join(map(str, [group["dimension"], group["name"],
                    f"{group['translatedTargets']}/{group['translationTargets']}", rate,
                    group["dictionaryTranslatedSourceLines"], group["dictionaryKeptSourceLines"],
                    group["intentionalPreservation"], group["unreviewedIntent"],
                    f"{group['uniqueTranslatedEveryVariant']}/{group['uniqueTranslatedAnyVariant']}/{group['uniqueTargetTexts']}"])))
    return "\n".join(rows)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path, help="Hash-only JSON written by the Java display replay")
    parser.add_argument("--output", type=Path, help="Write machine-readable aggregated JSON")
    parser.add_argument("--baseline", type=Path, help="Fail on fixed-cohort translation/native-style regressions")
    parser.add_argument("--write-baseline", type=Path, help="Explicitly write a new baseline for human diff review")
    args = parser.parse_args(argv)
    try:
        document = load_report(args.input)
        summary = summarize(document)
        if args.output:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        if args.write_baseline:
            args.write_baseline.parent.mkdir(parents=True, exist_ok=True)
            args.write_baseline.write_text(json.dumps(baseline(document), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(render_table(summary))
        if args.baseline:
            failures = compare(document, json.loads(args.baseline.read_text(encoding="utf-8")))
            if failures:
                for failure in failures:
                    print("REGRESSION: " + failure, file=sys.stderr)
                return 1
            print("Fixed-cohort baseline: all existing translations and native styles preserved")
        return 0
    except (OSError, ValueError, KeyError, TypeError) as error:
        print(f"Coverage report error: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())

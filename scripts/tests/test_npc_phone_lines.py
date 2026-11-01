"""Phone aliases require both explicit Wiki eligibility and an unchanged adopted bare pair."""
import copy
import importlib.util
import json
from pathlib import Path
import sys
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "compile_dictionary.py"
SPEC = importlib.util.spec_from_file_location("npc_phone_compiler", SCRIPT)
compiler = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = compiler
SPEC.loader.exec_module(compiler)


class NpcPhoneLineTests(unittest.TestCase):
    def document(self):
        return json.loads(SCRIPT.with_name("dictionary-npc-phone-lines.json").read_text("utf8"))

    def seeded(self, document, candidate=True):
        current = compiler.Compiler(candidate=candidate)
        for row in document["entries"]:
            for source_id in row["bare_source_ids"]:
                current.propose(compiler.Unit("npc", row["speaker"], row["bare_en"], row["bare_ja"], False, source_id))
        return current

    def test_all_eighteen_fixed_phone_lines_are_exact_scoped_derivations(self):
        document = self.document()
        self.assertEqual(18, len(document["entries"]))
        current = self.seeded(document)
        current.add_npc_phone_lines(document)
        dictionary, report = current.finish("fixture")
        emitted = {row["source"]: row for row in dictionary["entries"]}
        self.assertEqual(18, report["npc_phone_line_count"])
        for row in document["entries"]:
            self.assertEqual("✆ " + row["bare_en"], row["en"])
            self.assertEqual("✆ " + row["bare_ja"], row["ja"])
            self.assertEqual("npc", emitted[row["en"]]["channel"])
            self.assertEqual(row["speaker"], emitted[row["en"]]["speaker"])
            self.assertEqual([row["id"]], emitted[row["en"]]["source_ids"])
            self.assertEqual(row["bare_ja"], emitted[row["bare_en"]]["target"])

    def test_candidate_off_and_unapproved_decisions_do_not_add_aliases(self):
        document = self.document()
        current = self.seeded(document, candidate=False)
        current.add_npc_phone_lines(document)
        self.assertEqual(0, current.finish("fixture")[1]["npc_phone_line_count"])
        changed = copy.deepcopy(document)
        changed["entries"][0]["approved"] = False
        current = self.seeded(document); current.add_npc_phone_lines(changed)
        self.assertEqual(17, current.finish("fixture")[1]["npc_phone_line_count"])

    def test_stale_context_body_speaker_and_native_proof_fail_closed(self):
        original = self.document()
        for mutate in (
            lambda row: row.update(channel="ui"),
            lambda row: row.update(speaker="Another NPC"),
            lambda row: row.update(en=row["en"] + " extra"),
            lambda row: row.update(ja="別の訳"),
            lambda row: row.update(bare_source_ids=["unknown/source"]),
            lambda row: row["evidence"]["wiki_group"].update(conditions=["Random"]),
            lambda row: row["evidence"]["wiki_group"].update(extracted_line=999),
            lambda row: row["evidence"]["wiki_group"].update(extracted_text="[NPC] Bob: Wrong body."),
            lambda row: row["evidence"]["native_prefix"].update(source="Wrong protocol prefix"),
            lambda row: row["evidence"]["native_prefix"].update(speaker="Bob"),
            lambda row: row["evidence"]["native_prefix"].update(source_styled_complete=False),
        ):
            changed = copy.deepcopy(original); mutate(changed["entries"][0])
            with self.subTest(change=changed["entries"][0]):
                with self.assertRaises(ValueError):
                    self.seeded(original).add_npc_phone_lines(changed)
        changed = copy.deepcopy(original)
        group = changed["entries"][0]["evidence"]["wiki_group"]
        group["conditions"] = ["Random"]
        group["conditions_sha256"] = compiler.sha256(compiler.json_bytes(group["conditions"]))
        group["group_sha256"] = compiler.sha256(compiler.json_bytes({key: group[key] for key in ("source", "conditions", "extracted_text")}))
        with self.assertRaisesRegex(ValueError, "not explicitly phone eligible"):
            self.seeded(original).add_npc_phone_lines(changed)

    def test_changed_or_conflicting_bare_and_phone_specific_pairs_are_not_overridden(self):
        document = self.document(); row = document["entries"][0]
        current = self.seeded(document)
        current.propose(compiler.Unit("npc", row["speaker"], row["bare_en"], "別の本文訳。", False, "competing/bare"))
        with self.assertRaisesRegex(ValueError, "bare pair changed, ambiguous"):
            current.add_npc_phone_lines(document)
        current = self.seeded(document)
        current.propose(compiler.Unit("npc", row["speaker"], row["en"], "✆ 電話専用の別訳。", False, "competing/phone"))
        with self.assertRaisesRegex(ValueError, "phone specific translation already differs"):
            current.add_npc_phone_lines(document)

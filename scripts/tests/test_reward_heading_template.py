"""Contracts for the narrowly reviewed Roman skill reward heading family."""
import importlib.util
import json
from pathlib import Path
import sys
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "compile_dictionary.py"
SPEC = importlib.util.spec_from_file_location("reward_heading_compiler", SCRIPT)
compiler = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = compiler
SPEC.loader.exec_module(compiler)


class RewardHeadingTemplateTests(unittest.TestCase):
    def test_only_candidate_mode_emits_three_explicit_ui_families(self):
        _, candidate = compiler.Compiler(candidate=True).finish("fixture")
        _, verified = compiler.Compiler(candidate=False).finish("fixture")
        self.assertEqual(3, candidate["reviewed_ui_template_count"])
        self.assertEqual(0, verified["reviewed_ui_template_count"])
        templates = candidate["matching_document"]["templates"]
        reward = next(row for row in templates if row["source"] == "Level {v0} Rewards:")
        self.assertEqual({"channel": "ui", "speaker": "", "source": "Level {v0} Rewards:",
                          "target": "レベル{v0}の報酬:", "variables": [{"name": "v0", "type": "roman"}],
                          "source_ids": ["reviewed/ui/level_rewards"]}, reward)
        self.assertEqual({"Selected pet: {v0}", "Progress to Level {v0}: {v1}%", "Level {v0} Rewards:"},
                         {row["source"] for row in templates})
        self.assertEqual([], verified["matching_document"]["templates"])

    def test_existing_level_thirty_seven_literal_target_and_id_remain_unchanged(self):
        supplement = json.loads(SCRIPT.with_name("dictionary-observed-text.json").read_text("utf8"))
        literal = next(row for row in supplement["entries"] if row["en"] == "Level XXXVII Rewards:")
        self.assertEqual("observed/ui/runtime079_a9cf9140d89d72648c", literal["id"])
        self.assertEqual("レベルXXXVIIの報酬:", literal["ja"])
        current = compiler.Compiler(candidate=True)
        current.add_observed_text({"schema_version": 1, "entries": [literal]})
        dictionary, report = current.finish("fixture")
        emitted = next(row for row in dictionary["entries"] if row["source"] == literal["en"])
        self.assertEqual(literal["ja"], emitted["target"])
        self.assertEqual([literal["id"]], emitted["source_ids"])
        self.assertEqual(3, report["reviewed_ui_template_count"])

"""Hash-bound, native-context sharing is separate from literal scope emission."""
import copy
import importlib.util
import json
from pathlib import Path
import sys
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'compile_dictionary.py'
SPEC = importlib.util.spec_from_file_location('compile_dictionary_applicability', SCRIPT)
compiler = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = compiler
SPEC.loader.exec_module(compiler)


class ObservedApplicabilityTests(unittest.TestCase):
    def documents(self):
        policy = json.loads(SCRIPT.with_name('dictionary-observed-applicability.json').read_text(encoding='utf-8'))
        observed = json.loads(SCRIPT.with_name('dictionary-observed-text.json').read_text(encoding='utf-8'))
        ids = {row['origin_source_id'] for row in policy['entries']}
        observed['entries'] = [row for row in observed['entries'] if row['id'] in ids]
        self.assertEqual(4, len(policy['entries']))
        self.assertEqual(4, len(observed['entries']))
        return observed, policy

    def make(self, observed=None, candidate=True):
        c = compiler.Compiler(candidate=candidate)
        c.add_observed_text(observed or self.documents()[0])
        return c

    def test_four_native_reviews_emit_guarded_resource_without_opening_literal_ui_scope(self):
        observed, policy = self.documents()
        c = self.make(observed); c.add_observed_applicability(policy)
        dictionary, report = c.finish('fixture')
        self.assertEqual({'item'}, {row['channel'] for row in dictionary['entries']})
        doc = report['scoped_descriptions_document']
        self.assertEqual(dictionary['build_id'], doc['build_id'])
        self.assertEqual(4, len(doc['rules']))
        self.assertEqual([], report['scoped_description_rejections'])
        self.assertEqual({'ui'}, {row['to_channel'] for row in doc['rules']})
        for row in doc['rules']:
            self.assertEqual({'tooltipTitle', 'tooltipKind', 'paragraphRole', 'location', 'slotMatches'}, set(row['requires']))
            self.assertEqual('UI', row['requires']['tooltipKind'])
            self.assertEqual(compiler.sha256(row['source'].encode()), row['source_sha256'])
            self.assertEqual(compiler.sha256(row['target'].encode()), row['target_sha256'])

    def test_observed_description_role_alone_does_not_share_any_literal(self):
        c = self.make(); dictionary, report = c.finish('fixture')
        self.assertEqual({'item'}, {row['channel'] for row in dictionary['entries']})
        self.assertEqual([], report['scoped_descriptions_document']['rules'])
        c = self.make(candidate=False); c.add_observed_applicability(self.documents()[1])
        self.assertEqual([], c.finish('fixture')[1]['scoped_descriptions_document']['rules'])

    def test_changed_source_target_scope_role_or_runtime_context_requires_review(self):
        for mutate in (
            lambda r: r.update(source_sha256='0' * 64),
            lambda r: r.update(target_sha256='0' * 64),
            lambda r: r.update(from_channel='npc'),
            lambda r: r.update(to_channel='system'),
            lambda r: r.update(semantic_role='UI_TITLE'),
            lambda r: r['requires'].update(slotMatches=False),
            lambda r: r['requires'].update(tooltipKind='ITEM'),
            lambda r: r['requires'].pop('paragraphRole'),
            lambda r: r['requires'].update(tooltipTitle='Unrelated control'),
            lambda r: r.update(native_evidence_sha256='0' * 64),
        ):
            observed, policy = self.documents(); mutate(policy['entries'][0])
            with self.assertRaises(ValueError): self.make(observed).add_observed_applicability(policy)

    def test_rehashed_but_incomplete_native_capture_still_fails(self):
        for mutate in (
            lambda n: n.update(source_styled_complete=False),
            lambda n: n['source_styled_lines'][0][0].update(text='Unrelated text'),
            lambda n: n['context'].update(slotMatches=False),
            lambda n: n.update(source_lines=['A truncated body.']),
        ):
            observed, policy = self.documents(); row = policy['entries'][0]
            mutate(row['native_evidence'])
            row['native_evidence_sha256'] = compiler.sha256(compiler.json_bytes(row['native_evidence']))
            with self.assertRaises(ValueError): self.make(observed).add_observed_applicability(policy)

    def test_duplicate_context_is_rejected_and_late_translation_conflicts_stay_quarantined(self):
        observed, policy = self.documents(); duplicate = copy.deepcopy(policy['entries'][0]); duplicate['id'] += '-duplicate'
        policy['entries'].append(duplicate)
        with self.assertRaises(ValueError): self.make(observed).add_observed_applicability(policy)
        observed, policy = self.documents(); c = self.make(observed); c.add_observed_applicability(policy)
        source = observed['entries'][0]
        c.add({'id': 'fixture/conflict', 'kind': 'item', 'role': 'description', 'en': source['en'], 'ja': '別の意味の説明。',
               'status': 'draft', 'note': '', 'tokens': [], 'runtime_eligible': False, 'source_review': {'game_validated': False}})
        _, report = c.finish('fixture')
        self.assertEqual(3, len(report['scoped_descriptions_document']['rules']))
        self.assertTrue(report['scoped_description_rejections'])


if __name__ == '__main__': unittest.main()

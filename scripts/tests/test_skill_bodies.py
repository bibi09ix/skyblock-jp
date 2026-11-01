"""Exact reviewed skill effects must not broaden common or erase labels."""
import copy
import json
import unittest
from pathlib import Path

from test_compile_dictionary import compiler


DATA = Path(__file__).resolve().parents[1] / 'dictionary-skill-bodies.json'
NAMES = ['Farming Fortune', 'Foraging Fortune', 'Mining Fortune',
         'Treasure Chance', 'Charm Chance']


def document():
    return json.loads(DATA.read_text(encoding='utf8'))


def original(row, **changes):
    result = {'id': row['source_id'], 'kind': 'common', 'role': 'skill_unlock',
              'en': row['evidence']['full_en'], 'ja': row['evidence']['full_ja'],
              'status': 'draft', 'runtime_eligible': True,
              'source_review': {'game_validated': True}}
    result.update(changes)
    return result


def seeded(rows, candidate=True):
    result = compiler.Compiler(candidate=candidate, names=NAMES)
    for row in rows:
        result.add(original(row))
    return result


def body_target(row, target):
    """Update every declared target hash for semantic-fidelity negative tests."""
    row['ja'] = target
    row['target_sha256'] = compiler.sha256(target.encode('utf8'))
    row['evidence']['full_ja'] = row['label'] + '\n' + target
    row['evidence']['full_ja_sha256'] = compiler.sha256(row['evidence']['full_ja'].encode('utf8'))


class SkillBodyTests(unittest.TestCase):
    def test_all_507_real_reviewed_effects_compile_only_as_exact_ui_bodies(self):
        doc = document()
        self.assertEqual(507, len(doc['entries']))
        c = seeded(doc['entries'])
        c.add_skill_bodies(doc)
        dictionary, report = c.finish('real-skill-fixture')
        self.assertEqual(507, report['skill_body_entry_count'])
        self.assertEqual(0, report['template_entry_count'])
        ui = {(r['source'], r['target']) for r in dictionary['entries'] if r['channel'] == 'ui'}
        common = [r for r in dictionary['entries'] if r['channel'] == 'common']
        self.assertEqual({(r['en'], r['ja']) for r in doc['entries']}, ui)
        self.assertEqual(507, len(common))
        self.assertEqual({'ui', 'common'}, {r['channel'] for r in dictionary['entries']})
        self.assertFalse(any(r['source'].startswith('Farmhand ') for r in dictionary['entries'] if r['channel'] == 'ui'))
        # Body-only derivation leaves every existing combined source unchanged.
        self.assertEqual({compiler.normalize(r['evidence']['full_en']) for r in doc['entries']},
                         {r['source'] for r in common})

    def test_real_review_has_all_nine_skills_and_preserves_every_value_and_icon(self):
        rows = document()['entries']
        self.assertEqual({'ALCHEMY', 'COMBAT', 'ENCHANTING', 'FARMING', 'FISHING',
                          'FORAGING', 'HUNTING', 'MINING', 'TAMING'},
                         {r['evidence']['context']['skill'] for r in rows})
        self.assertEqual(18, len({(r['evidence']['context']['skill'],
                                 compiler.NUMBER.sub('#', r['en'])) for r in rows}))
        for row in rows:
            with self.subTest(id=row['id']):
                self.assertTrue(row['approved'])
                self.assertTrue(compiler.quantities_preserved(row['en'], row['ja']))
                self.assertEqual(compiler.glyph_signature(row['en']), compiler.glyph_signature(row['ja']))
                self.assertEqual(row['evidence']['full_en'], row['label'] + '   ' + row['en'])
                self.assertEqual(row['evidence']['full_ja'], row['label'] + '\n' + row['ja'])
        farming = next(r for r in rows if r['label'] == 'Farmhand XXXVII')
        self.assertIn('+144➜148', farming['en'])
        self.assertIn('+144➜148', farming['ja'])
        self.assertIn('Farming Fortune', farming['ja'])

    def test_missing_original_or_changed_source_id_is_rejected(self):
        row = document()['entries'][0]
        with self.assertRaises(ValueError):
            compiler.Compiler(candidate=True).add_skill_bodies({'schema_version': 1, 'entries': [row]})
        changed = copy.deepcopy(row)
        changed['source_id'] = 'common/skill_unlock/no-longer-present'
        changed['evidence']['source_id'] = changed['source_id']
        with self.assertRaises(ValueError):
            seeded([row]).add_skill_bodies({'schema_version': 1, 'entries': [changed]})

    def test_changed_full_pair_and_conflicting_original_translation_are_rejected(self):
        row = document()['entries'][0]
        changed = copy.deepcopy(row)
        body_target(changed, row['ja'] + '確認済み。')
        with self.assertRaises(ValueError):
            seeded([row]).add_skill_bodies({'schema_version': 1, 'entries': [changed]})
        c = seeded([row])
        c.add(original(row, id=row['source_id'] + '/other', ja=row['evidence']['full_ja'] + '別訳。'))
        with self.assertRaises(ValueError):
            c.add_skill_bodies({'schema_version': 1, 'entries': [row]})

    def test_stale_hashes_labels_boundaries_and_metadata_fail_closed(self):
        base = document()['entries'][0]
        mutations = [
            lambda r: r.update(source_sha256='0' * 64),
            lambda r: r.update(target_sha256='0' * 64),
            lambda r: r['evidence'].update(full_en_sha256='0' * 64),
            lambda r: r['evidence'].update(full_ja_sha256='0' * 64),
            lambda r: r['evidence'].update(source_separator=' '),
            lambda r: r['evidence'].update(target_separator=' '),
            lambda r: r.update(label='Brewer 1'),
            lambda r: r['evidence'].update(label='Another I'),
            lambda r: r['evidence'].update(full_en='Brewer I ' + r['en']),
            lambda r: r['evidence'].update(full_ja='Brewer I ' + r['ja']),
            lambda r: r['evidence'].update(source_id='unknown'),
            lambda r: r.update(approved='yes'),
            lambda r: r.update(reason=''),
        ]
        for mutate in mutations:
            with self.subTest(mutation=mutate):
                changed = copy.deepcopy(base)
                mutate(changed)
                with self.assertRaises(ValueError):
                    seeded([base]).add_skill_bodies({'schema_version': 1, 'entries': [changed]})

    def test_item_npc_common_and_non_description_scopes_are_rejected(self):
        base = document()['entries'][0]
        for field, value in [('channel', 'item'), ('channel', 'npc'), ('channel', 'common'),
                             ('role', 'UI_TITLE'), ('kind', 'item')]:
            with self.subTest(field=field, value=value):
                changed = copy.deepcopy(base)
                changed[field] = value
                with self.assertRaises(ValueError):
                    seeded([base]).add_skill_bodies({'schema_version': 1, 'entries': [changed]})

    def test_candidate_off_and_review_off_do_not_publish_derived_bodies(self):
        row = document()['entries'][0]
        for candidate, approved in [(False, True), (True, False)]:
            with self.subTest(candidate=candidate, approved=approved):
                changed = copy.deepcopy(row)
                changed['approved'] = approved
                c = seeded([row], candidate=candidate)
                c.add_skill_bodies({'schema_version': 1, 'entries': [changed]})
                dictionary, report = c.finish('fixture')
                self.assertEqual(0, report['skill_body_entry_count'])
                self.assertEqual({'common'}, {r['channel'] for r in dictionary['entries']})

    def test_changed_numbers_icons_or_names_cannot_be_authorized_by_fresh_hashes(self):
        farming = next(r for r in document()['entries'] if r['label'] == 'Farmhand XXXVII')
        for target in (farming['ja'].replace('148', '149'), farming['ja'].replace('➜', ''),
                       farming['ja'].replace('\ue051', ''), farming['ja'].replace('Farming Fortune', '収穫運')):
            with self.subTest(target=target):
                changed = copy.deepcopy(farming)
                body_target(changed, target)
                with self.assertRaises(ValueError):
                    seeded([changed]).add_skill_bodies({'schema_version': 1, 'entries': [changed]})

    def test_duplicate_ids_are_rejected_and_existing_ui_conflicts_stay_quarantined(self):
        row = document()['entries'][0]
        with self.assertRaises(ValueError):
            seeded([row]).add_skill_bodies({'schema_version': 1, 'entries': [row, copy.deepcopy(row)]})
        c = seeded([row])
        c.add({'id': 'ui/slot_description/conflict', 'kind': 'ui', 'role': 'slot_description',
               'en': row['en'], 'ja': row['ja'] + '別の説明。', 'status': 'draft'})
        c.add_skill_bodies({'schema_version': 1, 'entries': [row]})
        dictionary, report = c.finish('fixture')
        self.assertFalse(any(r['channel'] == 'ui' and r['source'] == row['en'] for r in dictionary['entries']))
        self.assertTrue(any(r['channel'] == 'ui' and r['source'] == row['en'] for r in dictionary['blocked_keys']))


if __name__ == '__main__':
    unittest.main()

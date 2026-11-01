"""One reviewed damage slot must not make Fire Veil's duration or wording variable."""
import copy
import unittest
from test_compile_dictionary import compiler, entry


def document(values=('101,479.2', '102,015.7', '105,582.3')):
    source, target = compiler.FIRE_VEIL_FAMILY_EN, compiler.FIRE_VEIL_FAMILY_JA
    pairs = []
    for index, value in enumerate(values):
        en, ja = source.replace('{v0}', value), target.replace('{v0}', value)
        pairs.append({'value': value, 'source': en, 'target': ja,
                      'source_sha256': compiler.sha256(en.encode()), 'target_sha256': compiler.sha256(ja.encode()),
                      'source_ids': [f'item/fire_veil_fixture/{index}']})
    observed = source.replace('{v0}', '43,716.4')
    lines = ['Creates a veil of fire around you for', '5s, dealing 43,716.4 damage per', 'second to mobs within.']
    sample = {'value': '43,716.4', 'source': observed, 'source_sha256': compiler.sha256(observed.encode()),
              'source_lines': lines, 'source_lines_sha256': compiler.sha256(compiler.json_bytes(lines)),
              'channel': 'item', 'role': 'DESCRIPTION', 'event_sha256': 'a' * 64,
              'evidence_kind': 'recorded_tooltip', 'native_styles': False, 'source_complete': True,
              'snapshot_file': 'saved/tooltip-replay-input.json', 'snapshot_sha256': 'b' * 64,
              'record_pointer': '/670', 'line_start': 10, 'line_end': 12}
    return {'schema_version': 1, 'entries': [{'id': 'reviewed/template/fire_veil_damage_per_second',
        'kind': 'fire_veil_damage_per_second', 'en': source, 'ja': target,
        'source_sha256': compiler.sha256(source.encode()), 'channels': ['item', 'ui'],
        'variables': [{'name': 'v0', 'type': 'number'}], 'source_pairs': pairs, 'native_example': sample,
        'approved': True, 'reason': '原文・訳文の固定5秒と毎秒damageの位置を3つの採用済み組で確認。保存済みの別数値を根拠に補間する。'}]}


def prepared(doc, candidate=True):
    c = compiler.Compiler(candidate=candidate)
    for sample in doc['entries'][0]['source_pairs']:
        for source_id in sample['source_ids']:
            c.add(entry(sample['source'], sample['target'], id=source_id, role='ability_body'))
    return c


class FireVeilNumericFamilyTests(unittest.TestCase):
    def test_exact_shape_creates_only_item_ui_templates_from_unchanged_pairs(self):
        doc = document(); c = prepared(doc); c.add_reviewed_numeric_families(doc)
        _, report = c.finish('fixture')
        self.assertEqual(1, report['reviewed_numeric_family_count'])
        templates = [r for r in report['matching_document']['templates'] if r['source'] == compiler.FIRE_VEIL_FAMILY_EN]
        self.assertEqual({'item', 'ui'}, {r['channel'] for r in templates})
        self.assertEqual(2, len(templates))
        self.assertTrue(all(r['target'] == compiler.FIRE_VEIL_FAMILY_JA for r in templates))
        self.assertTrue(all(r['variables'] == [{'name': 'v0', 'type': 'number'}] for r in templates))

    def test_fixed_words_duration_and_units_cannot_change_even_after_rehashing(self):
        mutations = [lambda r: r.update(en=r['en'].replace('5s', '6s')),
                     lambda r: r.update(en=r['en'].replace('5s', '5m')),
                     lambda r: r.update(en=r['en'].replace('second', 'minute')),
                     lambda r: r.update(en=r['en'].replace('mobs', 'players')),
                     lambda r: r.update(ja=r['ja'].replace('5秒間', '6秒間')),
                     lambda r: r.update(ja=r['ja'].replace('毎秒', '毎分')),
                     lambda r: r.update(ja=r['ja'] + '追加効果。')]
        for mutate in mutations:
            doc = document(); value = doc['entries'][0]; mutate(value)
            value['source_sha256'] = compiler.sha256(value['en'].encode())
            with self.assertRaisesRegex(ValueError, 'shape'):
                prepared(doc).add_reviewed_numeric_families(doc)

    def test_damage_evidence_is_unsigned_decimal_with_valid_comma_grouping(self):
        for bad in ('-1', '+1', '1k', '1e5', '10%', '1,00', '01,479.2', '1,479,2', '0.1234567', '.5', ' 20'):
            with self.subTest(value=bad):
                doc = document(); doc['entries'][0]['source_pairs'][0]['value'] = bad
                with self.assertRaisesRegex(ValueError, 'sample value'):
                    prepared(doc).add_reviewed_numeric_families(doc)
                doc = document(); doc['entries'][0]['native_example']['value'] = bad
                with self.assertRaisesRegex(ValueError, 'native numeric family value'):
                    prepared(doc).add_reviewed_numeric_families(doc)

    def test_three_format_variants_of_one_damage_are_not_three_distinct_pairs(self):
        doc = document(('101,479.2', '101479.2', '101479.20'))
        with self.assertRaisesRegex(ValueError, 'Duplicate numeric family evidence'):
            prepared(doc).add_reviewed_numeric_families(doc)

    def test_three_sources_hashes_ids_and_unambiguous_adopted_targets_remain_required(self):
        mutations = [lambda r: r['source_pairs'].pop(),
                     lambda r: r['source_pairs'][0].update(source_sha256='0' * 64),
                     lambda r: r['source_pairs'][0].update(target_sha256='0' * 64),
                     lambda r: r['source_pairs'][0].update(source_ids=['unknown/source']),
                     lambda r: r.update(channels=['npc']),
                     lambda r: r.update(source_sha256='0' * 64)]
        for mutate in mutations:
            original = document(); c = prepared(original); changed = copy.deepcopy(original); mutate(changed['entries'][0])
            with self.assertRaises(ValueError):
                c.add_reviewed_numeric_families(changed)
        doc = document(); c = prepared(doc); pair = doc['entries'][0]['source_pairs'][0]
        c.add(entry(pair['source'], pair['target'] + '追加効果。', id='competing/translation', role='ability_body'))
        with self.assertRaisesRegex(ValueError, 'no longer matches source packs'):
            c.add_reviewed_numeric_families(doc)

    def test_historical_capture_must_explicitly_disclaim_native_styles(self):
        for field, value in [('evidence_kind', None), ('evidence_kind', 'native_tooltip'),
                             ('native_styles', True), ('native_styles', 0), ('native_styles', 'false'),
                             ('source_complete', False)]:
            doc = document(); doc['entries'][0]['native_example'][field] = value
            with self.assertRaisesRegex(ValueError, 'explicit historical tooltip evidence'):
                prepared(doc).add_reviewed_numeric_families(doc)

    def test_recorded_pointer_range_and_snapshot_hash_are_not_optional(self):
        for field, value in [('snapshot_file', ''), ('snapshot_sha256', 'bad'), ('record_pointer', ''),
                             ('record_pointer', '/0670'), ('record_pointer', '/other/670'),
                             ('line_start', 0), ('line_end', 11), ('line_start', '10')]:
            doc = document(); doc['entries'][0]['native_example'][field] = value
            with self.assertRaises(ValueError):
                prepared(doc).add_reviewed_numeric_families(doc)
        doc = document(); example = doc['entries'][0]['native_example']
        example['snapshot_hash'] = example.pop('snapshot_sha256')
        prepared(doc).add_reviewed_numeric_families(doc)
        example['snapshot_sha256'] = 'c' * 64
        with self.assertRaisesRegex(ValueError, 'snapshot hash'):
            prepared(doc).add_reviewed_numeric_families(doc)

    def test_old_record_cannot_be_presented_as_a_fresh_native_log_event(self):
        for field, value in [('time', '2026-10-30T00:00:00Z'), ('log', 'latest.log'), ('version', '0.7.10'),
                             ('event_line', 1), ('screen_title', 'SkyBlock Menu'), ('tooltip_id', 'fabricated')]:
            doc = document(); doc['entries'][0]['native_example'][field] = value
            with self.assertRaisesRegex(ValueError, 'cannot claim native event metadata'):
                prepared(doc).add_reviewed_numeric_families(doc)

    def test_complete_historical_source_and_joined_line_hashes_are_verified(self):
        for field, value in [('source', 'A different source.'), ('source_sha256', '0' * 64),
                             ('source_lines', ['A different source.']), ('source_lines_sha256', '0' * 64),
                             ('role', 'NPC_DIALOGUE'), ('channel', 'npc'), ('event_sha256', 'bad')]:
            doc = document(); doc['entries'][0]['native_example'][field] = value
            with self.assertRaises(ValueError):
                prepared(doc).add_reviewed_numeric_families(doc)

    def test_candidate_and_explicit_review_gates_are_unchanged(self):
        doc = document(); c = prepared(doc, candidate=False); c.add_reviewed_numeric_families(doc)
        self.assertEqual(0, c.finish('fixture')[1]['reviewed_numeric_family_count'])
        doc['entries'][0]['approved'] = False; c = prepared(doc); c.add_reviewed_numeric_families(doc)
        self.assertEqual(0, c.finish('fixture')[1]['reviewed_numeric_family_count'])
        for bad in ('yes', 1, None):
            doc = document(); doc['entries'][0]['approved'] = bad
            with self.assertRaises(ValueError):
                prepared(doc).add_reviewed_numeric_families(doc)


if __name__ == '__main__':
    unittest.main()

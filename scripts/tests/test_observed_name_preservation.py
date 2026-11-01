"""Only source-bound, reviewed name captures can become identity dictionary rows."""
import unittest
from test_observed_text import row, build


def preserved(channel='ui', role='UI_TITLE', source='\uE010 SkyBlock Level'):
    value = row(channel=channel, source=source, target=source)
    value.update(role=role, kind='proper_name', keep_original=True,
                 preservation_reason='固有名称のため英語表記を保持する。')
    value['evidence']['role'] = role
    if role == 'NAMED_LABEL':
        value['preservation_label_type'] = 'feature_name'
    return value


class ObservedNamePreservationTests(unittest.TestCase):
    def test_explicit_name_roles_emit_only_their_exact_observed_scope(self):
        for channel in ('item', 'ui'):
            for role in ('UI_TITLE', 'ITEM_NAME', 'ITEM_TYPE', 'ABILITY_NAME', 'NAMED_LABEL'):
                with self.subTest(channel=channel, role=role):
                    value = preserved(channel, role)
                    dictionary, report = build([value])
                    self.assertEqual(1, report['observed_text_entry_count'])
                    self.assertEqual(1, report['keep_original_count'])
                    self.assertEqual([{'channel': channel, 'speaker': '', 'source': value['en'],
                                      'target': value['en'], 'keep_original': True,
                                      'source_ids': [value['id']]}], dictionary['entries'])
                    self.assertEqual(0, report['template_entry_count'])

    def test_missing_or_explicit_false_flags_do_not_infer_name_preservation(self):
        for flag in ('missing', False):
            value = row(source='Farming XXXVI', target='Farming XXXVI')
            if flag != 'missing':
                value['keep_original'] = flag
            dictionary, report = build([value])
            self.assertFalse(dictionary['entries'][0]['keep_original'])
            self.assertEqual(0, report['keep_original_count'])
        value = row(); value['keep_original'] = False
        self.assertFalse(build([value])[0]['entries'][0]['keep_original'])

    def test_boolean_flag_rejects_json_lookalikes(self):
        for flag in (0, 1, 'true', 'false', None, [], {}):
            with self.subTest(flag=flag):
                value = preserved(); value['keep_original'] = flag
                with self.assertRaisesRegex(ValueError, 'preservation flag'):
                    build([value])

    def test_identity_is_exact_not_case_number_icon_or_whitespace_normalized(self):
        for target in ('\uE010 skyblock Level', '\uE010 SkyBlock Level 2', 'SkyBlock Level',
                       '\uE010  SkyBlock Level', '\uE010 SkyBlock Level '):
            with self.subTest(target=target):
                value = preserved(); value['ja'] = target
                with self.assertRaisesRegex(ValueError, 'exact identity'):
                    build([value])

    def test_prose_npc_chat_and_general_channels_cannot_be_preserved(self):
        for channel, role in [('item', 'DESCRIPTION'), ('ui', 'DESCRIPTION'), ('ui', 'UI_CONTROL'),
                              ('npc', 'NPC_DIALOGUE'), ('system', 'UI_TITLE'),
                              ('common', 'ITEM_NAME'), ('player_chat', 'ITEM_NAME')]:
            with self.subTest(channel=channel, role=role):
                value = preserved(); value.update(channel=channel, role=role)
                with self.assertRaisesRegex(ValueError, 'preservation scope'):
                    build([value])

    def test_rationale_must_explicitly_preserve_names_and_not_excuse_missing_translation(self):
        for rationale in (None, '', '   ', '固有名詞である。', '未収録のため原文保持。',
                          'keep_original because missing translation', 'retired text; keep_original'):
            with self.subTest(rationale=rationale):
                value = preserved(); value['preservation_reason'] = rationale
                with self.assertRaisesRegex(ValueError, 'preservation reason'):
                    build([value])
        value = preserved(); value['preservation_reason'] = 'Reviewed proper name; keep_original.'
        self.assertTrue(build([value])[0]['entries'][0]['keep_original'])

    def test_name_kind_and_reason_cannot_silently_survive_a_disabled_flag(self):
        mutations = [lambda r: r.pop('keep_original'), lambda r: r.update(keep_original=False),
                     lambda r: r.update(kind='ui'), lambda r: r.pop('kind'),
                     lambda r: r.pop('preservation_reason')]
        for mutate in mutations:
            value = preserved(); mutate(value)
            with self.assertRaises(ValueError):
                build([value])

    def test_named_label_is_only_an_explicit_reviewed_feature_name(self):
        for label_type in (None, 'description', 'stat_label', 'arbitrary_title'):
            value = preserved(role='NAMED_LABEL')
            value['preservation_label_type'] = label_type
            with self.assertRaisesRegex(ValueError, 'preserved label type'):
                build([value])
        value = preserved(role='NAMED_LABEL', source='SkyBlock Levels')
        self.assertTrue(build([value])[0]['entries'][0]['keep_original'])

    def test_capture_role_text_hash_and_complete_source_are_still_bound(self):
        mutations = [lambda r: r['evidence'].update(role='DESCRIPTION'),
                     lambda r: r['evidence'].update(source_complete=False),
                     lambda r: r['evidence'].update(source_lines=['Different source']),
                     lambda r: r.update(source_sha256='a' * 64),
                     lambda r: r['evidence'].update(event_sha256='not-a-hash')]
        for mutate in mutations:
            value = preserved(); mutate(value)
            with self.assertRaises(ValueError):
                build([value])
        value = preserved(); value['token_roles'] = {'ordinary_spans': []}
        with self.assertRaisesRegex(ValueError, 'cannot mask ordinary tokens'):
            build([value])

    def test_only_complete_native_or_recorded_tooltips_can_supply_preserved_names(self):
        value = preserved(); evidence = value['evidence']
        value['evidence'] = {'kind': 'recorded_tooltip', 'channel': 'ui', 'speaker': '', 'source_complete': True,
            'source_lines': evidence['source_lines'], 'source_lines_sha256': evidence['source_lines_sha256'],
            'snapshot_file': 'audit/tooltips.json', 'snapshot_sha256': 'a' * 64, 'record_pointer': '/3',
            'event_sha256': 'b' * 64, 'context_known': False, 'role': 'UI_TITLE', 'line_start': 1, 'line_end': 1}
        self.assertTrue(build([value])[0]['entries'][0]['keep_original'])
        for kind in ('wiki_source', 'npc_dialogue', 'manual_guess', None):
            bad = preserved(); bad['evidence']['kind'] = kind
            with self.assertRaisesRegex(ValueError, 'requires captured tooltip evidence'):
                build([bad])

    def test_candidate_and_human_approval_gates_remain_in_force(self):
        value = preserved()
        self.assertEqual([], build([value], candidate=False)[0]['entries'])
        value['approved'] = False
        self.assertEqual([], build([value])[0]['entries'])
        value['approved'] = 'yes'
        with self.assertRaises(ValueError):
            build([value])

    def test_identity_cannot_override_a_conflicting_translation_without_review(self):
        identity = preserved(source='SkyBlock Levels')
        translation = row(source='SkyBlock Levels', target='SkyBlockのレベル')
        translation['id'] += '_translation'; translation['role'] = 'UI_TITLE'
        translation['evidence']['role'] = 'UI_TITLE'
        dictionary, report = build([identity, translation])
        self.assertEqual([], dictionary['entries'])
        self.assertEqual(1, report['conflicting_key_count'])


if __name__ == '__main__':
    unittest.main()

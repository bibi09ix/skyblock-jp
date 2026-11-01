"""Exact native supplements must retain provenance, scope and immutable values."""
import copy
import unittest
from test_compile_dictionary import compiler


def row(channel='ui', source='View all profiles.', target='すべてのプロフィールを見る。', speaker=''):
    role = 'NPC_DIALOGUE' if channel == 'npc' else 'DESCRIPTION'
    lines = [source]
    evidence = {
        'kind': 'npc_dialogue' if channel == 'npc' else 'tooltip',
        'channel': channel, 'speaker': speaker, 'source_complete': True,
        'source_lines': lines, 'source_lines_sha256': compiler.sha256(compiler.json_bytes(lines)),
        'event_sha256': 'a' * 64, 'log': 'native-test.jsonl', 'event_line': 1,
        'time': '2026-10-30T01:00:00Z', 'version': '0.7.8',
        'role': role, 'tooltip_id': 'fixture-tooltip', 'screen_title': 'SkyBlock Menu',
        'line_start': 2, 'line_end': 2,
    }
    return {'id': f'observed/{channel}/fixture', 'channel': channel, 'speaker': speaker,
            'role': role, 'en': source, 'ja': target, 'approved': True,
            'reason': '完全なゲーム内原文を確認して訳した。',
            'source_sha256': compiler.sha256(source.encode('utf8')), 'evidence': evidence}


def build(rows, candidate=True, names=()):
    c = compiler.Compiler(candidate=candidate, names=names)
    c.add_observed_text({'schema_version': 1, 'entries': rows})
    return c.finish('fixture')


class ObservedTextTests(unittest.TestCase):
    def test_scoped_ordinary_roles_emit_entries_without_renaming_real_npcs(self):
        value = row('npc', 'An old item.', '古い品。', 'Malik')
        value['token_roles'] = {'ordinary_spans': [{'start': 0, 'end': 2, 'text': 'An', 'role': 'indefinite_article'}]}
        dictionary, report = build([value], names=['An'])
        self.assertEqual(1, len(dictionary['entries']))
        self.assertNotIn('protected_name_changed', report['skipped_reason_counts'])
        for source, literal in [('Visit An.', 'An'), ('Dean is here.', 'Dean')]:
            bad = row('npc', source, 'ここにいる。', 'Malik')
            start = source.index(literal)
            bad['token_roles'] = {'ordinary_spans': [{'start': start, 'end': start + len(literal), 'text': literal, 'role': 'indefinite_article' if literal == 'An' else 'ordinary_word'}]}
            with self.assertRaises(ValueError): build([bad], names=['An', 'Dean'])

    def test_recovered_wiki_body_stays_bound_to_exact_line_and_speaker(self):
        value = row('npc', 'Hello!', 'やあ！', 'Dean')
        value['id'] = 'recovered/npc/fixture'
        original = '[NPC] Dean: Hello!\n[NPC] Dean: Goodbye!'
        value['evidence'] = {'kind': 'wiki_source', 'channel': 'npc', 'speaker': 'Dean', 'source_complete': True,
            'source_lines': ['Hello!'], 'source_lines_sha256': compiler.sha256(compiler.json_bytes(['Hello!'])),
            'extracted_text': original, 'extracted_text_sha256': compiler.sha256(original.encode('utf8')), 'extracted_line': 0,
            'source': {'file': 'wiki/pages/fixture.json', 'sha256': 'b' * 64, 'url': 'https://example.invalid/revision/1', 'revision': 1}}
        self.assertEqual(1, len(build([value])[0]['entries']))
        for field, replacement in [('extracted_line', 1), ('extracted_text_sha256', '0' * 64), ('speaker', 'Maddox'), ('source_complete', False)]:
            bad = copy.deepcopy(value); bad['evidence'][field] = replacement
            with self.assertRaises(ValueError): build([bad])

    def test_recorded_tooltip_does_not_invent_native_context_or_time(self):
        value = row('item')
        value['evidence'] = {'kind': 'recorded_tooltip', 'channel': 'item', 'speaker': '', 'source_complete': True,
            'source_lines': [value['en']], 'source_lines_sha256': compiler.sha256(compiler.json_bytes([value['en']])),
            'snapshot_file': 'audit/tooltips.json', 'snapshot_sha256': 'a' * 64, 'record_pointer': '/3', 'event_sha256': 'b' * 64,
            'context_known': False, 'role': 'DESCRIPTION', 'line_start': 2, 'line_end': 2}
        self.assertEqual(1, len(build([value])[0]['entries']))
        for field, replacement in [('snapshot_sha256', ''), ('record_pointer', ''), ('context_known', 'unknown'), ('line_end', 4)]:
            bad = copy.deepcopy(value); bad['evidence'][field] = replacement
            with self.assertRaises(ValueError): build([bad])

    def test_exact_scope_and_npc_speaker_are_retained_without_cross_expansion(self):
        rows = [row(), row('item', 'Works in the bag!', 'バッグ内で効果を発揮する！'),
                row('npc', '✆ Come back later.', '✆ あとで来てくれ。', 'Dean')]
        dictionary, report = build(rows)
        self.assertEqual(3, report['observed_text_entry_count'])
        self.assertEqual({(r['channel'], r['speaker'], r['en']) for r in rows},
                         {(r['channel'], r['speaker'], r['source']) for r in dictionary['entries']})
        self.assertEqual(0, report['template_entry_count'])
        self.assertEqual([], build(rows, candidate=False)[0]['entries'])
        rows[0]['approved'] = False
        self.assertEqual(2, len(build(rows)[0]['entries']))

    def test_stale_incomplete_or_mismatched_evidence_is_rejected(self):
        mutations = [lambda r: r.update(en='Different source.'),
                     lambda r: r['evidence'].update(source_complete=False),
                     lambda r: r['evidence'].update(channel='item'),
                     lambda r: r['evidence'].update(source_lines=['Wrong.']),
                     lambda r: r['evidence'].update(event_sha256='bad'),
                     lambda r: r['evidence'].update(event_line=0),
                     lambda r: r['evidence'].update(line_end=4),
                     lambda r: r.update(role='PRICE'),
                     lambda r: r.update(approved='yes')]
        for mutate in mutations:
            with self.subTest(mutate=mutate):
                value = row(); mutate(value)
                with self.assertRaises(ValueError): build([value])
        value = row('npc', 'Hello!', 'やあ！', 'Dean')
        value['evidence']['speaker'] = 'Maddox'
        with self.assertRaises(ValueError): build([value])

    def test_values_icons_commands_and_names_stay_unchanged(self):
        source = 'Use /visit Dean to receive 5❤.'
        good = '/visit Deanで5❤を受け取ろう。'
        self.assertEqual(1, len(build([row(source=source, target=good)], names=['Dean'])[0]['entries']))
        for target in (good.replace('5', '6'), good.replace('❤', ''),
                       good.replace('/visit', '/trade'), good.replace('Dean', 'ディーン')):
            with self.assertRaises(ValueError): build([row(source=source, target=target)], names=['Dean'])

    def test_conflicts_are_quarantined_and_edits_change_identity(self):
        first = row(); other = copy.deepcopy(first)
        other['id'] += '_other'; other['ja'] = 'プロフィール一覧を開く。'
        dictionary, report = build([first, other])
        self.assertEqual([], dictionary['entries'])
        self.assertEqual(1, report['conflicting_key_count'])
        before = build([first])[0]['build_id']
        first['ja'] = other['ja']
        self.assertNotEqual(before, build([first])[0]['build_id'])

    def test_literal_command_name_is_not_generic_html_or_a_variable(self):
        source = 'Play with friends using /coopadd <name>!'
        target = '/coopadd <name>でフレンドと一緒に遊ぼう！'
        self.assertIsNone(compiler.unsafe_reason(source))
        self.assertEqual(1, len(build([row(source=source, target=target)])[0]['entries']))
        with self.assertRaises(ValueError): build([row(source=source, target=target.replace('<name>', '<username>'))])
        with self.assertRaises(ValueError): build([row(source='Use /ignore add <username>!', target='/ignore remove <username>を使う！')])
        for text in ('Hello <name>!', 'Use /coopadd <script>!', 'Use <b>/coopadd</b>!',
                     'Use /coopadd <name onclick="run()">!', 'Use /coopadd <name> and <img>!',
                     'Use /coopadd {name}!', 'Use /coopadd &lt;name&gt;!'):
            self.assertIsNotNone(compiler.unsafe_reason(text), text)


if __name__ == '__main__':
    unittest.main()

"""Offline safety/contract tests for the candidate dictionary compiler."""
import copy
import contextlib
import io
import json
import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "compile_dictionary.py"
SPEC = importlib.util.spec_from_file_location("compile_dictionary", SCRIPT)
compiler = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = compiler
SPEC.loader.exec_module(compiler)


def entry(source="A useful description.", target="便利な説明。", **extra):
    row = {"id": "fixture/1", "kind": "item", "en": source, "ja": target, "status": "draft", "note": "", "tokens": [], "runtime_eligible": False, "source_review": {"game_validated": False}}
    row.update(extra)
    return row


def compile_rows(*rows, candidate=True, names=()):
    c = compiler.Compiler(candidate=candidate, names=names)
    for row in rows:
        c.add(row)
    return c.finish("fixture")


class CompilerTests(unittest.TestCase):
    def numeric_families(self):
        document = json.loads(SCRIPT.with_name('dictionary-numeric-families.json').read_text(encoding='utf8'))
        # These tests specifically exercise the unchanged Sea Creatures family.
        # Other independently reviewed families have their own pinned fixtures.
        document['entries'] = [row for row in document['entries']
                               if row['id'] == 'reviewed/template/sea_creature_damage_percent']
        self.assertEqual(1, len(document['entries']))
        return document

    def family_compiler(self, doc):
        c = compiler.Compiler(candidate=True)
        for sample in doc['entries'][0]['source_pairs']:
            for source_id in sample['source_ids']:
                c.add(entry(sample['source'], sample['target'], id=source_id, role='description'))
        return c

    def test_reviewed_numeric_family_requires_unchanged_complete_source_pairs(self):
        doc = self.numeric_families(); c = self.family_compiler(doc)
        c.add_reviewed_numeric_families(doc)
        _, report = c.finish('fixture')
        self.assertEqual(1, report['reviewed_numeric_family_count'])
        templates = [r for r in report['matching_document']['templates'] if r['source'].startswith('Increases damage dealt to Sea Creatures')]
        self.assertEqual({'item', 'ui'}, {r['channel'] for r in templates})
        self.assertEqual(2, len(templates))
        for mutate in (
            lambda r: r.update(en=r['en'].replace('Sea Creatures', 'Players')),
            lambda r: r['source_pairs'][0].update(target_sha256='0' * 64),
            lambda r: r['source_pairs'][0].update(source_ids=['unknown/source']),
            lambda r: r['native_example'].update(source='Increases damage dealt to Players by 20%.'),
            lambda r: r['native_example'].update(source_lines=['Increases damage dealt to Sea Creatures by 21%.']),
            lambda r: r.update(channels=['npc']),
        ):
            changed = copy.deepcopy(doc); mutate(changed['entries'][0])
            with self.assertRaises(ValueError): self.family_compiler(doc).add_reviewed_numeric_families(changed)

    def test_reviewed_numeric_family_cannot_hide_new_conflicting_source_translation(self):
        doc = self.numeric_families(); c = self.family_compiler(doc)
        sample = doc['entries'][0]['source_pairs'][0]
        c.add(entry(sample['source'], sample['target'] + '追加効果。', id='competing/translation', role='description'))
        with self.assertRaises(ValueError): c.add_reviewed_numeric_families(doc)
        doc['entries'][0]['approved'] = False
        c = self.family_compiler(doc); c.add_reviewed_numeric_families(doc)
        self.assertEqual(0, c.finish('fixture')[1]['reviewed_numeric_family_count'])

    def observed_ui(self):
        return json.loads(SCRIPT.with_name('dictionary-observed-ui.json').read_text(encoding='utf8'))

    def test_native_ui_supplement_is_literal_scoped_and_candidate_only(self):
        doc = self.observed_ui()
        c = compiler.Compiler(candidate=True)
        c.add_observed_ui(doc)
        dictionary, report = c.finish('fixture')
        self.assertEqual(8, report['observed_ui_entry_count'])
        self.assertEqual({r['en'] for r in doc['entries']}, {r['source'] for r in dictionary['entries']})
        self.assertEqual({'ui'}, {r['channel'] for r in dictionary['entries']})
        self.assertEqual(0, report['template_entry_count'])
        verified = compiler.Compiler(candidate=False)
        verified.add_observed_ui(doc)
        self.assertEqual([], verified.finish('fixture')[0]['entries'])

    def test_native_ui_supplement_rejects_stale_evidence_and_scope_changes(self):
        for mutate in (
            lambda r: r.update(en='Close this menu'),
            lambda r: r.update(channel='npc'),
            lambda r: r.update(role='DESCRIPTION'),
            lambda r: r['evidence'].update(channel='item'),
            lambda r: r['evidence'].update(source_lines=['Open']),
            lambda r: r['evidence'].update(source_lines_sha256='0' * 64),
            lambda r: r['evidence'].update(line_end=999),
        ):
            doc = self.observed_ui()
            mutate(doc['entries'][0])
            with self.assertRaises(ValueError): compiler.Compiler(candidate=True).add_observed_ui(doc)
        doc = self.observed_ui(); excluded_source = doc['entries'][0]['en']; doc['entries'][0]['approved'] = False
        c = compiler.Compiler(candidate=True); c.add_observed_ui(doc)
        self.assertNotIn(excluded_source, {r['source'] for r in c.finish('fixture')[0]['entries']})

    def test_native_ui_supplement_keeps_commands_and_conflicts_fail_closed(self):
        for target in ('/otherからも開ける。', 'statsからも開ける。', '/statsと/newからも開ける。'):
            doc = self.observed_ui()
            next(row for row in doc['entries'] if row['en'] == 'Also accessible via /stats')['ja'] = target
            with self.assertRaises(ValueError): compiler.Compiler(candidate=True).add_observed_ui(doc)
        c = compiler.Compiler(candidate=True)
        c.add(entry('Close', '近い', kind='ui', role='slot_title'))
        c.add_observed_ui(self.observed_ui())
        dictionary, report = c.finish('fixture')
        self.assertNotIn('Close', {r['source'] for r in dictionary['entries']})
        self.assertIn({'channel': 'ui', 'speaker': '', 'source': 'Close'}, dictionary['blocked_keys'])

    def test_native_ui_target_and_evidence_edits_change_build_identity(self):
        def build(doc):
            c = compiler.Compiler(candidate=True); c.add_observed_ui(doc)
            return c.finish('fixture')[0]['build_id']
        doc = self.observed_ui(); before = build(doc)
        self.assertEqual(before, build(copy.deepcopy(doc)))
        doc['entries'][0]['ja'] = '画面を閉じる'
        self.assertNotEqual(before, build(doc))
        doc = self.observed_ui(); doc['entries'][0]['reason'] += ' 再確認。'
        self.assertNotEqual(before, build(doc))

    def test_ordinary_policy_words_do_not_hide_real_prose_but_full_names_remain(self):
        names=['Damage','An','Set','Stats','Royal Damage Set','Strength']
        source='Damage from Royal Damage Set is increased by 10%.'
        target='Royal Damage Setのダメージが10%増加する。'
        self.assertEqual(1,len(compile_rows(entry(source,target),names=names)[0]['entries']))
        self.assertEqual([],compile_rows(entry(source,target.replace('Royal Damage Set','防具')),names=names)[0]['entries'])

    def test_real_npc_name_an_remains_protected_even_when_it_can_be_an_article(self):
        source='[NPC] Bob: Talk to An in the Hub.'
        target='[NPC] Bob: HubにいるAnに話しかけて。'
        self.assertEqual(1,len(compile_rows(entry(source,target,kind='npc'),names=['An'])[0]['entries']))
        self.assertEqual([],compile_rows(entry(source,target.replace('An','アン'),kind='npc'),names=['An'])[0]['entries'])
        # Ambiguous sentence-start "An" remains conservative until grammatical
        # article context can be established without damaging the actual name.
        self.assertEqual([],compile_rows(entry('An ancient stone.','古代の石。'),names=['An'])[0]['entries'])

    def test_ordinal_suffix_does_not_become_a_trillion_unit(self):
        source='Every 7th hit deals +100% Damage.'
        target='7回目の攻撃でダメージが+100%増加する。'
        self.assertEqual(1,len(compile_rows(entry(source,target),names=['Damage'])[0]['entries']))
        self.assertEqual([],compile_rows(entry('Gain 7t Coins.','7 Coinsを得る。'))[0]['entries'])

    def test_known_multiplier_words_can_be_rendered_as_digits(self):
        for word,value in [('Double','2'),('Doubles','2'),('doubled','2'),('twice','2'),('triple','3'),('triples','3'),('tripled','3'),('thrice','3'),('quadruple','4'),('quadruples','4'),('quadrupled','4')]:
            with self.subTest(word=word):
                self.assertEqual(1,len(compile_rows(entry(word+' the bonus for 30s.','30sの間、ボーナスが'+value+'倍になる。'))[0]['entries']))

    def test_quantity_allowance_never_drops_or_changes_literal_values(self):
        for target in ('30sの間、ボーナスが3倍になる。','30sの間、ボーナスが+2倍になる。','30sの間、ボーナスが2%増える。','30sの間、ボーナスが2k倍になる。','31sの間、ボーナスが2倍になる。','ボーナスが2倍になる。','30sの間、2と2を得る。'):
            self.assertEqual([],compile_rows(entry('Doubles the bonus for 30s.',target))[0]['entries'],target)
        self.assertEqual([],compile_rows(entry('Doubles the +50% bonus.','+100%のボーナスを得る。'))[0]['entries'])
        self.assertFalse(compiler.quantities_preserved('Double-Layered bonus.','ボーナスが2倍になる。'))
        self.assertFalse(compiler.quantities_preserved('Doubles the 1k bonus.','1Kのボーナスが2倍になる。'))
        self.assertFalse(compiler.quantities_preserved('Doubles the 1,000 bonus.','1000のボーナスが2倍になる。'))

    def test_quantity_words_also_work_in_declared_templates_without_weakening_holes(self):
        row=self.template_row('Doubles the bonus for {0}s.','{0}sの間、ボーナスが2倍になる。')
        _,report=compile_rows(row)
        self.assertEqual(2,report['template_entry_count'])  # item + approved UI tooltip applicability
        row['ja']='{0}sの間、ボーナスが3倍になる。'
        self.assertEqual(0,compile_rows(row)[1]['template_entry_count'])

    def template_row(self,source,target,**extra):
        tokens=[{'token':token,'count':source.count(token),'role':'unresolved_source_parameter'} for token in dict.fromkeys(compiler.VARIABLE.findall(source))]
        # VARIABLE captures the interior; metadata stores the whole source token.
        tokens=[{'token':'{'+t['token']+'}','count':source.count('{'+t['token']+'}'),'role':t['role']} for t in tokens]
        return entry(source,target,role='ability_body',tokens=tokens,**extra)

    def test_declared_numeric_and_haste_roman_templates_are_separate_from_literal_keys(self):
        row=self.template_row('Grants +{2} Mining Speed and permanent Haste {3}.',' Mining Speedが+{2}増加し、常時Haste {3}を得る。')
        dictionary,report=compile_rows(row)
        self.assertEqual([],dictionary['entries'])
        self.assertEqual(2,report['template_entry_count'])
        template=report['matching_document']['templates'][0]
        self.assertEqual([{'name':'v0','type':'number'},{'name':'v1','type':'roman'}],template['variables'])
        self.assertEqual('Grants +{v0} Mining Speed and permanent Haste {v1}.',template['source'])

    def test_template_literal_fidelity_and_variable_alignment_fail_closed(self):
        for target in ('Healthが{1}増加する。','Healthが{0}と{0}増加する。','Healthが{0}と50増加する。','Healthが{0}増加する。'):
            row=self.template_row('Grants +{0} ❤ Health.',target)
            _,report=compile_rows(row)
            self.assertEqual(0,report['template_entry_count'],target)
            self.assertTrue(report['template_rejection_counts'])
        row=self.template_row('Gain {0} Health.','Healthが{0}増加する。',kind='npc')
        self.assertEqual(0,compile_rows(row)[1]['template_entry_count'])

    def test_templates_canonicalize_variable_names_and_reject_conflicting_targets(self):
        a=self.template_row('Grants {0} Health.','Healthが{0}増加する。',id='a')
        b=self.template_row('Grants {amount} Health.','Healthが{amount}増加する。',id='b')
        _,report=compile_rows(a,b)
        self.assertEqual(['a','b'],report['matching_document']['templates'][0]['source_ids'])
        b['ja']='{amount}のHealthを得る。'
        _,report=compile_rows(a,b)
        self.assertEqual(0,report['template_entry_count'])
        self.assertEqual(2,report['template_rejection_counts']['conflicting_template'])

    def test_verified_alias_does_not_modify_literal_source(self):
        dictionary,report=compile_rows(entry('View and manage all your Pets.','所持するペットを管理する。',kind='ui'))
        self.assertEqual('View and manage all your Pets.',dictionary['entries'][0]['source'])
        self.assertEqual('View and manage all of your Pets.',report['matching_document']['aliases'][0]['source'])

    def test_default_requires_both_flags_and_hold_always_wins(self):
        for eligible, validated in ((False, False), (True, False), (False, True)):
            dictionary, report = compile_rows(entry(runtime_eligible=eligible, source_review={"game_validated": validated}), candidate=False)
            self.assertEqual([], dictionary["entries"])
            self.assertEqual(1, report["skipped_reason_counts"]["not_game_validated_and_runtime_eligible"])
        row = entry(runtime_eligible=True, source_review={"game_validated": True})
        self.assertEqual(1, len(compile_rows(row, candidate=False)[0]["entries"]))
        row["status"] = "hold"
        self.assertEqual([], compile_rows(row)[0]["entries"])

    def test_candidate_does_not_mutate_flags(self):
        row = entry()
        original = copy.deepcopy(row)
        dictionary, report = compile_rows(row)
        self.assertEqual("candidate", dictionary["tier"])
        self.assertEqual(row, original)
        self.assertFalse(report["actual_game_validation_performed"])
        self.assertFalse(report["source_adoption_flags_modified"])

    def test_untranslated_and_excluded_without_rationale_never_leak(self):
        for row in (entry(target=""), entry(status="untranslated"), entry(status="excluded", note="Missing source; keep original"), entry(status="excluded", note="")):
            self.assertEqual([], compile_rows(row)[0]["entries"])
        dictionary, _ = compile_rows(entry(status="excluded", note="固有名詞は英語を維持する。"))
        self.assertTrue(dictionary["entries"][0]["keep_original"])
        self.assertEqual(dictionary["entries"][0]["source"], dictionary["entries"][0]["target"])

    def test_conflict_rejects_key_but_not_other_contexts(self):
        rows = [entry(id="a"), entry(target="別の説明。", id="b"), entry(target="UIの説明。", id="c", kind="ui")]
        dictionary, report = compile_rows(*rows)
        self.assertEqual(["ui"], [e["channel"] for e in dictionary["entries"]])
        self.assertEqual(1, report["conflicting_key_count"])
        self.assertEqual(["a", "b"], report["skipped_source_ids_by_reason"]["conflicting_translation"])
        identity = entry(status="excluded", note="原文維持", id="keep")
        self.assertEqual([], compile_rows(rows[0], identity)[0]["entries"])
        # A numerically invalid competing draft also black-lists the literal
        # key, and disagreements are audited even when both drafts are unsafe.
        dictionary, report = compile_rows(entry("Gain 10 Speed.", "10 Speedを得る。", id="a"), entry("Gain 10 Speed.", "20 Speedを得る。", id="b"))
        self.assertEqual([], dictionary["entries"])
        self.assertEqual(1, report["conflicting_key_count"])
        self.assertEqual(1, compile_rows(entry("Gain 10 Speed.", "20 Speedを得る。", id="a"), entry("Gain 10 Speed.", "30 Speedを得る。", id="b"))[1]["conflicting_key_count"])

    def test_normalization_dedupes_only_literal_keys(self):
        rows = [entry("§aA\u00a0useful\n description.", id="b"), entry(id="a")]
        dictionary, _ = compile_rows(*rows)
        self.assertEqual(1, len(dictionary["entries"]))
        self.assertEqual(["a", "b"], dictionary["entries"][0]["source_ids"])
        self.assertEqual("A useful description.", dictionary["entries"][0]["source"])

    def test_npc_scoping_line_and_group_alignment(self):
        row = entry("[NPC] Bob: Hello.\n[NPC] Bob: How are you?\n[BOSS] Alice: Go away.", "[NPC] Bob: やあ。\n[NPC] Bob: 元気？\n[BOSS] Alice: あっちへ行け。", kind="npc")
        dictionary, _ = compile_rows(row)
        result = {(e["speaker"], e["source"]): e["target"] for e in dictionary["entries"]}
        self.assertEqual("やあ。 元気？", result[("Bob", "Hello. How are you?")])
        self.assertIn(("Alice", "Go away."), result)
        self.assertEqual(4, len(result))
        for target in ("[NPC] Bob: やあ。", "[NPC] Rob: やあ。\n[NPC] Bob: 元気？\n[BOSS] Alice: あっちへ行け。"):
            self.assertEqual([], compile_rows({**row, "ja": target})[0]["entries"])

    def test_same_npc_words_keep_speaker_context(self):
        rows = [entry("[NPC] Bob: Hello.", "[NPC] Bob: やあ。", kind="npc", id="bob"), entry("[NPC] Alice: Hello.", "[NPC] Alice: こんにちは。", kind="npc", id="alice")]
        dictionary, report = compile_rows(*rows)
        self.assertEqual({"Alice", "Bob"}, {e["speaker"] for e in dictionary["entries"]})
        self.assertEqual(0, report["conflicting_key_count"])
        rows.append(entry("[NPC] Bob: Hello.", "[NPC] Bob: おい。", kind="npc", id="bob-conflict"))
        dictionary, report = compile_rows(*rows)
        self.assertEqual(["Alice"], [e["speaker"] for e in dictionary["entries"]])
        self.assertEqual(1, report["conflicting_key_count"])

    def test_options_are_scoped_and_contiguous(self):
        row = entry("[NPC] Bob: Pick one.\nSelect an option: [Yes]\n[No]\n[NPC] Bob: Goodbye.", "[NPC] Bob: 選んで。\n選択肢：[はい]\n[いいえ]\n[NPC] Bob: さようなら。", kind="npc")
        dictionary, _ = compile_rows(row)
        options = [e for e in dictionary["entries"] if e["speaker"] == "選択肢"]
        self.assertEqual(3, len(options))
        self.assertIn("Select an option: [Yes] [No]", [e["source"] for e in options])
        self.assertEqual([], compile_rows(entry("[292] [MVP++] Someone: Hi", "[292] [MVP++] Someone: やあ", kind="npc"))[0]["entries"])

    def test_unknown_placeholders_markup_and_controls_rejected(self):
        for source in ("Gain {0} Speed.", "Gain # Speed.", "<s0>Words</s0>", "<n0/>", "&lt;n0/&gt;", "Wiki [[Link]]", "X\x00Y", "X\u202eY", "§xWords", "X\ufffdY", "X\ud800Y"):
            with self.subTest(source=source):
                self.assertEqual([], compile_rows(entry(source, source))[0]["entries"])
        self.assertEqual([], compile_rows(entry(tokens=[{"token": "{0}"}]))[0]["entries"])

    def test_numeric_glyph_and_protected_name_fidelity(self):
        source = "Gain +50%  Speed with Royal Titanium Belt."
        valid = "+50%  SpeedをRoyal Titanium Beltで得る。"
        self.assertEqual(1, len(compile_rows(entry(source, valid), names=["Royal Titanium Belt", "Speed"])[0]["entries"]))
        for target in (valid.replace("50", "5"), valid.replace("%", "％"), valid.replace("", ""), valid.replace("Royal Titanium Belt", "ベルト")):
            self.assertEqual([], compile_rows(entry(source, target), names=["Royal Titanium Belt", "Speed"])[0]["entries"])
        self.assertEqual([], compile_rows(entry("Get 1k Coins.", "1000 Coinsを得る。"))[0]["entries"])

    def test_deterministic_order_and_output(self):
        rows = [entry(id="a"), entry("Other description.", "別の説明。", id="b")]
        self.assertEqual(compiler.json_bytes(compile_rows(*rows)[0]), compiler.json_bytes(compile_rows(*reversed(rows))[0]))
        self.assertEqual(compiler.json_bytes(compile_rows(*rows)[1]), compiler.json_bytes(compile_rows(*reversed(rows))[1]))

    def test_source_files_are_read_only_and_artifacts_repeat(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / "source"
            pack = root / "translations" / "fixture.json"
            pack.parent.mkdir(parents=True)
            fixture_rows = [entry()] + [entry(sample['source'], sample['target'], id=source_id, role='description')
                for family in self.numeric_families()['entries'] for sample in family['source_pairs'] for source_id in sample['source_ids']]
            pack.write_bytes(compiler.json_bytes({"schema_version": 1, "entries": fixture_rows}))
            (root / "manifest.json").write_bytes(compiler.json_bytes({"build_id": "fixture", "entry_count": len(fixture_rows)}))
            (root / "translation-policy.json").write_bytes(compiler.json_bytes({"names": {}}))
            before = {p.relative_to(root).as_posix(): p.read_bytes() for p in root.rglob("*") if p.is_file()}
            a = compiler.compile_source(root, candidate=True, include_supplements=False)
            b = compiler.compile_source(root, candidate=True, include_supplements=False)
            self.assertEqual(a, b)
            self.assertEqual(before, {p.relative_to(root).as_posix(): p.read_bytes() for p in root.rglob("*") if p.is_file()})
            self.assertEqual(compiler.sha256(compiler.json_bytes(a[0])), a[1]["dictionary_sha256"])
            with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                compiler.main(["--source-root", str(root), "--output", str(root / "wrong.json"), "--report", str(Path(temp) / "report.json")])

    def test_tooltip_applicability_does_not_rewrite_provenance_or_escape_to_chat(self):
        rows = [entry('Use this item to create a portal.', 'このアイテムでポータルを作る。', kind='ui', role='slot_description', id='ui/body'),
                entry('Main Menu', 'メインメニュー', kind='ui', role='slot_title', id='ui/title'),
                entry('Right-click to consume!', '右クリックで消費する！', role='instruction', id='item/instruction'),
                entry('Crafting Material', 'クラフト素材', role='label', id='item/label')]
        before = copy.deepcopy(rows)
        dictionary, report = compile_rows(*rows)
        by_id = {row['id']: {e['channel'] for e in dictionary['entries'] if row['id'] in e['source_ids']} for row in rows}
        self.assertEqual({'item', 'ui'}, by_id['ui/body'])
        self.assertEqual({'item', 'ui'}, by_id['item/instruction'])
        self.assertEqual({'ui'}, by_id['ui/title'])
        self.assertEqual({'item'}, by_id['item/label'])
        self.assertEqual(before, rows)
        self.assertEqual('tooltip_roles_v2_observed_guarded', report['runtime_applicability_policy']['version'])

    def test_conflicting_shared_tooltip_meanings_are_quarantined_in_both_scopes(self):
        dictionary, report = compile_rows(entry(id='item/a', role='description'),
            entry(target='違う意味の説明。', id='ui/a', kind='ui', role='slot_description'))
        self.assertEqual([], dictionary['entries'])
        self.assertEqual(2, report['conflicting_key_count'])
        self.assertEqual({'item', 'ui'}, {e['channel'] for e in dictionary['blocked_keys']})

    def test_conflicting_scoped_keys_keep_tombstones_even_with_common_fallback(self):
        common = entry(id='common/a', kind='common')
        first, _ = compile_rows(common)
        dictionary, report = compile_rows(common, entry(id='item/a', role='description'),
            entry(target='別の説明。', id='ui/a', kind='ui', role='slot_description'))
        self.assertEqual(first['entries'], dictionary['entries'])
        self.assertEqual([{'channel': channel, 'speaker': '', 'source': common['en']} for channel in ('item', 'ui')], dictionary['blocked_keys'])
        self.assertEqual(2, report['blocked_key_count'])
        self.assertNotEqual(first['build_id'], dictionary['build_id'])

    def test_template_only_changes_change_both_document_build_ids(self):
        row = self.template_row('Gain {0} Health.', 'Healthが{0}増加する。')
        first, first_report = compile_rows(row)
        row['ja'] = 'Healthを{0}得る。'
        second, second_report = compile_rows(row)
        self.assertEqual(first['entries'], second['entries'])
        self.assertNotEqual(first['build_id'], second['build_id'])
        self.assertEqual(second['build_id'], second_report['matching_document']['build_id'])
        self.assertNotEqual(first_report['matching_document'], second_report['matching_document'])

    def test_reviewed_resolution_requires_current_source_variants_and_explicit_approval(self):
        rows = [entry('Gain 10 Health.', 'Healthが10増加する。', id='a'),
                entry('Gain 10 Health.', 'Healthを10得る。', id='b'),
                entry('Gain 10 Health.', 'Healthが10増加する。', id='c', kind='common')]
        dictionary, report = compile_rows(*rows)
        evidence = compiler._reviews.candidates(compiler._reviews.catalog(dictionary['entries'], report['conflicts']), 'item', '', rows[0]['en'])
        rule = {'id': 'review', 'approved': True, 'channel': 'item', 'speaker': '', 'source': rows[0]['en'],
                'source_sha256': compiler._reviews.source_hash(rows[0]['en']), 'variants_sha256': compiler._reviews.digest(evidence),
                'target': 'Healthが10増加する。', 'keep_original': False, 'requires': {}, 'reason': 'Reviewed fixture'}
        def compile_review(value, extra_rows=()):
            current = compiler.Compiler(candidate=True, reviews={'rules': [value]})
            for row in [*rows, *extra_rows]: current.add(row)
            return current.finish('fixture')
        reviewed, review_report = compile_review(rule)
        self.assertEqual(1, review_report['reviewed_rule_count'])
        self.assertEqual(1, len(reviewed['review_evidence']))
        self.assertEqual(dictionary['blocked_keys'], reviewed['blocked_keys'])
        for field, value in [('approved', False), ('source_sha256', 'stale'), ('variants_sha256', 'stale')]:
            _, rejected = compile_review({**rule, field: value})
            self.assertEqual(0, rejected['reviewed_rule_count'])
            self.assertEqual(1, len(rejected['reviewed_rule_rejections']))
        _, rejected = compile_review(rule, [entry('Gain 10 Health.', 'Healthが10上がる。', id='new')])
        self.assertEqual('review_variants_hash_mismatch', rejected['reviewed_rule_rejections'][0]['reason'])
        _, rejected = compile_review({**rule, 'requires': {'ui_id': 'wiki-only'}})
        self.assertEqual('unsupported_review_context', rejected['reviewed_rule_rejections'][0]['reason'])
        _, rejected = compile_review({**rule, 'target': 'Healthが11増加する。'})
        self.assertEqual('review_numeric_token_changed', rejected['reviewed_rule_rejections'][0]['reason'])
        alternative, _ = compile_review({**rule, 'target': 'Healthを10得る。'})
        self.assertNotEqual(reviewed['build_id'], alternative['build_id'])

    def test_alignment_content_changes_build_identity(self):
        first = compiler.Compiler(candidate=True, alignment_sha256='a' * 64)
        second = compiler.Compiler(candidate=True, alignment_sha256='b' * 64)
        first.add(entry()); second.add(entry())
        self.assertNotEqual(first.finish('fixture')[0]['build_id'], second.finish('fixture')[0]['build_id'])

    def test_reviewed_ordinary_roles_are_source_bound_and_keep_real_names(self):
        rows = [entry('An empty bottle stores a Scatha.', 'Scathaを入れる空の瓶。', id='a'),
                entry('An empty bottle stores a Scatha.', '空の瓶にScathaを入れる。', id='b')]
        names = ['An', 'Scatha']
        base, report = compile_rows(*rows, names=names)
        evidence = compiler._reviews.candidates(compiler._reviews.catalog(base['entries'], report['conflicts']), 'item', '', rows[0]['en'])
        rule = {'id': 'article', 'approved': True, 'channel': 'item', 'speaker': '', 'source': rows[0]['en'],
                'source_sha256': compiler._reviews.source_hash(rows[0]['en']), 'variants_sha256': compiler._reviews.digest(evidence),
                'target': 'Scathaを入れる空の瓶。', 'keep_original': False, 'requires': {}, 'reason': 'Reviewed article',
                'token_roles': {'ordinary_spans': [{'start': 0, 'end': 2, 'text': 'An', 'role': 'indefinite_article'}]}}
        def run(value):
            current = compiler.Compiler(candidate=True, names=names, reviews={'rules': [value]})
            for row in rows: current.add(row)
            return current.finish('fixture')[1]
        self.assertEqual(1, run(rule)['reviewed_rule_count'])
        self.assertEqual('review_protected_name_changed', run({**rule, 'target': '空の瓶。'})['reviewed_rule_rejections'][0]['reason'])
        wrong = copy.deepcopy(rule); wrong['token_roles']['ordinary_spans'][0]['start'] = 1
        self.assertEqual('review_span_source_mismatch', run(wrong)['reviewed_rule_rejections'][0]['reason'])
        self.assertEqual([], compile_rows(entry('Talk to An.', 'アンに話しかける。'), names=names)[0]['entries'])

    def test_percent_and_three_reviewed_wording_preserves_exact_original_numeric_tokens(self):
        examples = [
            ('Increases damage dealt by 1% for each percent of health your target has.', '対象の残りHealthの各パーセントポイントにつき、与えるダメージが1%増加する。'),
            ('Increases damage dealt by 1.2% for each percent of extra health that your target has above you up to 65%.', '対象のHealthが自分を上回る割合の各パーセントポイントにつき、与えるダメージが1.2%増加する。上限は65%。'),
            ('Increases damage dealt by 1.25% for each percent of health missing on your target.', '対象が失ったHealthの割合の各パーセントポイントにつき、与えるダメージが1.25%増加する。'),
            ('Increases melee damage dealt by 50% for the first three hits on a mob.', 'Mobへの最初の三回の攻撃で、与える近接ダメージが50%増加する。')]
        for source, target in examples:
            self.assertEqual(compiler.numeric_signature(source), compiler.numeric_signature(target))
            self.assertTrue(compiler.quantities_preserved(source, target))
            self.assertFalse(compiler.quantities_preserved(source, target + '1%'))

    def test_every_output_path_collision_is_rejected_before_writing(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            same = root / 'candidate-matching.json'
            other = root / 'other.json'
            same.write_text('sentinel', encoding='utf8')
            base = ['--source-root', str(root / 'source')]
            cases = [
                ['--output', str(same), '--report', str(same), '--matching-output', str(other)],
                ['--output', str(other), '--report', str(same), '--matching-output', str(same)],
                ['--output', str(same), '--report', str(other), '--matching-output', str(same)],
                ['--output', str(same), '--report', str(other)],  # default matching path
                ['--output', str(other), '--report', str(root / 'manifest.json'), '--matching-output', str(same), '--reviewed-output', str(same)],
                ['--output', str(other), '--report', str(same), '--matching-output', str(root / 'match.json'), '--reviewed-output', str(same)],
                ['--output', str(same), '--report', str(other), '--matching-output', str(root / 'match.json'), '--reviewed-output', str(same)],
                ['--output', str(root / 'candidate-reviewed.json'), '--report', str(other)],
            ]
            for args in cases:
                with self.subTest(args=args), contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                    compiler.main(base + args)
                self.assertEqual('sentinel', same.read_text(encoding='utf8'))
                self.assertFalse(other.exists())


if __name__ == "__main__":
    unittest.main()

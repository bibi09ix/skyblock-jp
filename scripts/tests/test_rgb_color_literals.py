"""RGB color values are complete immutable atoms, distinct from numbered # labels."""
import unittest
from test_compile_dictionary import compiler, entry, compile_rows


class RgbColorLiteralTests(unittest.TestCase):
    SOURCE = 'Changes the color of an armor piece to #7FFFD4!'
    TARGET = '防具の色を#7FFFD4に変更する！'

    def test_only_complete_six_digit_hex_atoms_are_recognized(self):
        for code in ('#7FFFD4', '#abcdef', '#000000', '#123456'):
            self.assertEqual({code.upper(): 1}, compiler.rgb_signature(code))
        for token in ('#ABC', '#ABCDEF0', '#ABCDEF01', '#7FFFD4G', 'x#7FFFD4',
                      '_#7FFFD4', '0#7FFFD4', '##7FFFD4', '#7FFFD4_', '#RRGGBB', '#123', '#12345678'):
            self.assertEqual({}, compiler.rgb_signature(token), token)

    def test_actual_armor_dye_source_is_safe_and_keeps_its_original_literal_key(self):
        self.assertIsNone(compiler.unsafe_reason(self.SOURCE))
        self.assertEqual(self.SOURCE, compiler.normalize(self.SOURCE))
        dictionary, report = compile_rows(entry(self.SOURCE, self.TARGET, role='description'))
        self.assertEqual({'item', 'ui'}, {r['channel'] for r in dictionary['entries']})
        self.assertTrue(all(r['source'] == self.SOURCE and r['target'] == self.TARGET for r in dictionary['entries']))
        self.assertEqual(0, report['conflicting_key_count'])

    def test_color_letter_mutations_are_rejected_even_with_identical_numeric_chunks(self):
        bad = self.TARGET.replace('7FFFD4', '7AAAD4')
        self.assertEqual(compiler.numeric_signature(self.SOURCE), compiler.numeric_signature(bad))
        self.assertFalse(compiler.quantities_preserved(self.SOURCE, bad))
        dictionary, report = compile_rows(entry(self.SOURCE, bad, role='description'))
        self.assertEqual([], dictionary['entries'])
        self.assertIn('protected_rgb_color_changed', report['skipped_reason_counts'])

    def test_rgb_value_is_case_insensitive_but_source_normalization_does_not_rewrite_case(self):
        source = self.SOURCE.replace('7FFFD4', '7fffd4')
        self.assertTrue(compiler.quantities_preserved(source, self.TARGET))
        self.assertEqual(source, compiler.normalize(source))
        dictionary, _ = compile_rows(entry(source, self.TARGET, role='description'))
        self.assertTrue(all(r['source'] == source for r in dictionary['entries']))

    def test_colors_cannot_be_deleted_added_or_changed_into_unknown_hash_tokens(self):
        for target in (self.TARGET.replace('#7FFFD4', ''), self.TARGET + '#7FFFD4',
                       self.TARGET.replace('#7FFFD4', '#7FFFE4'), self.TARGET.replace('#7FFFD4', '#ABC'),
                       self.TARGET.replace('#7FFFD4', '#7FFFD40')):
            self.assertFalse(compiler.quantities_preserved(self.SOURCE, target), target)

    def test_numeric_quantities_are_still_checked_after_rgb_atom_equality(self):
        source = 'Use 5 items with #ABCD12.'
        self.assertTrue(compiler.quantities_preserved(source, '#abcd12に5個使う。'))
        self.assertFalse(compiler.quantities_preserved(source, '#ABCD12に6個使う。'))
        self.assertFalse(compiler.quantities_preserved(source, '#ABCD12に5個と12個使う。'))
        self.assertNotEqual({}, compiler.numeric_signature('#ABCD12'), 'raw numeric utility must not silently mask RGB without fidelity checking')

    def test_malformed_hashes_templates_and_word_attached_colors_remain_unsafe(self):
        for source in ('Armor #ABC', 'Armor #ABCDEF0', 'Armor #ABCDEF01', 'Armor #RRGGBB',
                       'Armor x#ABCDEF', 'Armor #ABCDEFx', 'Armor ##ABCDEF', 'Armor #ABCDEF_',
                       'Armor #{color}', 'Armor <#ABCDEF>', 'Armor #name'):
            self.assertIsNotNone(compiler.unsafe_reason(source), source)
        for marker in ('#{rgb}', '#ABC{rgb}'):
            dictionary, report = compile_rows(entry('Changes the armor color to ' + marker + '!',
                '防具の色を' + marker + 'に変更する！', role='description',
                tokens=[{'token': '{rgb}', 'count': 1, 'role': 'unresolved_source_parameter'}]))
            self.assertEqual([], dictionary['entries'])
            self.assertEqual(0, report['template_entry_count'])

    def test_existing_numbered_label_grammar_is_retained_without_calling_it_rgb(self):
        for text in ('Rabbit #5', 'Your Ranking: #000', 'labeled #1 to #33333!', 'Position: #12345678'):
            self.assertIsNone(compiler.unsafe_reason(text), text)
            self.assertEqual({}, compiler.rgb_signature(text), text)
        self.assertTrue(compiler.quantities_preserved('Position: #12345678', '順位: #12345678'))


if __name__ == '__main__':
    unittest.main()

import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('dictionary_audit', Path(__file__).parents[1]/'dictionary_audit.py')
audit = importlib.util.module_from_spec(spec); spec.loader.exec_module(audit)


class PreservationAuditTest(unittest.TestCase):
    def test_action_prose_is_flagged_but_names_and_stat_lines_are_not(self):
        sources = ['Royal Titanium Belt', 'Health: +50',
                   'Consumes 3 material to grant the following effects: Pests are 2x more likely to spawn.']
        d = {'build_id':'fixture', 'entries':[{'channel':'ui','source':s,'target':s,
             'keep_original':True,'source_ids':['public/'+str(i)]} for i,s in enumerate(sources)]}
        result = audit.audit(d)
        self.assertEqual(3, result['counts_by_channel']['ui']['preserved'])
        self.assertEqual([sources[2]], [r['source'] for r in result['suspected_preserved_prose']])

    def test_translated_prose_does_not_raise_a_preservation_alarm(self):
        source = 'Grants 50 Mining Speed for 20 seconds when using this tool.'
        d = {'build_id':'fixture','entries':[{'channel':'item','source':source,'target':'20秒間、50 Mining Speedを得る。',
             'keep_original':False,'source_ids':['public/item']}]}
        result = audit.audit(d)
        self.assertEqual([], result['suspected_preserved_prose'])
        self.assertEqual({'keys':1}, result['counts_by_channel']['item'])

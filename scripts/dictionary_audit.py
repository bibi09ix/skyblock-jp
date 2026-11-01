"""Audit English-preservation decisions without guessing new translations.

This is a review queue, not a proof that all remaining identities are names.
Runtime key counts are never presented as a game's untranslated percentage.
"""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re

ACTION = re.compile(r'\b(?:consumes|grants|increases|reduces|decreases|allows|gives|deals|restores|summons|prevents|requires)\b', re.I)
JAPANESE = re.compile(r'[\u3040-\u30ff\u3400-\u9fff]')


def audit(document):
    counts = {}
    suspected = []
    for row in document['entries']:
        channel = row['channel']; c = counts.setdefault(channel, Counter())
        c['keys'] += 1
        if row['keep_original']:
            c['preserved'] += 1
            source = row['source']
            # Long grammatical action text contradicts a pure-name classification.
            # No row is automatically changed; an editor must inspect source and context.
            if len(re.findall(r'[A-Za-z]+', source)) >= 8 and ACTION.search(source):
                suspected.append({'channel': channel, 'source': source,
                    'source_sha256': hashlib.sha256(source.encode('utf-8')).hexdigest(),
                    'source_ids': row['source_ids'], 'reason': 'preserved_action_prose_needs_review'})
        if not JAPANESE.search(row['target']): c['target_without_japanese'] += 1
    return {'schema_version': 1, 'build_id': document['build_id'],
            'scope': 'dictionary keys, not weighted observed game displays',
            'counts_by_channel': {k: dict(v) for k,v in sorted(counts.items())},
            'suspected_preserved_prose': suspected,
            'limitations': ['Heuristic review queue; zero findings does not certify every preservation decision.',
                           'English names, stat rows, symbols and sounds may intentionally have no Japanese.']}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('dictionary', type=Path); p.add_argument('--output', type=Path)
    p.add_argument('--check', action='store_true', help='Fail if preserved action prose needs review')
    a = p.parse_args(); result = audit(json.loads(a.dictionary.read_bytes()))
    text = json.dumps(result, ensure_ascii=False, indent=2) + '\n'
    if a.output:
        a.output.parent.mkdir(parents=True, exist_ok=True); a.output.write_text(text, encoding='utf-8')
    else: print(text, end='')
    return 1 if a.check and result['suspected_preserved_prose'] else 0


if __name__ == '__main__': raise SystemExit(main())

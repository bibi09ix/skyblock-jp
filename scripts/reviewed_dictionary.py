"""Compile explicit, evidence-bound review decisions without modifying source packs."""
from collections import defaultdict
import hashlib
import json
import re

CONTEXT_FIELDS = {'containerTitle', 'location', 'slotMatches', 'slotIndex', 'containerSize',
                  'skyblockItemId', 'minecraftItemId', 'screenClass', 'previousDialogue', 'previousSpeaker'}
GLYPHS = [('☘', '\ue051', 'Farming Fortune'), ('☘', '\ue053', 'Mining Fortune'),
          ('⸕', '\ue015', 'Mining Speed'), ('✎', '\ue003', 'Intelligence'),
          ('❁', '\ue00d', 'Strength'), ('❈', '\ue008', 'Defense')]

def source_key(text):
    text = ' '.join(re.sub('§[0-9a-fk-or]', '', text, flags=re.I).split())
    for old, modern, stat in GLYPHS:
        text = re.sub(re.escape(old) + r'(?=\s*' + stat + r'(?![A-Za-z]))', modern, text, flags=re.I)
    text = ''.join(chr(ord(c) + 32) if 'A' <= c <= 'Z' else c for c in text)
    return text[:-1] if text.endswith('.') and not text.endswith('..') else text

def source_hash(source):
    return hashlib.sha256(source_key(source).encode('utf-8')).hexdigest()

def digest(value):
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True,
                                    separators=(',', ':')).encode('utf-8')).hexdigest()

def catalog(entries, conflicts):
    rows = defaultdict(list)
    for origin, values in [('emitted', entries), ('quarantined', conflicts)]:
        for row in values:
            for variant in row.get('variants', [row]):
                candidate = {field: row[field] for field in ('channel', 'speaker', 'source')}
                candidate.update({field: variant[field] for field in ('target', 'keep_original', 'source_ids')})
                candidate['origin'] = origin
                rows[(row['channel'], row['speaker'], source_key(row['source']))].append(candidate)
    return rows

def candidates(rows, channel, speaker, source):
    found = list(rows.get((channel, speaker, source_key(source)), []))
    if channel in ('item', 'ui', 'system'):
        found += rows.get(('common', '', source_key(source)), [])
    # Stable semantic evidence includes every source ID and every competing target.
    unique = {json.dumps(row, ensure_ascii=False, sort_keys=True, separators=(',', ':')): row for row in found}
    return [unique[key] for key in sorted(unique)]

def compile_reviews(entries, conflicts, review_document, validate):
    rows = catalog(entries, conflicts)
    compiled, rejected, registry = [], [], {}
    seen_ids = set()
    for rule in (review_document or {}).get('rules', []):
        reason = None
        channel, speaker, source = rule.get('channel'), rule.get('speaker', ''), rule.get('source', '')
        key = (channel, speaker, source_key(source))
        evidence = candidates(rows, channel, speaker, source)
        actual_hash = digest(evidence)
        if not isinstance(rule.get('id'), str) or not rule['id'] or rule['id'] in seen_ids: reason = 'duplicate_or_invalid_review_id'
        elif rule.get('approved') is not True: reason = 'review_not_approved'
        elif channel not in {'item', 'ui', 'npc', 'common', 'system'} or (channel == 'npc') != bool(speaker): reason = 'invalid_review_scope'
        elif not evidence: reason = 'review_source_missing'
        elif rule.get('source_sha256') != source_hash(source): reason = 'review_source_hash_mismatch'
        elif rule.get('variants_sha256') != actual_hash: reason = 'review_variants_hash_mismatch'
        elif not isinstance(rule.get('requires', {}), dict) or not set(rule.get('requires', {})) <= CONTEXT_FIELDS: reason = 'unsupported_review_context'
        elif any(not isinstance(value, (str, bool, int)) or isinstance(value, str) and len(value) > 16000 for value in rule.get('requires', {}).values()): reason = 'invalid_review_context_value'
        elif channel == 'npc' and not set(rule.get('requires', {})) <= {'previousDialogue', 'previousSpeaker'}: reason = 'invalid_npc_review_context'
        elif channel != 'npc' and set(rule.get('requires', {})) & {'previousDialogue', 'previousSpeaker'}: reason = 'invalid_tooltip_review_context'
        else: reason = validate(source, rule.get('target', ''), rule.get('keep_original', False), rule)
        seen_ids.add(rule.get('id'))
        if reason:
            rejected.append({'id': rule.get('id', ''), 'channel': channel, 'speaker': speaker, 'source': source, 'reason': reason})
            continue
        compiled.append({field: rule[field] for field in ('id', 'approved', 'channel', 'speaker', 'source',
            'source_sha256', 'variants_sha256', 'target', 'keep_original', 'requires', 'reason')} | {
                'source_ids': sorted({source_id for row in evidence for source_id in row['source_ids']})})
        registry[key] = {'channel': channel, 'speaker': speaker, 'source': key[2], 'variants_sha256': actual_hash}
    compiled.sort(key=lambda row: (row['channel'], row['speaker'], row['source'], row['id']))
    return compiled, [registry[key] for key in sorted(registry)], rejected

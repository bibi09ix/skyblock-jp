#!/usr/bin/env python3
"""Compile read-only reviewed source packs into a literal runtime dictionary.

This compiler does not approve translations or modify their adoption flags.  The
default verified tier requires BOTH runtime_eligible and game_validated.  Public
resource drafts can only be packaged with the explicit --candidate switch; the
mod must separately opt in to that candidate tier.  No network is used.
"""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
from dataclasses import dataclass
from decimal import Decimal
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import tempfile
import unicodedata
from typing import Any, Iterable
import importlib.util

_review_spec = importlib.util.spec_from_file_location('skyblock_reviewed_dictionary', Path(__file__).with_name('reviewed_dictionary.py'))
_reviews = importlib.util.module_from_spec(_review_spec)
_review_spec.loader.exec_module(_reviews)


SCHEMA_VERSION = 1
CHANNELS = frozenset({"item", "npc", "ui", "system", "common"})
MAX_TEXT_LENGTH = 16_000
FORMAT = re.compile(r"\u00a7[0-9a-fk-or]", re.IGNORECASE)
NUMBER = re.compile(r"[+-]?(?:[0-9]+(?:,[0-9]{3})*(?:\.[0-9]+)?|\.[0-9]+)(?:[kKmMbBtT](?![A-Za-z]))?(?:%|\u2030)?")
ENTITY = re.compile(r"&(?:#[xX][0-9a-fA-F]+|#[0-9]+|[A-Za-z][A-Za-z0-9]*);")
NPC_PREFIX = re.compile(r"^\[(NPC|BOSS)\]\s+([^:\n]+):\s*(.*)$")
OPTION_PREFIX = re.compile(r"^Select an option\s*[:\uff1a]?\s*(.*)$", re.IGNORECASE)
JA_OPTION_PREFIX = re.compile(r"^(?:Select an option|\u9078\u629e\u80a2)\s*[:\uff1a]?\s*(.*)$", re.IGNORECASE)
PLAYER_PREFIX = re.compile(r"^(?:\[(?:[0-9]+|(?:MVP|VIP)(?:\+)*|YOUTUBE|ADMIN)\]|(?:Guild|Party|Co-op|Officer)\s*>)", re.IGNORECASE)
# An excluded row is an intentional identity only with an explicit preservation
# rationale.  Missing or structurally retired source text is never an identity.
PRESERVATION = re.compile(r"(?:\u539f\u6587\u7dad\u6301|\u539f\u6587\u3092\u7dad\u6301|\u82f1\u8a9e[^\u3002\n]{0,45}(?:\u7dad\u6301|\u4fdd\u6301)|\u539f\u6587[^\u3002\n]{0,25}\u4fdd\u6301|keep[_ -](?:english|original)|identity[_ -]preserv)", re.IGNORECASE)
UNRELIABLE_EXCLUSION = re.compile(r"(?:\u7f3a\u843d|\u7a7a\u672c\u6587|\u672c\u6587\u304c\u7a7a|\u539f\u6587\u304c\u7a7a|\u672a\u63a1\u53d6|\u672a\u53ce\u9332|\u672a\u5b8c|\u9000\u5f79|retired|supersed|missing|empty source)", re.IGNORECASE)
ASCII_WORD = frozenset("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_")
# These entries in the harvested policy are ordinary prose, not named entities.
# Full names containing them (e.g. a named item ending in Set) remain protected.
ORDINARY_POLICY_WORDS = frozenset({"Set", "Stats", "Damage", "Throw", "Fill", "Shoot", "Spawn", "Capture", "Weather"})
OBSERVED_ORDINARY_WORDS = frozenset({'Y', 'Timber', 'Water', 'Think', 'Repeat', 'Toss'})
FIRE_VEIL_FAMILY_EN = 'Creates a veil of fire around you for 5s, dealing {v0} damage per second to mobs within.'
FIRE_VEIL_FAMILY_JA = '周囲に5秒間の炎の幕を作り、その中にいるモブに毎秒{v0}のダメージを与える。'
UNSIGNED_DAMAGE_VALUE = r'(?:0|[1-9][0-9]{0,11}|[1-9][0-9]{0,2}(?:,[0-9]{3}){1,3})(?:\.[0-9]{1,6})?'
VARIABLE = re.compile(r"\{([A-Za-z_][A-Za-z0-9_]*|[0-9]+)\}")
COMMAND_NAME_PLACEHOLDER = re.compile(r'(?<![A-Za-z0-9_/:])/[A-Za-z][A-Za-z0-9_-]*(?:[ \t]+[A-Za-z0-9_-]+){0,2}[ \t]+(<(?:name|username|id)>)')
NUMBERED_LABEL = re.compile(r'(?<![A-Za-z0-9_])#[0-9]+(?![A-Za-z0-9_])')
RGB_LITERAL = re.compile(r'(?<![A-Za-z0-9_#])#[0-9A-Fa-f]{6}(?![A-Za-z0-9_#])')
VERIFIED_SOURCE_ALIASES = {"View and manage all your Pets.": ("View and manage all of your Pets.",)}
REVIEWED_UI_TEMPLATES = [
    {"channel":"ui","speaker":"","source":"Selected pet: {v0}","target":"選択中のペット: {v0}",
     "variables":[{"name":"v0","type":"pet_name"}],"source_ids":["reviewed/ui/selected_pet"]},
    {"channel":"ui","speaker":"","source":"Progress to Level {v0}: {v1}%","target":"レベル{v0}までの進捗: {v1}%",
     "variables":[{"name":"v0","type":"number"},{"name":"v1","type":"number"}],"source_ids":["reviewed/ui/pet_progress"]},
    {"channel":"ui","speaker":"","source":"Level {v0} Rewards:","target":"レベル{v0}の報酬:",
     "variables":[{"name":"v0","type":"roman"}],"source_ids":["reviewed/ui/level_rewards"]},
]
TOOLTIP_ROLES = frozenset({'description', 'ability_body', 'flavor', 'instruction', 'requirement', 'list_entry'})
APPLICABILITY_POLICY = {
    'version': 'tooltip_roles_v2_observed_guarded',
    'item_roles_shared_with_ui': sorted(TOOLTIP_ROLES),
    'ui_roles_shared_with_item': ['slot_description'],
    'excluded_scopes': ['npc', 'system', 'player_chat'],
    'conflicts': 'quarantine all disagreeing translations in each emitted runtime scope',
    'observed_descriptions': 'explicit hash-bound native replay and exact runtime context; never unconditional scope emission',
}


def runtime_channels(entry: dict[str, Any]) -> tuple[str, ...]:
    """Source kind is provenance; the reviewed tooltip role determines applicability.

    Only actual tooltip bodies/instructions are shared. Menu titles, chat hover
    messages and NPC/system text never acquire another context through this rule.
    The source pack and its adoption/provenance fields remain untouched.
    """
    kind, role = entry['kind'], entry.get('role')
    if kind == 'item' and role in TOOLTIP_ROLES or kind == 'ui' and role == 'slot_description':
        return ('item', 'ui')
    return (kind,)


def normalize(text: str) -> str:
    """The shared literal-key normalization: formatting removal + whitespace."""
    return " ".join(FORMAT.sub("", text).split())


def json_bytes(value: Any) -> bytes:
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode("utf-8")


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def unsafe_reason(text: str) -> str | None:
    stripped = FORMAT.sub("", text)
    if not normalize(text):
        return "empty_text"
    if any((unicodedata.category(c) in {"Cc", "Cf", "Cs"} and c not in "\t\r\n") or c == "\ufffd" for c in stripped):
        return "control_or_invalid_character"
    if len(normalize(text).encode("utf-16-le")) // 2 > MAX_TEXT_LENGTH:
        return "text_too_long"
    without_numbered_labels = NUMBERED_LABEL.sub(lambda m: m.group()[1:], RGB_LITERAL.sub(lambda m: ' ' * len(m.group()), stripped))
    if any(c in without_numbered_labels for c in "{}#"):
        return "dynamic_or_unknown_placeholder"
    # Native help has three reviewed literal command arguments. These are not
    # HTML or variables; accept only their exact spelling after a slash command.
    without_placeholders = COMMAND_NAME_PLACEHOLDER.sub(lambda m: m.group().replace('<', '').replace('>', ''), stripped)
    if any(c in without_placeholders for c in "<>") or ENTITY.search(stripped) or "'''" in stripped or "[[" in stripped or "]]" in stripped:
        return "markup_or_encoded_marker"
    if "\u00a7" in stripped:
        return "unknown_formatting_code"
    return None


def numeric_signature(text: str) -> Counter[str]:
    return Counter(NUMBER.findall(text))


def rgb_signature(text: str) -> Counter[str]:
    return Counter(code.upper() for code in RGB_LITERAL.findall(text))


MULTIPLIER_WORD = re.compile(r"(?i)(?<![A-Za-z0-9_-])(?:double|doubles|doubled|twice|triple|triples|tripled|thrice|quadruple|quadruples|quadrupled)(?![A-Za-z0-9_-])")


def quantities_preserved(source: str, target: str) -> bool:
    """Literal values stay exact; explicit quantity words may become 2/3/4."""
    if rgb_signature(source) != rgb_signature(target):
        return False
    # The public numeric signature still counts literal digits. Mask hexadecimal
    # digits only here, after full RGB atom equality has been established.
    required = numeric_signature(RGB_LITERAL.sub(' ', source))
    actual = numeric_signature(RGB_LITERAL.sub(' ', target))
    if required - actual:
        return False
    allowance: Counter[str] = Counter()
    for match in MULTIPLIER_WORD.finditer(source):
        word = match.group().lower()
        allowance['2' if word in {'double', 'doubles', 'doubled', 'twice'} else '3' if word in {'triple', 'triples', 'tripled', 'thrice'} else '4'] += 1
    return not (actual - required - allowance)


def glyph_signature(text: str) -> Counter[str]:
    # Private-use game stat glyphs, currency, math symbols and icon characters
    # are kept exactly.  Punctuation is deliberately not treated as an icon.
    return Counter(c for c in text if unicodedata.category(c).startswith("S") or unicodedata.category(c) == "Co")


class ProtectedNames:
    """Small literal trie; no policy term is interpreted as a regex."""

    def __init__(self, names: Iterable[str] = ()) -> None:
        self.root: dict[str, Any] = {}
        for original in sorted(names):
            name = normalize(original)
            if not name:
                continue
            node = self.root
            for char in name:
                node = node.setdefault(char, {})
            node[""] = name

    def occurrences(self, text: str) -> Counter[str]:
        result: Counter[str] = Counter()
        for start, char in enumerate(text):
            node = self.root.get(char)
            if node is None:
                continue
            end = start + 1
            while node is not None:
                name = node.get("")
                if name is not None:
                    left_ok = name[0] not in ASCII_WORD or start == 0 or text[start - 1] not in ASCII_WORD
                    right_ok = name[-1] not in ASCII_WORD or end == len(text) or text[end] not in ASCII_WORD
                    if left_ok and right_ok:
                        result[name] += 1
                if end == len(text):
                    break
                node = node.get(text[end])
                end += 1
        return result


@dataclass(frozen=True)
class Unit:
    channel: str
    speaker: str
    source: str
    target: str
    keep_original: bool
    source_id: str


class Compiler:
    def __init__(self, *, candidate: bool = False, names: Iterable[str] = (), reviews: dict[str, Any] | None = None, alignment_sha256: str = '', ordinary_tokens_sha256: str = '') -> None:
        self.reviews = reviews or {'schema_version': 1, 'rules': []}
        self.alignment_sha256 = alignment_sha256
        self.ordinary_tokens_sha256 = ordinary_tokens_sha256
        self.candidate = candidate
        self.names = ProtectedNames(name for name in names if name not in ORDINARY_POLICY_WORDS)
        self.proposals: dict[tuple[str, str, str], dict[tuple[str, bool], set[str]]] = defaultdict(lambda: defaultdict(set))
        self.observed_variants: dict[tuple[str, str, str], dict[tuple[str, bool], set[str]]] = defaultdict(lambda: defaultdict(set))
        self.skipped: dict[str, set[str]] = defaultdict(set)
        self.statuses: Counter[str] = Counter()
        self.flags: Counter[str] = Counter()
        self.input_count = 0
        self.proposed_units = 0
        self.template_proposals: dict[tuple, dict[tuple, set[str]]] = defaultdict(lambda: defaultdict(set))
        self.template_rejections: dict[str, set[str]] = defaultdict(set)
        self.observed_ui: list[dict[str, Any]] = []
        self.observed_text: list[dict[str, Any]] = []
        self.observed_applicability: list[dict[str, Any]] = []
        self.skill_bodies: list[dict[str, Any]] = []
        self.reviewed_numeric_families: list[dict[str, Any]] = []
        self.npc_phone_lines: list[dict[str, Any]] = []

    def skip(self, reason: str, source_id: str) -> None:
        self.skipped[reason].add(source_id)

    def add_observed_ui(self, document: dict[str, Any]) -> None:
        """Reviewed literal UI text with native evidence, separate from source-pack adoption.

        This supplement fills extraction gaps only in the UI channel. It cannot
        authorize an NPC/system fallback or a template inferred from one example.
        The exact source and captured lines are hashed independently of editable ja.
        """
        if document.get('schema_version') != 1 or not isinstance(document.get('entries'), list):
            raise ValueError('Invalid observed UI supplement schema')
        seen = set()
        for row in document['entries']:
            if not isinstance(row, dict): raise ValueError('Invalid observed UI row')
            source_id, source, target = row.get('id'), row.get('en'), row.get('ja')
            evidence = row.get('evidence')
            if not isinstance(source_id, str) or not source_id.startswith('observed/ui/') or len(source_id) > 512 or source_id in seen:
                raise ValueError('Invalid or duplicate observed UI source ID')
            seen.add(source_id)
            if row.get('channel') != 'ui' or row.get('role') not in {'UI_TITLE', 'UI_CONTROL'}:
                raise ValueError(f'Unsupported observed UI scope: {source_id}')
            if not isinstance(source, str) or normalize(source) != source or unsafe_reason(source):
                raise ValueError(f'Unsafe observed UI source: {source_id}')
            if row.get('source_sha256') != sha256(source.encode('utf-8')):
                raise ValueError(f'Stale observed UI source hash: {source_id}')
            if not isinstance(evidence, dict) or evidence.get('channel') != 'ui' or evidence.get('role') != row['role']:
                raise ValueError(f'Observed UI evidence scope mismatch: {source_id}')
            lines = evidence.get('source_lines')
            if not isinstance(lines, list) or not lines or not all(isinstance(line, str) for line in lines):
                raise ValueError(f'Invalid observed UI source lines: {source_id}')
            if normalize(' '.join(lines)) != source or evidence.get('source_lines_sha256') != sha256(json_bytes(lines)):
                raise ValueError(f'Observed UI source evidence mismatch: {source_id}')
            if not re.fullmatch(r'[0-9a-f]{64}', str(evidence.get('event_sha256', ''))):
                raise ValueError(f'Invalid observed UI event hash: {source_id}')
            for field in ('log', 'time', 'version', 'tooltip_id', 'screen_title'):
                if not isinstance(evidence.get(field), str) or not evidence[field].strip():
                    raise ValueError(f'Missing observed UI evidence {field}: {source_id}')
            start, end = evidence.get('line_start'), evidence.get('line_end')
            if type(start) is not int or type(end) is not int or start < 1 or end - start + 1 != len(lines):
                raise ValueError(f'Invalid observed UI line bounds: {source_id}')
            if not isinstance(row.get('reason'), str) or not row['reason'].strip():
                raise ValueError(f'Missing observed UI review rationale: {source_id}')
            if type(row.get('approved')) is not bool: raise ValueError(f'Missing observed UI approval: {source_id}')
            if not row['approved'] or not self.candidate:
                self.skip('unapproved_or_non_candidate_observed_ui', source_id)
                continue
            if not isinstance(target, str) or unsafe_reason(target) or not quantities_preserved(source, target) or glyph_signature(source) != glyph_signature(target):
                raise ValueError(f'Unsafe observed UI target: {source_id}')
            commands = re.compile(r'(?<![A-Za-z0-9_])/[A-Za-z][A-Za-z0-9_-]*')
            if Counter(commands.findall(source)) != Counter(commands.findall(target)):
                raise ValueError(f'Observed UI command changed: {source_id}')
            source_names, target_names = self.names.occurrences(source), self.names.occurrences(target)
            if any(target_names[name] != count for name, count in source_names.items()):
                raise ValueError(f'Observed UI protected name changed: {source_id}')
            self.observed_ui.append(row)
            self.propose(Unit('ui', '', source, target, False, source_id))

    def add_observed_text(self, document: dict[str, Any]) -> None:
        """Exact, reviewed missing prose and explicitly preserved observed names.

        Observed text keeps its original literal scope. The shared applicability
        policy may separately license a guarded tooltip presentation after an
        explicit native review; NPC speakers, names and chat never acquire one.
        These additions do not approve or overwrite the original source packs.
        """
        if document.get('schema_version') != 1 or not isinstance(document.get('entries'), list):
            raise ValueError('Invalid observed text supplement schema')
        allowed = {'item': {'DESCRIPTION'}, 'ui': {'DESCRIPTION', 'UI_TITLE', 'UI_CONTROL'},
                   'npc': {'NPC_DIALOGUE'}}
        preservation_roles = {'UI_TITLE', 'ITEM_NAME', 'ITEM_TYPE', 'ABILITY_NAME', 'NAMED_LABEL'}
        seen = set()
        for row in document['entries']:
            if not isinstance(row, dict): raise ValueError('Invalid observed text row')
            source_id, source, target = row.get('id'), row.get('en'), row.get('ja')
            channel, speaker, role = row.get('channel'), row.get('speaker'), row.get('role')
            keep_original = row.get('keep_original', False)
            if type(keep_original) is not bool:
                raise ValueError(f'Invalid observed text preservation flag: {source_id}')
            if keep_original:
                if channel not in {'item', 'ui'} or role not in preservation_roles or row.get('kind') != 'proper_name':
                    raise ValueError(f'Unsupported observed name preservation scope: {source_id}')
                rationale = row.get('preservation_reason')
                if not isinstance(rationale, str) or not rationale.strip() or not PRESERVATION.search(rationale) or UNRELIABLE_EXCLUSION.search(rationale):
                    raise ValueError(f'Missing explicit observed name preservation reason: {source_id}')
                if source != target:
                    raise ValueError(f'Observed preserved name is not an exact identity: {source_id}')
                if role == 'NAMED_LABEL' and row.get('preservation_label_type') != 'feature_name':
                    raise ValueError(f'Unsupported observed preserved label type: {source_id}')
                if row.get('token_roles'):
                    raise ValueError(f'Observed preserved name cannot mask ordinary tokens: {source_id}')
            else:
                if row.get('kind') == 'proper_name' or 'preservation_reason' in row or 'preservation_label_type' in row:
                    raise ValueError(f'Inconsistent observed name preservation metadata: {source_id}')
                if channel not in allowed or role not in allowed[channel]:
                    raise ValueError(f'Unsupported observed text scope: {source_id}')
            evidence = row.get('evidence')
            wiki = isinstance(evidence, dict) and evidence.get('kind') == 'wiki_source'
            recorded = isinstance(evidence, dict) and evidence.get('kind') == 'recorded_tooltip'
            if keep_original and (not isinstance(evidence, dict) or evidence.get('kind') not in {'tooltip', 'recorded_tooltip'}):
                raise ValueError(f'Observed name preservation requires captured tooltip evidence: {source_id}')
            prefix = 'recovered' if wiki else 'observed'
            if not isinstance(source_id, str) or not source_id.startswith(f'{prefix}/{channel}/') or len(source_id) > 512 or source_id in seen:
                raise ValueError('Invalid or duplicate observed text source ID')
            seen.add(source_id)
            if not isinstance(speaker, str) or (channel == 'npc' and (not speaker.strip() or len(speaker) > 256)) or (channel != 'npc' and speaker):
                raise ValueError(f'Invalid observed text speaker: {source_id}')
            if not isinstance(source, str) or normalize(source) != source or unsafe_reason(source):
                raise ValueError(f'Unsafe observed text source: {source_id}')
            if channel == 'npc' and (PLAYER_PREFIX.match(source) or NPC_PREFIX.match(source)):
                raise ValueError(f'Chat header in observed NPC body: {source_id}')
            if row.get('source_sha256') != sha256(source.encode('utf-8')):
                raise ValueError(f'Stale observed text source hash: {source_id}')
            kind = 'wiki_source' if wiki else 'recorded_tooltip' if recorded else 'npc_dialogue' if channel == 'npc' else 'tooltip'
            if not isinstance(evidence, dict) or evidence.get('kind') != kind or evidence.get('channel') != channel or evidence.get('speaker', '') != speaker or evidence.get('source_complete') is not True:
                raise ValueError(f'Incomplete or mismatched observed text evidence: {source_id}')
            lines = evidence.get('source_lines')
            if not isinstance(lines, list) or not lines or not all(isinstance(line, str) for line in lines) or normalize(' '.join(lines)) != source or evidence.get('source_lines_sha256') != sha256(json_bytes(lines)):
                raise ValueError(f'Observed text source evidence mismatch: {source_id}')
            if wiki:
                original = evidence.get('extracted_text')
                if not isinstance(original, str) or evidence.get('extracted_text_sha256') != sha256(original.encode('utf-8')):
                    raise ValueError(f'Stale recovered source text: {source_id}')
                source_info = evidence.get('source')
                if not isinstance(source_info, dict) or not re.fullmatch(r'[0-9a-f]{64}', str(source_info.get('sha256', ''))) or not isinstance(source_info.get('file'), str) or not source_info['file'] or not isinstance(source_info.get('url'), str) or not source_info['url'] or type(source_info.get('revision')) is not int:
                    raise ValueError(f'Invalid recovered source provenance: {source_id}')
                if channel == 'npc':
                    index = evidence.get('extracted_line')
                    originals = original.splitlines()
                    if type(index) is not int or not 0 <= index < len(originals):
                        raise ValueError(f'Invalid recovered NPC line: {source_id}')
                    parsed = NPC_PREFIX.fullmatch(originals[index].strip())
                    if not parsed or normalize(parsed.group(2)) != speaker or normalize(parsed.group(3)) != source:
                        raise ValueError(f'Recovered NPC source/speaker mismatch: {source_id}')
                elif normalize(original) != source:
                    raise ValueError(f'Recovered tooltip source mismatch: {source_id}')
            elif recorded:
                if channel == 'npc' or not isinstance(evidence.get('snapshot_file'), str) or not evidence['snapshot_file'] or not isinstance(evidence.get('record_pointer'), str) or not evidence['record_pointer'].startswith('/') or type(evidence.get('context_known')) is not bool:
                    raise ValueError(f'Invalid recorded tooltip provenance: {source_id}')
                for field in ('snapshot_sha256', 'event_sha256'):
                    if not re.fullmatch(r'[0-9a-f]{64}', str(evidence.get(field, ''))):
                        raise ValueError(f'Invalid recorded tooltip hash: {source_id}')
            else:
                if not re.fullmatch(r'[0-9a-f]{64}', str(evidence.get('event_sha256', ''))):
                    raise ValueError(f'Invalid observed text event hash: {source_id}')
                for field in ('log', 'time', 'version'):
                    if not isinstance(evidence.get(field), str) or not evidence[field].strip():
                        raise ValueError(f'Missing observed text evidence {field}: {source_id}')
                if type(evidence.get('event_line')) is not int or evidence['event_line'] < 1:
                    raise ValueError(f'Invalid observed text event line: {source_id}')
            if kind in {'tooltip', 'recorded_tooltip'}:
                start, end = evidence.get('line_start'), evidence.get('line_end')
                if evidence.get('role') != role or type(start) is not int or type(end) is not int or start < 1 or end - start + 1 != len(lines) or (kind == 'tooltip' and (not evidence.get('tooltip_id') or not evidence.get('screen_title'))):
                    raise ValueError(f'Invalid observed tooltip evidence: {source_id}')
            if not isinstance(row.get('reason'), str) or not row['reason'].strip() or type(row.get('approved')) is not bool:
                raise ValueError(f'Missing observed text review: {source_id}')
            if not row['approved'] or not self.candidate:
                self.skip('unapproved_or_non_candidate_observed_text', source_id)
                continue
            if not isinstance(target, str) or unsafe_reason(target) or not quantities_preserved(source, target) or glyph_signature(source) != glyph_signature(target):
                raise ValueError(f'Unsafe observed text target: {source_id}')
            commands = re.compile(r'(?<![A-Za-z0-9_])/[A-Za-z][A-Za-z0-9_-]*')
            if Counter(commands.findall(source)) != Counter(commands.findall(target)):
                raise ValueError(f'Observed text command changed: {source_id}')
            if Counter(normalize(m.group()) for m in COMMAND_NAME_PLACEHOLDER.finditer(source)) != Counter(normalize(m.group()) for m in COMMAND_NAME_PLACEHOLDER.finditer(target)):
                raise ValueError(f'Observed text literal command arguments changed: {source_id}')
            masked_source = list(source)
            roles = row.get('token_roles', {})
            if not isinstance(roles, dict) or set(roles) - {'ordinary_spans'}:
                raise ValueError(f'Invalid observed ordinary roles: {source_id}')
            spans = roles.get('ordinary_spans', [])
            if not isinstance(spans, list) or len(spans) > 8:
                raise ValueError(f'Invalid observed ordinary spans: {source_id}')
            occupied = set()
            for span in spans:
                if not isinstance(span, dict) or set(span) != {'start', 'end', 'text', 'role'}:
                    raise ValueError(f'Invalid observed ordinary span: {source_id}')
                start, end, literal = span['start'], span['end'], span['text']
                if type(start) is not int or type(end) is not int or not 0 <= start < end <= len(source) or source[start:end] != literal or occupied.intersection(range(start, end)):
                    raise ValueError(f'Stale observed ordinary span: {source_id}')
                if span['role'] == 'indefinite_article':
                    valid = literal == 'An' and start == 0 and bool(re.match(r' [a-z]', source[end:]))
                elif span['role'] == 'ordinary_word':
                    valid = literal in OBSERVED_ORDINARY_WORDS and (start == 0 or source[start - 1] not in ASCII_WORD) and (end == len(source) or source[end] not in ASCII_WORD)
                else: valid = False
                if not valid: raise ValueError(f'Unsupported observed ordinary role: {source_id}')
                occupied.update(range(start, end)); masked_source[start:end] = ' ' * (end - start)
            source_names, target_names = self.names.occurrences(''.join(masked_source)), self.names.occurrences(target)
            if any(target_names[name] != count for name, count in source_names.items()):
                raise ValueError(f'Observed text protected name changed: {source_id}')
            self.observed_text.append(row)
            self.propose(Unit(channel, speaker, source, target, keep_original, source_id), reviewed_name_source=''.join(masked_source))

    def add_observed_applicability(self, document: dict[str, Any]) -> None:
        """Review another presentation of an existing observed body, not new text.

        A recorded DESCRIPTION flag alone is insufficient. Each licensed context
        binds the already-approved source/target and a complete native replay.
        These rows remain outside the unguarded literal dictionary.
        """
        if document.get('schema_version') != 1 or not isinstance(document.get('entries'), list):
            raise ValueError('Invalid observed applicability schema')
        originals = {row['id']: row for row in self.observed_text}
        seen = set()
        seen_contexts = set()
        required_fields = {'tooltipTitle', 'tooltipKind', 'paragraphRole', 'location', 'slotMatches'}
        for row in document['entries']:
            if not isinstance(row, dict): raise ValueError('Invalid observed applicability row')
            rule_id = row.get('id')
            if not isinstance(rule_id, str) or not rule_id.startswith('observed-applicability/') or rule_id in seen:
                raise ValueError('Invalid or duplicate observed applicability ID')
            seen.add(rule_id)
            if row.get('approved') is not True or not self.candidate:
                self.skip('unapproved_or_non_candidate_observed_applicability', rule_id)
                continue
            original = originals.get(row.get('origin_source_id'))
            role = row.get('semantic_role')
            if not original or original.get('channel') != 'item' or original.get('role') != 'DESCRIPTION' or original.get('keep_original'):
                raise ValueError(f'Observed applicability origin is not reviewed item prose: {rule_id}')
            if row.get('from_channel') != 'item' or row.get('to_channel') != 'ui' or role not in {'DESCRIPTION', 'DESCRIPTION_LABEL'}:
                raise ValueError(f'Unsupported observed applicability scope/role: {rule_id}')
            source, target = original['en'], original['ja']
            if row.get('source_sha256') != sha256(source.encode('utf-8')) or row.get('target_sha256') != sha256(target.encode('utf-8')):
                raise ValueError(f'Stale observed applicability text: {rule_id}')
            evidence = original.get('evidence', {})
            if evidence.get('source_complete') is not True or evidence.get('recorded_role') != role:
                raise ValueError(f'Observed applicability role is not recorded: {rule_id}')
            requires = row.get('requires')
            if not isinstance(requires, dict) or set(requires) != required_fields or requires.get('tooltipKind') != 'UI' or requires.get('location') != 'CONTAINER' or requires.get('slotMatches') is not True or requires.get('paragraphRole') != role:
                raise ValueError(f'Incomplete observed applicability context: {rule_id}')
            title = requires.get('tooltipTitle')
            if not isinstance(title, str) or not title or normalize(title) != title or len(title) > 120:
                raise ValueError(f'Invalid observed applicability title: {rule_id}')
            if role == 'DESCRIPTION' and normalize(str(evidence.get('item', ''))) != title:
                raise ValueError(f'Observed body changes its original item context: {rule_id}')
            if role == 'DESCRIPTION_LABEL' and not re.fullmatch(r'[A-Z][A-Za-z -]{2,50}:', source):
                raise ValueError(f'Observed label is not an independent section: {rule_id}')
            context_key = (row['to_channel'], source, json_bytes(requires))
            if context_key in seen_contexts:
                raise ValueError(f'Duplicate observed applicability context: {rule_id}')
            seen_contexts.add(context_key)
            native = row.get('native_evidence')
            if not isinstance(native, dict) or row.get('native_evidence_sha256') != sha256(json_bytes(native)):
                raise ValueError(f'Stale observed applicability replay: {rule_id}')
            lines, styled, live = native.get('source_lines'), native.get('source_styled_lines'), native.get('context')
            if native.get('source_styled_complete') is not True or not isinstance(lines, list) or not lines or not all(isinstance(s, str) for s in lines) or normalize(' '.join(lines)) != source or native.get('source_lines_sha256') != sha256(json_bytes(lines)):
                raise ValueError(f'Incomplete observed applicability source: {rule_id}')
            if not isinstance(styled, list) or len(styled) != len(lines) or not isinstance(live, dict) or any(live.get(k) != v for k, v in requires.items()):
                raise ValueError(f'Observed applicability replay context mismatch: {rule_id}')
            for plain, runs in zip(lines, styled):
                if not isinstance(runs, list) or not runs or any(not isinstance(run, dict) or set(run) != {'text', 'color', 'bold', 'italic', 'underlined', 'strikethrough', 'obfuscated'} for run in runs):
                    raise ValueError(f'Invalid observed applicability native runs: {rule_id}')
                if any(not isinstance(run['text'], str) or run['color'] is not None and (type(run['color']) is not int or not 0 <= run['color'] <= 0xffffff) or any(type(run[k]) is not bool for k in ('bold', 'italic', 'underlined', 'strikethrough', 'obfuscated')) for run in runs) or ''.join(run['text'] for run in runs) != plain:
                    raise ValueError(f'Observed applicability native text mismatch: {rule_id}')
            if role == 'DESCRIPTION_LABEL' and len(lines) != 1:
                raise ValueError(f'Observed section label is not independent: {rule_id}')
            if not isinstance(row.get('reason'), str) or not row['reason'].strip():
                raise ValueError(f'Missing observed applicability review reason: {rule_id}')
            self.observed_applicability.append(row)

    def add_skill_bodies(self, document: dict[str, Any]) -> None:
        """Derive reviewed UI bodies from unchanged official skill-unlock pairs.

        The label and split are explicit source evidence, not a general license
        for common entries to become UI text or for arbitrary prefixes to vanish.
        """
        if document.get('schema_version') != 1 or not isinstance(document.get('entries'), list):
            raise ValueError('Invalid skill body supplement schema')
        seen = set()
        for row in document['entries']:
            if not isinstance(row, dict): raise ValueError('Invalid skill body row')
            source_id, original_id = row.get('id'), row.get('source_id')
            source, target, label, evidence = row.get('en'), row.get('ja'), row.get('label'), row.get('evidence')
            if not isinstance(source_id, str) or not source_id.startswith('derived/skill_body/') or source_id in seen or not isinstance(original_id, str) or not original_id.startswith('common/skill_unlock/'):
                raise ValueError('Invalid or duplicate skill body ID')
            seen.add(source_id)
            if row.get('channel') != 'ui' or row.get('role') != 'DESCRIPTION' or row.get('kind') != 'common':
                raise ValueError(f'Invalid skill body scope: {source_id}')
            if not isinstance(source, str) or not isinstance(target, str) or normalize(source) != source or normalize(target) != target or unsafe_reason(source) or unsafe_reason(target):
                raise ValueError(f'Unsafe skill body: {source_id}')
            if row.get('source_sha256') != sha256(source.encode('utf-8')) or row.get('target_sha256') != sha256(target.encode('utf-8')):
                raise ValueError(f'Stale skill body hash: {source_id}')
            if not isinstance(label, str) or not re.fullmatch(r'[A-Za-z][A-Za-z -]* [IVXLCDM]+', label):
                raise ValueError(f'Invalid skill label: {source_id}')
            if not isinstance(evidence, dict) or evidence.get('type') != 'source_pack_body_derivation' or evidence.get('source_id') != original_id or evidence.get('label') != label or evidence.get('source_separator') != '   ' or evidence.get('target_separator') != '\n':
                raise ValueError(f'Invalid skill body evidence: {source_id}')
            en, ja = evidence.get('full_en'), evidence.get('full_ja')
            if en != label + '   ' + source or ja != label + '\n' + target or evidence.get('full_en_sha256') != sha256(en.encode('utf-8')) or evidence.get('full_ja_sha256') != sha256(ja.encode('utf-8')):
                raise ValueError(f'Stale skill body boundary: {source_id}')
            if type(row.get('approved')) is not bool or not isinstance(row.get('reason'), str) or not row['reason'].strip():
                raise ValueError(f'Missing skill body review: {source_id}')
            if not row['approved'] or not self.candidate:
                self.skip('unapproved_or_non_candidate_skill_body', source_id)
                continue
            key = ('common', '', normalize(en))
            observed = self.observed_variants.get(key, {})
            if set(observed) != {(normalize(ja), False)} or original_id not in observed[(normalize(ja), False)]:
                raise ValueError(f'Skill source pair changed or not compiled: {source_id}')
            if not quantities_preserved(source, target) or glyph_signature(source) != glyph_signature(target):
                raise ValueError(f'Skill body fidelity changed: {source_id}')
            source_names, target_names = self.names.occurrences(source), self.names.occurrences(target)
            if any(target_names[name] != count for name, count in source_names.items()):
                raise ValueError(f'Skill body protected name changed: {source_id}')
            self.skill_bodies.append(row)
            self.propose(Unit('ui', '', source, target, False, source_id))

    def add_npc_phone_lines(self, document: dict[str, Any]) -> None:
        """Derive exact phone lines only from approved pairs explicitly phone-eligible in Wiki context."""
        if document.get('schema_version') != 1 or not isinstance(document.get('entries'), list):
            raise ValueError('Invalid NPC phone supplement schema')
        seen = set()
        for row in document['entries']:
            source_id = row.get('id')
            if not isinstance(source_id, str) or not source_id.startswith('reviewed/npc_phone/') or source_id in seen:
                raise ValueError('Invalid or duplicate NPC phone ID')
            seen.add(source_id)
            speaker, source, target = row.get('speaker'), row.get('en'), row.get('ja')
            bare_source, bare_target, bare_ids = row.get('bare_en'), row.get('bare_ja'), row.get('bare_source_ids')
            if row.get('channel') != 'npc' or not isinstance(speaker, str) or not speaker.strip():
                raise ValueError(f'Invalid NPC phone scope: {source_id}')
            if not all(isinstance(text, str) and normalize(text) == text and not unsafe_reason(text)
                       for text in (source, target, bare_source, bare_target)):
                raise ValueError(f'Unsafe NPC phone text: {source_id}')
            if bare_source.startswith('✆') or bare_target.startswith('✆') or source != '✆ ' + bare_source or target != '✆ ' + bare_target:
                raise ValueError(f'NPC phone prefix or body changed: {source_id}')
            for field, text in (('source_sha256', source), ('target_sha256', target),
                                ('bare_source_sha256', bare_source), ('bare_target_sha256', bare_target)):
                if row.get(field) != sha256(text.encode('utf8')):
                    raise ValueError(f'Stale NPC phone text hash: {source_id}')
            evidence = row.get('evidence')
            if not isinstance(evidence, dict) or evidence.get('kind') != 'wiki_abiphone_dialogue' or evidence.get('speaker') != speaker or evidence.get('source_complete') is not True:
                raise ValueError(f'Invalid NPC phone evidence scope: {source_id}')
            group, proof = evidence.get('wiki_group'), evidence.get('native_prefix')
            if not isinstance(group, dict) or not isinstance(group.get('source'), dict):
                raise ValueError(f'Invalid NPC phone Wiki group: {source_id}')
            info, conditions, original = group['source'], group.get('conditions'), group.get('extracted_text')
            if not isinstance(conditions, list) or not conditions or not all(isinstance(value, str) for value in conditions) or group.get('conditions_sha256') != sha256(json_bytes(conditions)):
                raise ValueError(f'Stale NPC phone conditions: {source_id}')
            if not any('(Can appear as [[Abiphone]] dialogue)' in condition for condition in conditions):
                raise ValueError(f'NPC dialogue not explicitly phone eligible: {source_id}')
            if not isinstance(original, str) or group.get('extracted_text_sha256') != sha256(original.encode('utf8')):
                raise ValueError(f'Stale NPC phone group text: {source_id}')
            if not re.fullmatch(r'[0-9a-f]{64}', str(info.get('sha256', ''))) or not isinstance(info.get('file'), str) or not info['file'] or not isinstance(info.get('url'), str) or not info['url'] or type(info.get('revision')) is not int or not isinstance(info.get('span'), list) or len(info['span']) != 2:
                raise ValueError(f'Invalid NPC phone Wiki provenance: {source_id}')
            if not all(type(value) is int for value in info['span']) or not 0 <= info['span'][0] < info['span'][1]:
                raise ValueError(f'Invalid NPC phone Wiki span: {source_id}')
            binding = {'source': info, 'conditions': conditions, 'extracted_text': original}
            if group.get('group_sha256') != sha256(json_bytes(binding)):
                raise ValueError(f'Stale NPC phone group context: {source_id}')
            index = group.get('extracted_line'); lines = original.splitlines()
            if type(index) is not int or not 0 <= index < len(lines):
                raise ValueError(f'Invalid NPC phone group line: {source_id}')
            parsed = NPC_PREFIX.fullmatch(lines[index].strip())
            if not parsed or normalize(parsed.group(2)) != speaker or normalize(parsed.group(3)) != bare_source:
                raise ValueError(f'NPC phone group speaker/body mismatch: {source_id}')
            if not isinstance(proof, dict) or proof.get('speaker') != speaker or proof.get('source_complete') is not True or not isinstance(proof.get('source'), str) or not proof['source'].startswith('✆ ') or proof.get('source_sha256') != sha256(proof['source'].encode('utf8')) or not re.fullmatch(r'[0-9a-f]{64}', str(proof.get('event_sha256', ''))) or type(proof.get('event_line')) is not int or proof['event_line'] < 1:
                raise ValueError(f'Invalid native NPC phone prefix proof: {source_id}')
            if not all(isinstance(proof.get(field), str) and proof[field] for field in ('log', 'time', 'version')):
                raise ValueError(f'Incomplete native NPC phone proof: {source_id}')
            runs = proof.get('source_styled_runs')
            if proof.get('source_styled_complete') is not True or not isinstance(runs, list) or not runs or not all(isinstance(run, dict) and isinstance(run.get('text'), str) for run in runs) or ''.join(run['text'] for run in runs) != proof['source'] or proof.get('source_styled_runs_sha256') != sha256(json_bytes(runs)):
                raise ValueError(f'Stale or incomplete NPC phone prefix styles: {source_id}')
            if not isinstance(row.get('reason'), str) or not row['reason'] or type(row.get('approved')) is not bool:
                raise ValueError(f'Missing NPC phone review: {source_id}')
            if not row['approved'] or not self.candidate:
                self.skip('unapproved_or_non_candidate_npc_phone_line', source_id); continue
            pair = (bare_target, False); key = ('npc', speaker, bare_source)
            if not isinstance(bare_ids, list) or not bare_ids or not all(isinstance(value, str) and value for value in bare_ids) or set(self.observed_variants.get(key, {})) != {pair} or set(self.proposals.get(key, {})) != {pair} or not set(bare_ids) <= self.proposals[key][pair]:
                raise ValueError(f'NPC phone bare pair changed, ambiguous or not compiled: {source_id}')
            phone_key = ('npc', speaker, source)
            if self.observed_variants.get(phone_key) and set(self.observed_variants[phone_key]) != {(target, False)}:
                raise ValueError(f'NPC phone specific translation already differs: {source_id}')
            self.propose(Unit('npc', speaker, source, target, False, source_id))
            if source_id not in self.proposals.get(phone_key, {}).get((target, False), set()):
                raise ValueError(f'NPC phone derivation failed fidelity checks: {source_id}')
            self.npc_phone_lines.append(row)

    def add_reviewed_numeric_families(self, document: dict[str, Any]) -> None:
        """Explicit numeric families, evidenced by unchanged pairs and captured text.

        No families are inferred from numbers. Every proposed source/target shape
        must have at least three unchanged reviewed pairs and a captured native
        example; all fixed words, punctuation, units and names remain literal.
        The separately reviewed Fire Veil damage shape also accepts historical
        tooltip evidence without claiming a new client capture or native styles.
        """
        if document.get('schema_version') != 1 or not isinstance(document.get('entries'), list):
            raise ValueError('Invalid reviewed numeric family schema')
        seen = set()
        for row in document['entries']:
            source_id = row.get('id')
            if not isinstance(source_id, str) or not source_id.startswith('reviewed/template/') or source_id in seen:
                raise ValueError('Invalid or duplicate reviewed numeric family ID')
            seen.add(source_id)
            if row.get('channels') != ['item', 'ui'] or row.get('variables') != [{'name': 'v0', 'type': 'number'}]:
                raise ValueError(f'Unsupported reviewed numeric family scope: {source_id}')
            source, target = row.get('en'), row.get('ja')
            if not isinstance(source, str) or not isinstance(target, str) or VARIABLE.findall(source) != ['v0'] or VARIABLE.findall(target) != ['v0']:
                raise ValueError(f'Invalid reviewed numeric family variables: {source_id}')
            family_kind = row.get('kind', 'percentage')
            if family_kind == 'percentage':
                if '{v0}%' not in source or '{v0}%' not in target:
                    raise ValueError(f'Non-percentage reviewed numeric family: {source_id}')
                value_pattern = r'[0-9]+(?:\.[0-9]+)?'
            elif family_kind == 'fire_veil_damage_per_second':
                if source != FIRE_VEIL_FAMILY_EN or target != FIRE_VEIL_FAMILY_JA:
                    raise ValueError(f'Unreviewed Fire Veil numeric family shape: {source_id}')
                value_pattern = UNSIGNED_DAMAGE_VALUE
            else:
                raise ValueError(f'Unsupported reviewed numeric family kind: {source_id}')
            if row.get('source_sha256') != sha256(source.encode('utf-8')):
                raise ValueError(f'Stale reviewed numeric family source: {source_id}')
            if type(row.get('approved')) is not bool or not isinstance(row.get('reason'), str) or not row['reason'].strip():
                raise ValueError(f'Missing reviewed numeric family decision: {source_id}')
            if not self.candidate or not row['approved']:
                self.skip('unapproved_or_non_candidate_numeric_family', source_id)
                continue
            samples = row.get('source_pairs')
            if not isinstance(samples, list) or len(samples) < 3:
                raise ValueError(f'Insufficient reviewed numeric family evidence: {source_id}')
            values = set()
            for sample in samples:
                if not isinstance(sample, dict):
                    raise ValueError(f'Invalid numeric family sample: {source_id}')
                value = sample.get('value')
                if not isinstance(value, str) or not re.fullmatch(value_pattern, value):
                    raise ValueError(f'Invalid numeric family sample value: {source_id}')
                values.add(Decimal(value.replace(',', '')) if family_kind == 'fire_veil_damage_per_second' else value)
                en, ja = source.replace('{v0}', value), target.replace('{v0}', value)
                if sample.get('source') != en or sample.get('target') != ja or sample.get('source_sha256') != sha256(en.encode('utf-8')) or sample.get('target_sha256') != sha256(ja.encode('utf-8')):
                    raise ValueError(f'Stale numeric family pair: {source_id}')
                key = ('item', '', en)
                if set(self.observed_variants.get(key, {})) != {(ja, False)} or sorted(self.proposals.get(key, {}).get((ja, False), set())) != sample.get('source_ids'):
                    raise ValueError(f'Numeric family evidence no longer matches source packs: {source_id}')
            if len(values) < 3: raise ValueError(f'Duplicate numeric family evidence: {source_id}')
            native = row.get('native_example', {})
            if not isinstance(native, dict):
                raise ValueError(f'Invalid observed numeric family example: {source_id}')
            value = native.get('value')
            if not isinstance(value, str) or not re.fullmatch(value_pattern, value):
                raise ValueError(f'Invalid native numeric family value: {source_id}')
            en = source.replace('{v0}', value)
            lines = native.get('source_lines')
            if native.get('source') != en or native.get('source_sha256') != sha256(en.encode('utf-8')) or not isinstance(lines, list) or not all(isinstance(line, str) for line in lines) or normalize(' '.join(lines)) != en or native.get('source_lines_sha256') != sha256(json_bytes(lines)):
                raise ValueError(f'Stale native numeric family evidence: {source_id}')
            if native.get('channel') != 'item' or native.get('role') != 'DESCRIPTION' or not re.fullmatch(r'[0-9a-f]{64}', str(native.get('event_sha256', ''))):
                raise ValueError(f'Invalid native numeric family scope: {source_id}')
            if family_kind == 'fire_veil_damage_per_second':
                if native.get('evidence_kind') != 'recorded_tooltip' or native.get('native_styles') is not False or native.get('source_complete') is not True:
                    raise ValueError(f'Fire Veil requires explicit historical tooltip evidence: {source_id}')
                snapshot_hash = native.get('snapshot_sha256', native.get('snapshot_hash'))
                if not re.fullmatch(r'[0-9a-f]{64}', str(snapshot_hash)) or ('snapshot_sha256' in native and 'snapshot_hash' in native and native['snapshot_sha256'] != native['snapshot_hash']):
                    raise ValueError(f'Invalid Fire Veil snapshot hash: {source_id}')
                if not isinstance(native.get('snapshot_file'), str) or not native['snapshot_file'].strip() or not isinstance(native.get('record_pointer'), str) or not re.fullmatch(r'/(?:0|[1-9][0-9]*)', native['record_pointer']):
                    raise ValueError(f'Invalid Fire Veil recorded tooltip pointer: {source_id}')
                start, end = native.get('line_start'), native.get('line_end')
                if type(start) is not int or type(end) is not int or start < 1 or end - start + 1 != len(lines):
                    raise ValueError(f'Invalid Fire Veil recorded line range: {source_id}')
                if any(field in native for field in ('time', 'log', 'version', 'event_line', 'screen_title', 'tooltip_id')):
                    raise ValueError(f'Historical Fire Veil example cannot claim native event metadata: {source_id}')
            synthetic = {'id': source_id, 'kind': 'item', 'role': 'description', 'en': source, 'ja': target,
                         'tokens': [{'token': '{v0}', 'count': 1, 'role': 'unresolved_source_parameter'}]}
            if not self.add_template(synthetic, False):
                raise ValueError(f'Reviewed numeric family failed normal template fidelity checks: {source_id}')
            self.reviewed_numeric_families.append(row)

    def add(self, entry: dict[str, Any]) -> None:
        self.input_count += 1
        source_id = entry.get("id")
        if not isinstance(source_id, str) or not source_id or len(source_id) > 512:
            raise ValueError("Every source row needs a nonempty id of at most 512 characters")
        status = entry.get("status", "")
        self.statuses[str(status)] += 1
        review = entry.get("source_review")
        review = review if isinstance(review, dict) else {}
        eligible = entry.get("runtime_eligible") is True
        validated = review.get("game_validated") is True
        self.flags[f"runtime_eligible={str(eligible).lower()},game_validated={str(validated).lower()}"] += 1
        # Status gates take precedence even over erroneously set approval flags.
        if status == "hold":
            self.skip("held_source", source_id)
            return
        keep_original = status == "excluded"
        if keep_original:
            note = entry.get("note", "")
            if not isinstance(note, str) or UNRELIABLE_EXCLUSION.search(note) or not PRESERVATION.search(note):
                self.skip("excluded_without_preservation_rationale", source_id)
                return
        elif status not in {"draft", "translated", "reviewed", "verified", "approved"} or not isinstance(entry.get("ja"), str) or not entry["ja"].strip():
            self.skip("untranslated_or_unknown_status", source_id)
            return
        if not self.candidate and not (eligible and validated):
            self.skip("not_game_validated_and_runtime_eligible", source_id)
            return
        if entry.get("kind") not in CHANNELS:
            self.skip("unknown_channel", source_id)
            return
        if not isinstance(entry.get("en"), str):
            self.skip("missing_source", source_id)
            return
        context = entry.get("context", {})
        context = context if isinstance(context, dict) else {}
        if any("player_chat" in str(context.get(key, "")).lower() for key in ("surface", "input_kind", "source_owner")):
            self.skip("player_chat_example", source_id)
            return
        # Token metadata can describe a placeholder even when the visible text
        # itself no longer makes the unresolved parameter obvious.
        if entry.get("tokens"):
            if not self.add_template(entry, keep_original):
                self.skip("unresolved_source_token_metadata", source_id)
            return
        target = entry["en"] if keep_original else entry["ja"]
        if entry["kind"] == "npc":
            self.add_npc(entry["en"], target, source_id, keep_original)
        else:
            for channel in runtime_channels(entry):
                self.propose(Unit(channel, "", entry["en"], target, keep_original, source_id))

    def add_template(self, entry: dict[str, Any], keep_original: bool) -> bool:
        """Only declared numeric/Roman holes; never infer a template from fixed numbers."""
        source_id = entry['id']
        def reject(reason: str) -> bool:
            self.template_rejections[reason].add(source_id)
            return False
        if keep_original or entry.get('kind') not in {'item', 'common'} or entry.get('role') not in {'ability_body', 'description'}:
            return reject('unsupported_template_context')
        source, target = normalize(entry['en']), normalize(entry['ja'])
        declared = entry['tokens']
        if not isinstance(declared, list) or not 1 <= len(declared) <= 8:
            return reject('variable_count')
        declared_tokens = []
        for token in declared:
            if not isinstance(token, dict) or token.get('role') != 'unresolved_source_parameter' or not VARIABLE.fullmatch(str(token.get('token', ''))):
                return reject('unsupported_variable_metadata')
            declared_tokens.append(token['token'])
            if token.get('count') != source.count(token['token']):
                return reject('variable_metadata_count')
        source_tokens = [m.group() for m in VARIABLE.finditer(source)]
        target_tokens = [m.group() for m in VARIABLE.finditer(target)]
        if set(source_tokens) != set(declared_tokens) or Counter(source_tokens) != Counter(target_tokens):
            return reject('variable_alignment')
        if len(source_tokens) > 16:
            return reject('variable_occurrence_limit')
        plain_source, plain_target = VARIABLE.sub('', source), VARIABLE.sub('', target)
        if unsafe_reason(plain_source) or unsafe_reason(plain_target):
            return reject('unsafe_template_text')
        if len(re.findall(r'[A-Za-z]{2,}',plain_source)) < 2 or len(plain_source) < 12:
            return reject('insufficient_literal_context')
        if not quantities_preserved(plain_source, plain_target):
            return reject('fixed_numeric_token_changed')
        if glyph_signature(plain_source) != glyph_signature(plain_target):
            return reject('protected_glyph_changed')
        source_names, target_names = self.names.occurrences(plain_source), self.names.occurrences(plain_target)
        if any(target_names[name] != count for name,count in source_names.items()):
            return reject('protected_name_changed')
        ordered = list(dict.fromkeys(source_tokens))
        variables=[]
        for index, token in enumerate(ordered):
            # Roman effect levels are explicit. Other holes only accept bounded
            # decimal values, so a player/item name cannot be bound as a number.
            preceding = source[:source.index(token)]
            kind = 'roman' if re.search(r'\bHaste\s*$', preceding) else 'number'
            variables.append((f'v{index}',kind))
        mapping = {token:'{'+variables[index][0]+'}' for index,token in enumerate(ordered)}
        source = VARIABLE.sub(lambda m:mapping[m.group()],source)
        target = VARIABLE.sub(lambda m:mapping[m.group()],target)
        for channel in runtime_channels(entry):
            key = (channel,'',source,tuple(variables))
            self.template_proposals[key][(target,False)].add(source_id)
        return True

    def add_npc(self, source: str, target: str, source_id: str, keep_original: bool) -> None:
        en_lines = FORMAT.sub("", source).replace("\r\n", "\n").replace("\r", "\n").split("\n")
        ja_lines = FORMAT.sub("", target).replace("\r\n", "\n").replace("\r", "\n").split("\n")
        if len(en_lines) != len(ja_lines):
            self.skip("npc_line_count_mismatch", source_id)
            return
        rows: list[tuple[str, str, str] | None] = []
        in_options = False
        for en, ja in zip(en_lines, ja_lines):
            en, ja = en.strip(), ja.strip()
            en_prefix, ja_prefix = NPC_PREFIX.fullmatch(en), NPC_PREFIX.fullmatch(ja)
            if en_prefix or ja_prefix:
                if not en_prefix or not ja_prefix or en_prefix.group(1) != ja_prefix.group(1) or normalize(en_prefix.group(2)) != normalize(ja_prefix.group(2)):
                    self.skip("npc_speaker_or_prefix_mismatch", source_id)
                    return
                rows.append((normalize(en_prefix.group(2)), en_prefix.group(3), ja_prefix.group(3)))
                in_options = False
            elif OPTION_PREFIX.fullmatch(en):
                if not JA_OPTION_PREFIX.fullmatch(ja):
                    self.skip("npc_option_alignment_mismatch", source_id)
                    return
                rows.append(("\u9078\u629e\u80a2", en, ja))
                in_options = True
            elif in_options and en and ja and not PLAYER_PREFIX.match(en) and not PLAYER_PREFIX.match(ja):
                rows.append(("\u9078\u629e\u80a2", en, ja))
            elif not en and not ja:
                rows.append(None)
                in_options = False
            else:
                self.skip("player_chat_example" if PLAYER_PREFIX.match(en) else "npc_unscoped_line", source_id)
                rows.append(None)
                in_options = False
        # A group and its lines are separate exact literals.  Grouping stops at
        # speaker changes, option sections, blank lines and unscoped examples.
        run: list[tuple[str, str, str]] = []

        def flush() -> None:
            if len(run) > 1:
                self.propose(Unit("npc", run[0][0], "\n".join(row[1] for row in run), "\n".join(row[2] for row in run), keep_original, source_id))
            run.clear()

        for row in rows:
            if row is None:
                flush()
                continue
            if run and run[0][0] != row[0]:
                flush()
            run.append(row)
            self.propose(Unit("npc", row[0], row[1], row[2], keep_original, source_id))
        flush()

    def propose(self, unit: Unit, *, reviewed_name_source: str | None = None) -> None:
        if unit.speaker:
            reason = unsafe_reason(unit.speaker)
            if reason:
                self.skip("unsafe_speaker", unit.source_id)
                return
        for text in (unit.source, unit.target):
            reason = unsafe_reason(text)
            if reason:
                self.skip(reason, unit.source_id)
                return
        source, target = normalize(unit.source), normalize(unit.target)
        if PLAYER_PREFIX.match(source):
            self.skip("player_chat_example", unit.source_id)
            return
        # A fidelity failure does not make a competing translation cease to
        # exist: black-list the exact key if another usable row disagrees.
        self.observed_variants[(unit.channel, unit.speaker, source)][(target, unit.keep_original)].add(unit.source_id)
        if rgb_signature(source) != rgb_signature(target):
            self.skip("protected_rgb_color_changed", unit.source_id)
            return
        if not quantities_preserved(source, target):
            self.skip("numeric_token_changed", unit.source_id)
            return
        if glyph_signature(source) != glyph_signature(target):
            self.skip("protected_glyph_changed", unit.source_id)
            return
        source_names = self.names.occurrences(source if reviewed_name_source is None else reviewed_name_source)
        target_names = self.names.occurrences(target)
        if any(target_names[name] != count for name, count in source_names.items()):
            self.skip("protected_name_changed", unit.source_id)
            return
        self.proposals[(unit.channel, unit.speaker, source)][(target, unit.keep_original)].add(unit.source_id)
        self.proposed_units += 1

    def finish(self, source_build_id: str) -> tuple[dict[str, Any], dict[str, Any]]:
        entries: list[dict[str, Any]] = []
        conflicts: list[dict[str, Any]] = []
        for (channel, speaker, source), observed in sorted(self.observed_variants.items()):
            if len(observed) > 1:
                conflicts.append({"channel": channel, "speaker": speaker, "source": source, "variants": [{"target": target, "keep_original": keep_original, "source_ids": sorted(ids)} for (target, keep_original), ids in sorted(observed.items())]})
                for ids in observed.values():
                    self.skipped["conflicting_translation"].update(ids)
                continue
            variants = self.proposals.get((channel, speaker, source))
            if not variants:
                continue
            (target, keep_original), source_ids = next(iter(variants.items()))
            entries.append({"channel": channel, "speaker": speaker, "source": source, "target": target, "keep_original": keep_original, "source_ids": sorted(source_ids)})
        tier = "candidate" if self.candidate else "verified"
        templates=[]
        template_conflicts=[]
        for (channel,speaker,source,variables),variants in sorted(self.template_proposals.items()):
            if len(variants)!=1:
                template_conflicts.append({'channel':channel,'source':source,'source_ids':sorted({i for ids in variants.values() for i in ids})})
                for ids in variants.values():
                    self.template_rejections['conflicting_template'].update(ids)
                continue
            (target,_),ids=next(iter(variants.items()))
            templates.append({'channel':channel,'speaker':speaker,'source':source,'target':target,
                              'variables':[{'name':name,'type':kind} for name,kind in variables],'source_ids':sorted(ids)})
        aliases=[]
        for entry in entries:
            for alias in VERIFIED_SOURCE_ALIASES.get(entry['source'],()):
                aliases.append({**entry,'source':alias})
        matching={'schema_version':1,'normalization':'ascii_case_single_period_stat_glyphs_v1',
                  'aliases':aliases,'templates':templates + (REVIEWED_UI_TEMPLATES if self.candidate else [])}
        # Removing a conflicting scoped row alone would let common/normalized/
        # template fallback apply an unapproved meaning. Ship the quarantine too.
        blocked_keys = [{field: conflict[field] for field in ('channel', 'speaker', 'source')}
                        for conflict in conflicts]
        def validate_review(source, target, keep, rule):
            if not isinstance(target, str) or unsafe_reason(target): return 'unsafe_review_target'
            if not isinstance(keep, bool) or keep and normalize(source) != normalize(target): return 'invalid_review_preservation'
            if not quantities_preserved(source, target): return 'review_numeric_token_changed'
            if glyph_signature(source) != glyph_signature(target): return 'review_protected_glyph_changed'
            # These spans are an explicit reviewed interpretation of this exact
            # source/evidence hash, never a global exception for a name such as An.
            roles = rule.get('token_roles', {})
            if not isinstance(roles, dict) or not set(roles) <= {'ordinary_spans', 'protected_terms'}: return 'invalid_review_token_roles'
            if not isinstance(roles.get('ordinary_spans', []), list) or len(roles.get('ordinary_spans', [])) > 8: return 'invalid_review_token_roles'
            if not isinstance(roles.get('protected_terms', []), list) or len(roles.get('protected_terms', [])) > 16: return 'invalid_review_token_roles'
            masked = list(source)
            occupied = set()
            for span in roles.get('ordinary_spans', []):
                if not isinstance(span, dict) or set(span) != {'start', 'end', 'text', 'role'}: return 'invalid_review_ordinary_span'
                start, end, literal, role = span['start'], span['end'], span['text'], span['role']
                if type(start) is not int or type(end) is not int or not 0 <= start < end <= len(source) or source[start:end] != literal: return 'review_span_source_mismatch'
                if occupied.intersection(range(start, end)): return 'overlapping_review_spans'
                if role == 'indefinite_article':
                    if start != 0 or literal != 'An' or not re.match(r' [a-z]', source[end:]): return 'invalid_review_article_role'
                elif role == 'input_label':
                    if literal != 'SNEAK' or not source[:start].endswith('Ability: Water Burst ') or roles.get('protected_terms') != ['Water Burst']: return 'invalid_review_input_role'
                elif role == 'truncated_interjection':
                    if source != 'Y-' or start != 0 or end != 1 or rule.get('speaker') != 'Ugo': return 'invalid_review_interjection_role'
                elif role == 'ordinary_word':
                    if literal not in OBSERVED_ORDINARY_WORDS or (start > 0 and source[start - 1] in ASCII_WORD) or (end < len(source) and source[end] in ASCII_WORD): return 'invalid_review_ordinary_word'
                else: return 'unsupported_review_token_role'
                occupied.update(range(start, end)); masked[start:end] = ' ' * (end - start)
            for term in roles.get('protected_terms', []):
                if not isinstance(term, str) or not term: return 'invalid_review_protected_term'
                pattern = re.compile(r'(?<![A-Za-z0-9_])' + re.escape(term) + r'(?![A-Za-z0-9_])')
                count = len(pattern.findall(source))
                if count == 0 or len(pattern.findall(target)) != count: return 'review_protected_part_changed'
            source_names, target_names = self.names.occurrences(''.join(masked)), self.names.occurrences(target)
            if any(target_names[name] != count for name, count in source_names.items()): return 'review_protected_name_changed'
            return None
        reviewed_rules, review_evidence, rejected_reviews = _reviews.compile_reviews(entries, conflicts, self.reviews, validate_review)
        reviewed = {'schema_version': 1, 'rules': reviewed_rules}
        scoped_rules = []
        scoped_rejections = []
        observed_by_id = {row['id']: row for row in self.observed_text}
        emitted_by_key = {(row['channel'], row['speaker'], row['source']): row for row in entries}
        for row in self.observed_applicability:
            original = observed_by_id[row['origin_source_id']]
            source, target, source_id = original['en'], original['ja'], original['id']
            emitted_origin = emitted_by_key.get(('item', '', source))
            if not emitted_origin or emitted_origin['target'] != target or emitted_origin['keep_original'] or source_id not in emitted_origin['source_ids']:
                scoped_rejections.append({'id': row['id'], 'reason': 'applicability_origin_unavailable'})
                continue
            # All input paths contribute to observed_variants. A late competing
            # translation cannot be bypassed by a separate guarded sidecar.
            conflict = any(len(self.observed_variants.get((channel, '', source), {})) > 1
                or any(candidate != (target, False) for candidate in self.observed_variants.get((channel, '', source), {}))
                for channel in ('item', 'ui', 'common'))
            if conflict:
                scoped_rejections.append({'id': row['id'], 'reason': 'conflicting_applicability_translation'})
                continue
            scoped_rules.append({'id': row['id'], 'from_channel': 'item', 'to_channel': 'ui', 'source': source,
                'target': target, 'source_sha256': row['source_sha256'], 'target_sha256': row['target_sha256'],
                'source_ids': [source_id], 'semantic_role': row['semantic_role'], 'requires': row['requires']})
        scoped = {'schema_version': 1, 'rules': sorted(scoped_rules, key=lambda row: row['id'])}
        # Every runtime-affecting document participates, including template-only edits.
        identity = {'schema_version': SCHEMA_VERSION, 'tier': tier, 'entries': entries,
                    'blocked_keys': blocked_keys, 'matching': matching, 'reviewed': reviewed,
                    'review_evidence': review_evidence, 'alignment_sha256': self.alignment_sha256,
                    'ordinary_tokens_sha256': self.ordinary_tokens_sha256,
                    'observed_ui': self.observed_ui,
                    'observed_text': self.observed_text,
                     'observed_applicability': self.observed_applicability,
                     'scoped_descriptions': scoped,
                    'skill_bodies': self.skill_bodies,
                    'npc_phone_lines': self.npc_phone_lines,
                    'reviewed_numeric_families': self.reviewed_numeric_families,
                    'runtime_applicability_policy': APPLICABILITY_POLICY}
        build_id = f"{source_build_id}-{tier}-{sha256(json_bytes(identity))[:16]}"
        dictionary = {"schema_version": SCHEMA_VERSION, "build_id": build_id, "tier": tier, "entries": entries,
                      "blocked_keys": blocked_keys, 'review_evidence': review_evidence}
        matching['build_id'] = build_id
        reviewed['build_id'] = build_id
        scoped['build_id'] = build_id
        report = {
            "schema_version": SCHEMA_VERSION,
            "build_id": build_id,
            "tier": tier,
            "actual_game_validation_performed": False,
            "source_adoption_flags_modified": False,
            "candidate_requires_runtime_opt_in": self.candidate,
            "notes": ["Exact/normalized keys and explicitly typed numeric/Roman templates only; no fuzzy matching.", "Candidate packaging is not source approval or evidence of live game validation.", "Skipped reason counts count distinct source IDs per reason and can overlap.", "NPC exact groups and their individual lines can share source IDs.", "Source kind remains provenance; approved tooltip roles are emitted for item and UI only."],
            "runtime_applicability_policy": APPLICABILITY_POLICY,
            "input_entry_count": self.input_count,
            "input_status_counts": dict(sorted(self.statuses.items())),
            "input_adoption_flag_counts": dict(sorted(self.flags.items())),
            "proposed_unit_count": self.proposed_units,
            "entry_count": len(entries),
            "entry_counts_by_channel": dict(sorted(Counter(e["channel"] for e in entries).items())),
            "keep_original_count": sum(e["keep_original"] for e in entries),
            "emitted_source_id_count": len({source_id for e in entries for source_id in e["source_ids"]}),
            "skipped_reason_counts": {reason: len(ids) for reason, ids in sorted(self.skipped.items())},
            "skipped_source_ids_by_reason": {reason: sorted(ids) for reason, ids in sorted(self.skipped.items())},
            "conflicting_key_count": len(conflicts),
            "blocked_key_count": len(blocked_keys),
            "conflicts": conflicts,
            "ordinary_policy_words_not_names": sorted(ORDINARY_POLICY_WORDS),
            "template_entry_count": len(templates),
            "template_source_id_count": len({i for t in templates for i in t['source_ids']}),
            "template_rejection_counts": {reason:len(ids) for reason,ids in sorted(self.template_rejections.items())},
            "template_rejected_source_ids": {reason:sorted(ids) for reason,ids in sorted(self.template_rejections.items())},
            "template_conflicts": template_conflicts,
            "verified_alias_count": len(aliases),
            "reviewed_ui_template_count": len(REVIEWED_UI_TEMPLATES) if self.candidate else 0,
            "matching_document": matching,
            'reviewed_document': reviewed,
            'scoped_descriptions_document': scoped,
            'scoped_description_rule_count': len(scoped_rules),
            'scoped_description_rejections': scoped_rejections,
            'reviewed_rule_count': len(reviewed_rules),
            'reviewed_contextual_rule_count': sum(bool(rule['requires']) for rule in reviewed_rules),
            'reviewed_rule_rejections': rejected_reviews,
            'reviewed_token_role_decisions': [{'id': rule['id'], 'source': rule['source'], 'token_roles': rule['token_roles']}
                for rule in self.reviews.get('rules', []) if rule.get('token_roles') and any(compiled['id'] == rule['id'] for compiled in reviewed_rules)],
            'alignment_sha256': self.alignment_sha256,
            'observed_ui_entry_count': len(self.observed_ui),
            'observed_ui_evidence': self.observed_ui,
            'observed_text_entry_count': len(self.observed_text),
            'observed_text_evidence': self.observed_text,
            'skill_body_entry_count': len(self.skill_bodies),
            'skill_body_evidence': self.skill_bodies,
            'npc_phone_line_count': len(self.npc_phone_lines),
            'npc_phone_line_evidence': self.npc_phone_lines,
            'reviewed_numeric_family_count': len(self.reviewed_numeric_families),
            'reviewed_numeric_family_evidence': self.reviewed_numeric_families,
            'ordinary_tokens_sha256': self.ordinary_tokens_sha256,
        }
        return dictionary, report


def compile_source(source_root: Path, *, candidate: bool = False, include_supplements: bool = True) -> tuple[dict[str, Any], dict[str, Any]]:
    manifest_path = source_root / "manifest.json"
    policy_path = source_root / "translation-policy.json"
    manifest_raw, policy_raw = manifest_path.read_bytes(), policy_path.read_bytes()
    manifest, policy = json.loads(manifest_raw), json.loads(policy_raw)
    source_build_id = manifest.get("build_id")
    if not isinstance(source_build_id, str) or not source_build_id:
        raise ValueError("Source manifest must contain a build_id")
    review_path = Path(__file__).with_name('dictionary-reviewed.json')
    reviews = json.loads(review_path.read_text(encoding='utf-8')) if review_path.exists() else None
    alignment_path = Path(__file__).resolve().parents[1] / 'src/main/resources/assets/skyblock_jp/dictionary/alignment.json'
    alignment_raw = alignment_path.read_bytes() if alignment_path.exists() else b''
    ordinary_tokens_path = alignment_path.with_name('ordinary-tokens.json')
    ordinary_tokens_raw = ordinary_tokens_path.read_bytes() if ordinary_tokens_path.exists() else b''
    compiler = Compiler(candidate=candidate, names=policy.get("names", {}), reviews=reviews,
                        alignment_sha256=sha256(alignment_raw), ordinary_tokens_sha256=sha256(ordinary_tokens_raw))
    hashes: list[dict[str, str]] = []
    ids: set[str] = set()
    packs = sorted((source_root / "translations").rglob("*.json"))
    if not packs:
        raise ValueError("No translations/*.json source packs found")
    for path in packs:
        raw = path.read_bytes()
        relative = path.relative_to(source_root).as_posix()
        hashes.append({"path": relative, "sha256": sha256(raw)})
        pack = json.loads(raw)
        if pack.get("schema_version") != SCHEMA_VERSION or not isinstance(pack.get("entries"), list):
            raise ValueError(f"Invalid source pack schema: {relative}")
        for entry in pack["entries"]:
            if entry.get("id") in ids:
                raise ValueError(f"Duplicate source ID: {entry.get('id')}")
            ids.add(entry.get("id"))
            compiler.add(entry)
    if manifest.get("entry_count") != compiler.input_count:
        raise ValueError(f"Source manifest count {manifest.get('entry_count')} does not match {compiler.input_count} records")
    observed_path = Path(__file__).with_name('dictionary-observed-ui.json')
    observed_raw = observed_path.read_bytes() if include_supplements and observed_path.exists() else b''
    if observed_raw:
        compiler.add_observed_ui(json.loads(observed_raw))
    observed_text_path = Path(__file__).with_name('dictionary-observed-text.json')
    observed_text_raw = observed_text_path.read_bytes() if include_supplements and observed_text_path.exists() else b''
    if observed_text_raw:
        compiler.add_observed_text(json.loads(observed_text_raw))
    observed_applicability_path = Path(__file__).with_name('dictionary-observed-applicability.json')
    observed_applicability_raw = observed_applicability_path.read_bytes() if include_supplements and observed_applicability_path.exists() else b''
    if observed_applicability_raw:
        compiler.add_observed_applicability(json.loads(observed_applicability_raw))
    skills_path = Path(__file__).with_name('dictionary-skill-bodies.json')
    skills_raw = skills_path.read_bytes() if include_supplements and skills_path.exists() else b''
    if skills_raw:
        compiler.add_skill_bodies(json.loads(skills_raw))
    families_path = Path(__file__).with_name('dictionary-numeric-families.json')
    families_raw = families_path.read_bytes() if include_supplements and families_path.exists() else b''
    if families_raw:
        compiler.add_reviewed_numeric_families(json.loads(families_raw))
    phone_path = Path(__file__).with_name('dictionary-npc-phone-lines.json')
    phone_raw = phone_path.read_bytes() if include_supplements and phone_path.exists() else b''
    if phone_raw:
        compiler.add_npc_phone_lines(json.loads(phone_raw))
    dictionary, report = compiler.finish(source_build_id)
    report["source_manifest_sha256"] = sha256(manifest_raw)
    report["source_translation_policy_sha256"] = sha256(policy_raw)
    report["source_packs"] = hashes
    report["source_packs_sha256"] = sha256(json_bytes(hashes))
    report["dictionary_sha256"] = sha256(json_bytes(dictionary))
    report["dictionary_bytes"] = len(json_bytes(dictionary))
    report['matching_sha256'] = sha256(json_bytes(report['matching_document']))
    report['matching_bytes'] = len(json_bytes(report['matching_document']))
    report['reviewed_sha256'] = sha256(json_bytes(report['reviewed_document']))
    report['reviewed_bytes'] = len(json_bytes(report['reviewed_document']))
    report['scoped_descriptions_sha256'] = sha256(json_bytes(report['scoped_descriptions_document']))
    report['scoped_descriptions_bytes'] = len(json_bytes(report['scoped_descriptions_document']))
    report['alignment_bytes'] = len(alignment_raw)
    report['observed_ui_source_sha256'] = sha256(observed_raw)
    report['observed_text_source_sha256'] = sha256(observed_text_raw)
    report['observed_applicability_source_sha256'] = sha256(observed_applicability_raw)
    report['skill_bodies_source_sha256'] = sha256(skills_raw)
    report['npc_phone_lines_source_sha256'] = sha256(phone_raw)
    report['reviewed_numeric_families_source_sha256'] = sha256(families_raw)
    report['ordinary_tokens_bytes'] = len(ordinary_tokens_raw)
    return dictionary, report


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-root", type=Path, required=True, help="Read-only workbench containing manifest.json and translations/")
    parser.add_argument("--output", type=Path, required=True, help="Destination literal dictionary JSON (never inside source-root)")
    parser.add_argument("--report", type=Path, required=True, help="Destination deterministic manifest/audit JSON")
    parser.add_argument("--candidate", action="store_true", help="Explicitly package reviewed drafts as opt-in candidates; does not approve them")
    parser.add_argument("--matching-output", type=Path, help="Optional aliases/templates resource (default candidate-matching.json beside output)")
    parser.add_argument('--reviewed-output', type=Path, help='Optional reviewed decisions resource (default candidate-reviewed.json beside output)')
    parser.add_argument('--scoped-descriptions-output', type=Path, help='Optional guarded observed presentation resource (default candidate-scoped-descriptions.json beside output)')
    args = parser.parse_args(argv)
    source_root = args.source_root.resolve()
    matching_output=args.matching_output or args.output.with_name('candidate-matching.json')
    reviewed_output=args.reviewed_output or args.output.with_name('candidate-reviewed.json')
    scoped_output=args.scoped_descriptions_output or args.output.with_name('candidate-scoped-descriptions.json')
    for destination in (args.output.resolve(), args.report.resolve(), matching_output.resolve(), reviewed_output.resolve(), scoped_output.resolve()):
        if destination == source_root or source_root in destination.parents:
            parser.error("Outputs must be outside the read-only source corpus")
    destinations = (args.output.resolve(), args.report.resolve(), matching_output.resolve(), reviewed_output.resolve(), scoped_output.resolve())
    if len(set(destinations)) != len(destinations):
        parser.error('All dictionary, manifest, matching, reviewed and scoped outputs must be different files')
    dictionary, report = compile_source(source_root, candidate=args.candidate)
    matching=report.pop('matching_document')
    reviewed=report.pop('reviewed_document')
    scoped=report.pop('scoped_descriptions_document')
    # Stage every complete JSON before publication; each replacement is atomic.
    # Publish the manifest last: an interrupted set cannot pass the build hash check.
    staged = []
    try:
        for path, value in ((args.output, dictionary), (matching_output, matching), (reviewed_output, reviewed), (scoped_output, scoped), (args.report, report)):
            path.parent.mkdir(parents=True, exist_ok=True)
            payload = json_bytes(value)
            with tempfile.NamedTemporaryFile(dir=path.parent, prefix=path.name + '.', suffix='.tmp', delete=False) as temp:
                staged.append((Path(temp.name), path))
                temp.write(payload)
                temp.flush()
                os.fsync(temp.fileno())
        for temporary, destination in staged:
            os.replace(temporary, destination)
    finally:
        for temporary, _ in staged:
            temporary.unlink(missing_ok=True)
    print(json.dumps({"build_id": dictionary["build_id"], "tier": dictionary["tier"], "entries": len(dictionary["entries"]), "bytes": report["dictionary_bytes"], "sha256": report["dictionary_sha256"], "conflicting_keys": report["conflicting_key_count"]}, ensure_ascii=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())

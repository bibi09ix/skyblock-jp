"""Portable, deterministic editorial source snapshots. No network or game access.

The archive contains source packs and their per-entry references, not logs,
credentials, translation caches, or workbench history. Unpack it before editing.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import tempfile
import unicodedata
import zipfile

MAX_FILES = 5000
MAX_TOTAL_BYTES = 512 * 1024 * 1024
MAX_FILE_BYTES = 16 * 1024 * 1024
MAX_ARCHIVE_BYTES = 128 * 1024 * 1024
MAX_SIDECAR_BYTES = 8 * 1024 * 1024
PRIVATE = re.compile(r'(?i)(?:[a-z]:[\\/](?:Users|Minecraft)[\\/]|/(?:Users|home)/|\\\\[^\\\s]+\\)')
SECRET = re.compile(r'(?:gsk_[A-Za-z0-9_-]{8,}|AIza[A-Za-z0-9_-]{12,}|sk-proj-[A-Za-z0-9_-]{8,}|sk-[A-Za-z0-9_-]{20,}|Bearer\s+[A-Za-z0-9._-]{10,})', re.IGNORECASE)
RESERVED = re.compile(r'(?i)^(?:con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\.|$)')


def digest(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def file_digest(path: Path) -> str:
    value = hashlib.sha256()
    with path.open('rb') as source:
        for chunk in iter(lambda: source.read(65536), b''): value.update(chunk)
    return value.hexdigest()


def json_bytes(value) -> bytes:
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + '\n').encode('utf-8')


def allowed(name: str) -> bool:
    path = PurePosixPath(name)
    return (not path.is_absolute() and '\\' not in name and ':' not in name
            and all(part not in {'.', '..', ''} for part in name.split('/'))
            and all(not part.endswith(('.', ' ')) and not RESERVED.match(part)
                    and not any(ord(c) < 32 or c in '<>"|?*' for c in part)
                    for part in name.split('/'))
            and (name in {'manifest.json', 'translation-policy.json'}
                 or name.startswith('translations/') and name.endswith('.json')))


def safe_text(raw: bytes, label: str) -> dict:
    doc = json.loads(raw)
    # Scan decoded strings too, so JSON escapes cannot hide local paths/keys.
    strings = []
    def walk(value):
        if isinstance(value, str): strings.append(value)
        elif isinstance(value, dict):
            for key, item in value.items(): walk(key); walk(item)
        elif isinstance(value, list):
            for item in value: walk(item)
    walk(doc)
    if any(PRIVATE.search(s) or SECRET.search(s) for s in strings):
        raise ValueError(f'Private path or credential-shaped value in {label}')
    if not isinstance(doc, dict): raise ValueError(f'Expected object: {label}')
    return doc


def export(root: Path, output: Path, corrections: dict | None = None) -> dict:
    if output.is_symlink() or output.with_suffix('.manifest.json').is_symlink():
        raise ValueError('Source snapshot outputs must not be symlinks')
    root, output = root.resolve(), output.resolve()
    if output == root or root in output.parents:
        raise ValueError('Snapshot output must be outside the editable source root')
    if any((root / name).is_symlink() for name in ('manifest.json', 'translation-policy.json')):
        raise ValueError('Source files must not be symlinks')
    original_manifest = (root / 'manifest.json').read_bytes()
    original = json.loads(original_manifest)
    manifest = {k: original[k] for k in ('schema_version', 'build_id', 'entry_count')}
    origin_hash = original.get('source_workbench_manifest_sha256', digest(original_manifest))
    if not re.fullmatch(r'[0-9a-f]{64}', str(origin_hash)):
        raise ValueError('Invalid source manifest provenance hash')
    manifest['source_workbench_manifest_sha256'] = origin_hash
    manifest['scope_note'] = 'Editorial source snapshot; runtime eligibility and game validation flags are preserved.'
    patches = {row['id']: row for row in (corrections or {}).get('entries', [])}
    if len(patches) != len((corrections or {}).get('entries', [])):
        raise ValueError('Duplicate editorial correction ID')
    history = original.get('editorial_corrections', [])
    if not isinstance(history, list) or not all(isinstance(s, str) for s in history):
        raise ValueError('Invalid editorial correction history')
    manifest['editorial_corrections'] = sorted(set(history) | set(patches))
    applied = set()
    payload = {'manifest.json': json_bytes(manifest),
               'translation-policy.json': (root / 'translation-policy.json').read_bytes()}
    entry_count = 0
    for path in sorted((root / 'translations').rglob('*.json')):
        if path.is_symlink(): raise ValueError('Source files must not be symlinks')
        name = path.relative_to(root).as_posix()
        raw = path.read_bytes()
        pack = safe_text(raw, name)
        changed = False
        for entry in pack.get('entries', []):
            entry_count += 1
            patch = patches.get(entry.get('id'))
            if patch is None: continue
            if (patch.get('approved') is not True or not patch.get('reason')
                or digest(entry['en'].encode('utf-8')) != patch.get('source_sha256')
                or entry.get('status') != patch.get('previous_status')
                or entry.get('ja', '') != patch.get('previous_ja')):
                raise ValueError(f'Stale/unapproved editorial correction: {entry.get("id")}')
            if not patch.get('ja', '').strip(): raise ValueError('Correction needs a translation')
            entry['ja'], entry['status'], entry['note'] = patch['ja'], 'draft', patch['reason']
            applied.add(entry['id']); changed = True
        payload[name] = json_bytes(pack) if changed else raw
    if applied != set(patches): raise ValueError('Editorial correction source missing')
    if entry_count != manifest['entry_count']: raise ValueError('Source count mismatch')
    if len(payload) > MAX_FILES or sum(map(len, payload.values())) > MAX_TOTAL_BYTES:
        raise ValueError('Source snapshot exceeds limits')
    if len({unicodedata.normalize('NFC', n).casefold() for n in payload}) != len(payload):
        raise ValueError('Source members collide on a case-insensitive filesystem')
    for name, raw in payload.items():
        if not allowed(name) or len(raw) > MAX_FILE_BYTES: raise ValueError('Invalid source member')
        safe_text(raw, name)
    output.parent.mkdir(parents=True, exist_ok=True)
    staged = None
    try:
        with tempfile.NamedTemporaryFile(dir=output.parent, prefix=output.name+'.', suffix='.tmp', delete=False) as stream:
            staged = Path(stream.name)
        with zipfile.ZipFile(staged, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as z:
            for name, raw in sorted(payload.items()):
                info = zipfile.ZipInfo(name, (2026, 1, 1, 0, 0, 0))
                # ZipInfo's default creator OS differs between Windows and Linux.
                info.create_system = 3
                info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = (stat.S_IFREG | 0o644) << 16
                z.writestr(info, raw, compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)
        if staged.stat().st_size > MAX_ARCHIVE_BYTES:
            raise ValueError('Compressed source snapshot exceeds limits')
        os.replace(staged, output)
    finally:
        if staged is not None: staged.unlink(missing_ok=True)
    result = {'schema_version': 1, 'source_build_id': manifest['build_id'],
              'entry_count': entry_count, 'archive_sha256': file_digest(output),
              'archive_bytes': output.stat().st_size, 'applied_corrections': manifest['editorial_corrections'],
              'members': {n: {'sha256': digest(b), 'bytes': len(b)} for n, b in sorted(payload.items())}}
    output.with_suffix('.manifest.json').write_bytes(json_bytes(result))
    return result


def unpack(archive: Path, destination: Path) -> Path:
    archive, destination = archive.resolve(), destination.resolve()
    if destination.exists() and any(destination.iterdir()):
        raise ValueError('Unpack destination must be empty')
    sidecar = archive.with_suffix('.manifest.json')
    if archive.stat().st_size > MAX_ARCHIVE_BYTES or sidecar.stat().st_size > MAX_SIDECAR_BYTES:
        raise ValueError('Source snapshot input exceeds limits')
    doc = json.loads(sidecar.read_bytes())
    if (doc.get('schema_version') != 1 or file_digest(archive) != doc.get('archive_sha256')
        or archive.stat().st_size != doc.get('archive_bytes')):
        raise ValueError('Source snapshot archive hash/size mismatch')
    # Validate every member before writing any output, including unsafe names,
    # duplicate names, symlinks, total size and decompressed content hashes.
    payload = {}
    with zipfile.ZipFile(archive) as z:
        entries = z.infolist()
        if len(entries) > MAX_FILES or sum(i.file_size for i in entries) > MAX_TOTAL_BYTES:
            raise ValueError('Source snapshot exceeds limits')
        if len({i.filename for i in entries}) != len(entries): raise ValueError('Duplicate source member')
        if len({unicodedata.normalize('NFC', i.filename).casefold() for i in entries}) != len(entries):
            raise ValueError('Source members collide on a case-insensitive filesystem')
        if {i.filename for i in entries} != set(doc.get('members', {})):
            raise ValueError('Source snapshot member inventory mismatch')
        for item in entries:
            name = item.filename
            if (not allowed(name) or item.file_size > MAX_FILE_BYTES
                or stat.S_ISLNK(item.external_attr >> 16)):
                raise ValueError('Unsafe source snapshot member')
            raw = z.read(item)
            expected = doc['members'][name]
            if len(raw) != expected['bytes'] or digest(raw) != expected['sha256']:
                raise ValueError('Source snapshot member hash/size mismatch')
            safe_text(raw, name)
            payload[name] = raw
    if not {'manifest.json', 'translation-policy.json'} <= payload.keys():
        raise ValueError('Incomplete source snapshot')
    destination.mkdir(parents=True, exist_ok=True)
    for name, raw in payload.items():
        path = destination / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(raw)
    return destination


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    subs = parser.add_subparsers(dest='command', required=True)
    pack = subs.add_parser('export'); pack.add_argument('--source-root', type=Path, required=True)
    pack.add_argument('--output', type=Path, required=True); pack.add_argument('--corrections', type=Path)
    extract = subs.add_parser('unpack'); extract.add_argument('--snapshot', type=Path, required=True)
    extract.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.command == 'export':
        patches = json.loads(args.corrections.read_bytes()) if args.corrections else None
        result = export(args.source_root, args.output, patches)
        print(json.dumps({k: v for k, v in result.items() if k != 'members'}))
    else:
        unpack(args.snapshot, args.output); print('Verified source snapshot unpacked')


if __name__ == '__main__': main()

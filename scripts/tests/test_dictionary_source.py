"""Boundary and editing-contract tests for portable source archives."""
from __future__ import annotations

import contextlib
import importlib.util
import io
import json
from pathlib import Path
import stat
import sys
import tempfile
import unittest
from unittest.mock import patch
import warnings
import zipfile


SCRIPTS = Path(__file__).resolve().parents[1]


def load_script(name):
    spec = importlib.util.spec_from_file_location(name, SCRIPTS / (name + '.py'))
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


source = load_script('dictionary_source')
compiler = load_script('compile_dictionary')


class DictionarySourceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='skyblock-source-test-')
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.root = self.base / 'editable'
        (self.root / 'translations').mkdir(parents=True)
        self.entry = {
            'id': 'item/fixture/one', 'kind': 'item', 'en': 'Grants +5 Mining Speed.',
            'ja': 'Mining Speedが+5増加する。', 'status': 'draft', 'note': '',
            'tokens': [], 'runtime_eligible': False,
            'source_review': {'game_validated': False},
            'source_refs': [{'url': 'https://example.invalid/public-source', 'revision': 1}],
        }
        self.write_json(self.root / 'manifest.json', {
            'schema_version': 1, 'build_id': 'fixture-build', 'entry_count': 1,
        })
        self.write_json(self.root / 'translation-policy.json', {'names': {}})
        self.pack = self.root / 'translations/pack.json'
        self.write_json(self.pack, {'schema_version': 1, 'entries': [self.entry]})
        self.archive = self.base / 'snapshot.zip'

    def write_json(self, path, value):
        path.write_bytes(source.json_bytes(value))

    def test_archive_creator_os_is_fixed_instead_of_using_platform_defaults(self):
        original_info = source.zipfile.ZipInfo
        class WindowsDefaultInfo(original_info):
            def __init__(self, *args, **kwargs):
                super().__init__(*args, **kwargs)
                self.create_system = 0
        with patch.object(source.zipfile, 'ZipInfo', WindowsDefaultInfo):
            source.export(self.root, self.archive)
        with zipfile.ZipFile(self.archive) as archive:
            self.assertEqual({3}, {member.create_system for member in archive.infolist()})

    def test_compressed_input_limit_is_checked_before_loading_or_extracting(self):
        source.export(self.root, self.archive)
        with patch.object(source, 'MAX_ARCHIVE_BYTES', self.archive.stat().st_size - 1):
            with self.assertRaises(ValueError): source.unpack(self.archive, self.base / 'too-large')
        self.assertFalse((self.base / 'too-large').exists())

    def test_sidecar_input_limit_is_checked_before_loading_or_extracting(self):
        source.export(self.root, self.archive)
        with patch.object(source, 'MAX_SIDECAR_BYTES', 1):
            with self.assertRaises(ValueError): source.unpack(self.archive, self.base / 'large-sidecar')
        self.assertFalse((self.base / 'large-sidecar').exists())

    def make_archive(self, members):
        """Build a self-consistent sidecar so each test reaches member validation."""
        members = [
            ('manifest.json', source.json_bytes({'schema_version': 1, 'entry_count': 1}), None),
            ('translation-policy.json', source.json_bytes({'names': {}}), None),
        ] + members
        with warnings.catch_warnings():
            warnings.simplefilter('ignore', UserWarning)
            with zipfile.ZipFile(self.archive, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
                for name, raw, mode in members:
                    info = zipfile.ZipInfo(name)
                    info.external_attr = ((mode or (stat.S_IFREG | 0o644)) << 16)
                    archive.writestr(info, raw)
        self.write_json(self.archive.with_suffix('.manifest.json'), {
            'schema_version': 1, 'archive_sha256': source.digest(self.archive.read_bytes()),
            'archive_bytes': self.archive.stat().st_size,
            'members': {name: {'bytes': len(raw), 'sha256': source.digest(raw)}
                        for name, raw, _ in members},
        })

    def reject_without_partial_output(self, members, limit=None):
        self.make_archive(members)
        destination = self.base / 'unpacked'
        if limit:
            with patch.object(source, limit[0], limit[1]):
                with self.assertRaises(ValueError): source.unpack(self.archive, destination)
        else:
            with self.assertRaises(ValueError): source.unpack(self.archive, destination)
        self.assertFalse(destination.exists(), 'reject every member before writing output')

    def test_export_is_deterministic_and_does_not_modify_editable_source(self):
        before = {p.relative_to(self.root): p.read_bytes() for p in self.root.rglob('*') if p.is_file()}
        first = source.export(self.root, self.archive)
        other = self.base / 'other.zip'
        second = source.export(self.root, other)
        self.assertEqual(self.archive.read_bytes(), other.read_bytes())
        self.assertEqual(first, second)
        self.assertEqual(before, {p.relative_to(self.root): p.read_bytes()
                                 for p in self.root.rglob('*') if p.is_file()})

    def test_no_edit_unpack_and_reexport_preserves_snapshot_bytes(self):
        source.export(self.root, self.archive)
        original = self.archive.read_bytes()
        extracted = source.unpack(self.archive, self.base / 'unpacked')
        other = self.base / 'roundtrip.zip'
        source.export(extracted, other)
        self.assertEqual(original, other.read_bytes(), 'unmodified source must not drift its provenance hash')

    def test_unpack_retains_references_validation_flags_and_editorial_text(self):
        source.export(self.root, self.archive)
        extracted = source.unpack(self.archive, self.base / 'unpacked')
        actual = json.loads((extracted / 'translations/pack.json').read_bytes())['entries'][0]
        self.assertEqual(self.entry, actual)

    def test_an_editorial_correction_requires_the_exact_pre_edit_source_and_status(self):
        correction = {
            'id': self.entry['id'], 'approved': True, 'reason': 'Reviewed text correction',
            'source_sha256': source.digest(self.entry['en'].encode()),
            'previous_status': self.entry['status'], 'previous_ja': self.entry['ja'],
            'ja': '+5 Mining Speedを得る。',
        }
        result = source.export(self.root, self.archive, {'entries': [correction]})
        self.assertEqual([self.entry['id']], result['applied_corrections'])
        with zipfile.ZipFile(self.archive) as archive:
            actual = json.loads(archive.read('translations/pack.json'))['entries'][0]
        self.assertEqual(correction['ja'], actual['ja'])
        self.assertEqual(self.entry['source_refs'], actual['source_refs'])
        self.assertFalse(actual['runtime_eligible'])
        self.assertFalse(actual['source_review']['game_validated'])
        correction['previous_ja'] = 'outdated'
        with self.assertRaises(ValueError):
            source.export(self.root, self.base / 'stale.zip', {'entries': [correction]})

    def test_hash_mismatch_rejects_without_writing(self):
        source.export(self.root, self.archive)
        manifest = self.archive.with_suffix('.manifest.json')
        doc = json.loads(manifest.read_bytes())
        doc['members']['translations/pack.json']['sha256'] = '0' * 64
        self.write_json(manifest, doc)
        destination = self.base / 'unpacked'
        with self.assertRaises(ValueError): source.unpack(self.archive, destination)
        self.assertFalse(destination.exists())

    def test_archive_hash_mismatch_rejects_without_writing(self):
        source.export(self.root, self.archive)
        self.archive.write_bytes(self.archive.read_bytes() + b'tampered')
        with self.assertRaises(ValueError): source.unpack(self.archive, self.base / 'unpacked')
        self.assertFalse((self.base / 'unpacked').exists())

    def test_extra_member_without_manifest_entry_is_rejected(self):
        source.export(self.root, self.archive)
        with zipfile.ZipFile(self.archive, 'a') as archive:
            archive.writestr('translations/extra.json', '{}')
        doc = json.loads(self.archive.with_suffix('.manifest.json').read_bytes())
        doc['archive_sha256'] = source.digest(self.archive.read_bytes())
        doc['archive_bytes'] = self.archive.stat().st_size
        self.write_json(self.archive.with_suffix('.manifest.json'), doc)
        with self.assertRaises(ValueError): source.unpack(self.archive, self.base / 'unpacked')
        self.assertFalse((self.base / 'unpacked').exists())

    def test_duplicate_member_is_rejected_before_any_write(self):
        self.reject_without_partial_output([
            ('translations/one.json', b'{}', None), ('translations/one.json', b'{}', None)])

    def test_case_colliding_members_are_rejected_for_portable_windows_output(self):
        self.reject_without_partial_output([
            ('translations/One.json', b'{}', None), ('translations/one.json', b'{}', None)])

    def test_unicode_equivalent_members_are_not_written_as_different_files(self):
        self.reject_without_partial_output([
            ('translations/caf\u00e9.json', b'{}', None), ('translations/cafe\u0301.json', b'{}', None)])

    def test_symlink_member_is_rejected(self):
        self.reject_without_partial_output([('translations/one.json', b'{}', stat.S_IFLNK | 0o777)])

    def test_unsafe_member_names_are_rejected_before_any_write(self):
        for name in ('translations/../escape.json', '/translations/one.json',
                     'translations\\one.json', 'translations/a:b.json',
                     'translations/./one.json', 'translations//one.json',
                     'translations/NUL.json', 'translations/CON/one.json',
                     'translations/sub./one.json', 'translations/sub /one.json'):
            with self.subTest(name=name):
                self.reject_without_partial_output([(name, b'{}', None)])

    def test_member_count_limit_precedes_output(self):
        self.reject_without_partial_output([('translations/one.json', b'{}', None)], ('MAX_FILES', 2))

    def test_total_uncompressed_limit_precedes_output(self):
        self.reject_without_partial_output([('translations/one.json', b'{}', None)], ('MAX_TOTAL_BYTES', 1))

    def test_individual_member_limit_precedes_output(self):
        self.reject_without_partial_output([('translations/one.json', b'{}', None)], ('MAX_FILE_BYTES', 1))

    def test_decoded_private_values_cannot_hide_behind_json_escaping(self):
        # These are synthetic canaries, never user credentials.
        for value in ('C:\\Users\\fixture\\config.json', '/home/fixture/private',
                      '\\\\fixture-server\\private\\config.json',
                      'gsk_TESTONLY123456789', 'bearer TESTONLY123456789',
                      'sk-proj-TESTONLY12345678901234567890'):
            with self.subTest(value=value):
                raw = json.dumps({'description': value}, ensure_ascii=True).encode()
                with self.assertRaises(ValueError): source.safe_text(raw, 'synthetic fixture')

    def test_export_rejects_a_symlinked_pack_instead_of_following_it(self):
        target = self.base / 'outside.json'
        target.write_bytes(self.pack.read_bytes())
        self.pack.unlink()
        try:
            self.pack.symlink_to(target)
        except OSError as error:
            self.skipTest('Platform does not allow symlinks: ' + type(error).__name__)
        with self.assertRaises(ValueError): source.export(self.root, self.archive)
        self.assertFalse(self.archive.exists())

    def test_source_symlink_guard_runs_on_platforms_without_symlink_privileges(self):
        with patch.object(Path, 'is_symlink', lambda path: path == self.pack):
            with self.assertRaises(ValueError): source.export(self.root, self.archive)
        self.assertFalse(self.archive.exists())

    def test_failed_export_preserves_an_existing_snapshot_and_sidecar(self):
        source.export(self.root, self.archive)
        original_zip = self.archive.read_bytes()
        sidecar = self.archive.with_suffix('.manifest.json')
        original_sidecar = sidecar.read_bytes()
        self.write_json(self.root / 'translation-policy.json', {
            'names': {}, 'unsafe': 'gsk_TESTONLY123456789',
        })
        with self.assertRaises(ValueError): source.export(self.root, self.archive)
        self.assertEqual(original_zip, self.archive.read_bytes())
        self.assertEqual(original_sidecar, sidecar.read_bytes())

    def test_snapshot_cli_compiles_verified_extraction_and_cleans_the_temporary_source(self):
        source.export(self.root, self.archive)
        extracted_roots = []

        def compile_verified(root, *, candidate):
            self.assertTrue(candidate)
            self.assertTrue((root / 'translations/pack.json').is_file())
            self.assertEqual(self.entry, json.loads((root / 'translations/pack.json').read_bytes())['entries'][0])
            extracted_roots.append(root)
            return ({'build_id': 'test-candidate', 'tier': 'candidate', 'entries': []}, {
                'matching_document': {'templates': []}, 'reviewed_document': {'rules': []},
                'scoped_descriptions_document': {'rules': []},
                'dictionary_bytes': 0, 'dictionary_sha256': '0' * 64, 'conflicting_key_count': 0,
            })

        report = self.base / 'compiled.manifest.json'
        with patch.object(compiler, 'compile_source', side_effect=compile_verified):
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(0, compiler.main([
                    '--source-snapshot', str(self.archive), '--candidate',
                    '--output', str(self.base / 'compiled.json'), '--report', str(report),
                ]))
        self.assertEqual(1, len(extracted_roots))
        self.assertFalse(extracted_roots[0].exists())
        self.assertEqual(source.digest(self.archive.read_bytes()),
                         json.loads(report.read_bytes())['public_source_snapshot_sha256'])

    def test_cli_cannot_replace_snapshot_or_its_integrity_sidecar(self):
        source.export(self.root, self.archive)
        original_zip = self.archive.read_bytes()
        sidecar = self.archive.with_suffix('.manifest.json')
        original_sidecar = sidecar.read_bytes()
        for source_file in (self.archive, sidecar):
            for option in ('--output', '--report', '--matching-output', '--reviewed-output', '--scoped-descriptions-output'):
                with self.subTest(source_file=source_file.name, option=option):
                    arguments = ['--source-snapshot', str(self.archive), '--candidate',
                                 '--output', str(self.base / 'compiled.json'),
                                 '--report', str(self.base / 'compiled.manifest.json')]
                    if option in ('--output', '--report'):
                        arguments[arguments.index(option) + 1] = str(source_file)
                    else:
                        arguments += [option, str(source_file)]
                    with patch.object(compiler, 'compile_source', side_effect=AssertionError('source collision reached compilation')):
                        with contextlib.redirect_stderr(io.StringIO()):
                            with self.assertRaises(SystemExit): compiler.main(arguments)
        self.assertEqual(original_zip, self.archive.read_bytes())
        self.assertEqual(original_sidecar, sidecar.read_bytes())


if __name__ == '__main__':
    unittest.main()

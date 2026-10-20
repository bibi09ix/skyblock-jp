"""Build release metadata without copying local config, logs, credentials or caches."""
import argparse
import hashlib
import json
import re
import shutil
import zipfile
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('--repository', required=True)
args = parser.parse_args()
if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9-]*/[A-Za-z0-9_][A-Za-z0-9_.-]*', args.repository):
    raise SystemExit('Use owner/repository')
root = Path(__file__).resolve().parents[1]
version = re.search(r"^version = '([0-9]+\.[0-9]+\.[0-9]+)'", (root / 'build.gradle').read_text(), re.M)[1]
jar = root / f'build/libs/skyblock-jp-{version}.jar'
with zipfile.ZipFile(jar) as z:
    metadata = json.loads(z.read('fabric.mod.json'))
    assert metadata['id'] == 'skyblock_jp' and metadata['version'] == version
    assert metadata['depends']['minecraft'] == '~26.2'
    embedded = z.read('skyblock-jp-update.properties').decode().strip()
    assert embedded == 'repository=' + args.repository, 'Rebuild with -PupdateRepository=owner/repository'
out = root / 'release-assets'
out.mkdir(exist_ok=True)
shutil.copy2(jar, out / jar.name)
manifest = dict(version=version, minecraft='26.2',
    url=f'https://github.com/{args.repository}/releases/download/v{version}/{jar.name}',
    sha256=hashlib.sha256(jar.read_bytes()).hexdigest())
(out / 'skyblock-jp-26.2.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
print(version)

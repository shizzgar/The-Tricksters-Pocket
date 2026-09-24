"""Packaging gates for English built-in instructions and both Bro kits."""
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import unittest

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app/src/main/assets'
SKILLS = ASSETS / 'default-skills'


class BroSkillPackagesTest(unittest.TestCase):
    def test_adapted_catalogs_match_every_installed_file(self):
        for profile in ('rebro', 'netbro'):
            result = subprocess.run([sys.executable, '-B', str(ROOT / f'scripts/update_{profile}_catalog.py'), '--check'],
                                    cwd=ROOT, capture_output=True, text=True, timeout=10)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            catalog = json.loads((ASSETS / f'assistant-presets/{profile}/catalog.json').read_text())
            self.assertEqual('en', catalog['instruction_language'])
            for item in catalog['packages']:
                package = SKILLS / item['name']
                files = {p.relative_to(package).as_posix(): p.read_bytes() for p in package.rglob('*') if p.is_file()}
                manifest = files['MANIFEST.sha256']
                expected = dict(line.split('  ', 1)[::-1] for line in manifest.decode().splitlines())
                self.assertEqual(set(files), set(expected) | {'MANIFEST.sha256'}, item['name'])
                self.assertEqual(item['files'], len(files))
                self.assertEqual(item['bytes'], sum(map(len, files.values())))
                self.assertEqual(item['manifest_sha256'], hashlib.sha256(manifest).hexdigest())
                for name, digest in expected.items():
                    self.assertEqual(digest, hashlib.sha256(files[name]).hexdigest(), name)

    def test_all_bundled_instruction_text_uses_english_and_bro_links_resolve(self):
        # Regression guard for the translated Russian/Chinese instruction inventory.
        # Identifiers, historical filenames and all non-Latin user data remain untouched.
        for path in SKILLS.rglob('*'):
            if not path.is_file() or path.suffix not in {'.md', '.py', '.json', '.js', '.txt'}:
                continue
            text = path.read_text()
            self.assertIsNone(re.search('[\u0400-\u04ff\u3400-\u9fff]', text), str(path))
            if path.suffix != '.md' or not path.relative_to(SKILLS).parts[0].startswith(('rebro-', 'netbro-')):
                continue
            prose = re.sub(r'(?s)```.*?```|~~~.*?~~~|`[^`]*`', '', text)
            for link in re.findall(r'\]\(([^)]+)\)', prose):
                if '://' in link or link.startswith('#'):
                    continue
                target = link.split('#', 1)[0]
                self.assertTrue((path.parent / target).is_file(), (str(path), link))

    def test_original_source_provenance_is_distinct_from_adapted_manifests(self):
        provenance = json.loads((ASSETS / 'assistant-presets/rebro/provenance.json').read_text())
        self.assertEqual('cf9d949bf541f027fc5b2a0d677159b636d07e55e2ebdfa2f586a9e169f247f8', provenance['kit_sha256'])
        self.assertEqual('fafcecdbc2e9079ccb047e8b91c8a7171f693bfe289af54181084aaf6029145f', provenance['system_prompt_source_sha256'])
        self.assertEqual(253, sum(p['files'] for p in provenance['source_packages']))
        catalog = json.loads((ASSETS / 'assistant-presets/rebro/catalog.json').read_text())
        self.assertEqual(254, sum(p['files'] for p in catalog['packages']))
        sources = {p['name']: p for p in provenance['source_packages']}
        for item in catalog['packages']:
            self.assertEqual(sources[item['name']]['zip_sha256'], item['source_zip_sha256'])
            self.assertNotEqual(item['source_zip_sha256'], item['manifest_sha256'])
        guide = (SKILLS / 'rebro-workflow/references/operating-rules.md').read_text()
        self.assertEqual(21, len(re.findall(r'^## [0-9]+\.', guide, re.M)))
        self.assertIn('dated evidence', guide)


if __name__ == '__main__':
    unittest.main()

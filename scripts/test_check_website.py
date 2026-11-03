import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name('check_website.py')
WEBSITE = Path(__file__).resolve().parent.parent / 'website'


def run_check(root: Path) -> subprocess.CompletedProcess:
    return subprocess.run([sys.executable, str(SCRIPT), str(root)], capture_output=True, text=True)


class CheckWebsiteTest(unittest.TestCase):
    def test_repository_website_passes(self):
        result = run_check(WEBSITE)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_reports_broken_markup_and_references(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'index.html').write_text(
                '<!doctype html><html><head><title>t</title></head><body>'
                '<div><p>unclosed div</body></html>'
                '<a href="#missing">x</a><a href="nope.css">y</a>'
                '<img src="favicon.svg"><script src="https://cdn.example/x.js"></script>'
                '<a href="http://example.com">z</a>',
                encoding='utf-8',
            )
            result = run_check(root)
        self.assertEqual(result.returncode, 1)
        for expected in ('lang attribute', 'viewport', 'closing </body>', '#missing', 'nope.css', 'without alt',
                         'external script', 'insecure http link'):
            self.assertIn(expected, result.stderr)

    def test_fails_without_pages(self):
        with tempfile.TemporaryDirectory() as directory:
            result = run_check(Path(directory))
        self.assertEqual(result.returncode, 1)


if __name__ == '__main__':
    unittest.main()

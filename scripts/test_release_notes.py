import unittest
from release_notes import release_notes


class ReleaseNotesTest(unittest.TestCase):
    def setUp(self):
        self.report = dict(versionName='1.2.0', versionCode=12, standalone=True, testOnly=False, sha256='abc', certificateSha256='def')
        self.changelog = '# Changelog\n\n## [1.2.0] - 2026-11-03\n\n- New feature.\n\n## [1.1.0]\n- Older feature.\n'

    def test_only_current_changes_and_exact_code_are_published(self):
        notes = release_notes(self.report, 'v1.2.0', self.changelog)
        self.assertIn('New feature.', notes)
        self.assertIn('<!-- runlocal-version-code: 12 -->', notes)
        self.assertNotIn('Older feature', notes)

    def test_tag_and_changelog_must_match_apk(self):
        with self.assertRaises(ValueError):
            release_notes(self.report, 'v9.9.9', self.changelog)
        with self.assertRaises(ValueError):
            release_notes(self.report, 'v1.2.0', '# Empty')

    def test_test_only_or_split_artifacts_are_rejected(self):
        for changes in [dict(testOnly=True), dict(standalone=False)]:
            with self.assertRaises(ValueError):
                release_notes(self.report | changes, 'v1.2.0', self.changelog)

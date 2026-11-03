#!/usr/bin/env python3
"""Produce release notes and the machine-readable version marker used by the app."""
import argparse
import json
import re
from pathlib import Path


def release_notes(report, tag, changelog):
    version = report['versionName']
    if tag != f'v{version}':
        raise ValueError('Tag must equal v + the APK versionName.')
    if not report['standalone'] or report['testOnly']:
        raise ValueError('Only a verified standalone distributable APK may be released.')
    section = re.search(rf'^## \[{re.escape(version)}\].*?\n(.*?)(?=^## |\Z)', changelog, re.M | re.S)
    if section is None:
        raise ValueError('Add this version to CHANGELOG.md before releasing.')
    return (section.group(1).strip() + '\n\n'
            f"<!-- runlocal-version-code: {report['versionCode']} -->\n\n"
            f"APK SHA-256: `{report['sha256']}`\n\n"
            f"Signing certificate SHA-256: `{report['certificateSha256']}`\n")


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--report', type=Path, required=True)
    parser.add_argument('--tag', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output.write_text(release_notes(json.loads(args.report.read_text()), args.tag, Path('CHANGELOG.md').read_text()))

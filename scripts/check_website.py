#!/usr/bin/env python3
"""Static checks for the landing page in website/.

Verifies that the HTML parses with balanced tags, that every local href/src
points at a file in the directory, that in-page anchors exist, and that no
page references a build step or external script.
"""
from __future__ import annotations

import argparse
import sys
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import urlparse

VOID_ELEMENTS = {
    'area', 'base', 'br', 'col', 'embed', 'hr', 'img', 'input', 'link', 'meta',
    'param', 'source', 'track', 'wbr',
}


class PageParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.stack: list[str] = []
        self.errors: list[str] = []
        self.ids: set[str] = set()
        self.links: list[str] = []
        self.scripts: list[str] = []
        self.has_title = False
        self.has_viewport = False
        self.has_lang = False
        self.images_without_alt = 0

    def handle_starttag(self, tag, attrs):
        attributes = dict(attrs)
        if tag == 'html' and attributes.get('lang'):
            self.has_lang = True
        if tag == 'title':
            self.has_title = True
        if tag == 'meta' and attributes.get('name') == 'viewport':
            self.has_viewport = True
        if 'id' in attributes:
            value = attributes['id']
            if value in self.ids:
                self.errors.append(f'line {self.getpos()[0]}: duplicate id "{value}"')
            self.ids.add(value)
        if tag in ('a', 'link') and attributes.get('href'):
            self.links.append(attributes['href'])
        if tag in ('img', 'script') and attributes.get('src'):
            self.links.append(attributes['src'])
            if tag == 'script':
                self.scripts.append(attributes['src'])
        if tag == 'img' and 'alt' not in attributes:
            self.images_without_alt += 1
        if tag not in VOID_ELEMENTS:
            self.stack.append(tag)

    def handle_startendtag(self, tag, attrs):
        self.handle_starttag(tag, attrs)
        if tag not in VOID_ELEMENTS:
            self.stack.pop()

    def handle_endtag(self, tag):
        if tag in VOID_ELEMENTS:
            return
        if not self.stack or self.stack[-1] != tag:
            expected = self.stack[-1] if self.stack else 'nothing'
            self.errors.append(f'line {self.getpos()[0]}: closing </{tag}> but expected </{expected}>')
            if tag in self.stack:
                while self.stack and self.stack[-1] != tag:
                    self.stack.pop()
                if self.stack:
                    self.stack.pop()
            return
        self.stack.pop()


def check_page(page: Path, root: Path) -> list[str]:
    parser = PageParser()
    parser.feed(page.read_text(encoding='utf-8'))
    parser.close()
    problems = [f'{page.name}: {error}' for error in parser.errors]
    if parser.stack:
        problems.append(f'{page.name}: unclosed tags at end of document: {parser.stack}')
    if not parser.has_lang:
        problems.append(f'{page.name}: <html> needs a lang attribute')
    if not parser.has_title:
        problems.append(f'{page.name}: missing <title>')
    if not parser.has_viewport:
        problems.append(f'{page.name}: missing viewport meta tag')
    if parser.images_without_alt:
        problems.append(f'{page.name}: {parser.images_without_alt} <img> without alt')
    for reference in parser.links:
        parsed = urlparse(reference)
        if parsed.scheme in ('http', 'https', 'mailto'):
            if parsed.scheme == 'http':
                problems.append(f'{page.name}: insecure http link {reference}')
            continue
        if reference.startswith('#'):
            if reference[1:] and reference[1:] not in parser.ids:
                problems.append(f'{page.name}: anchor {reference} has no matching id')
            continue
        target = (page.parent / parsed.path).resolve()
        if not target.is_file() or root.resolve() not in target.parents:
            problems.append(f'{page.name}: local reference {reference} is not a file in {root}')
    for script in parser.scripts:
        if urlparse(script).scheme:
            problems.append(f'{page.name}: external script {script} is not allowed')
    return problems


def main() -> int:
    arguments = argparse.ArgumentParser(description=__doc__)
    arguments.add_argument('root', type=Path, nargs='?', default=Path('website'))
    root = arguments.parse_args().root
    pages = sorted(root.glob('*.html'))
    if not pages:
        print(f'No HTML pages found in {root}', file=sys.stderr)
        return 1
    problems = [problem for page in pages for problem in check_page(page, root)]
    for problem in problems:
        print(problem, file=sys.stderr)
    if problems:
        return 1
    print(f'Checked {len(pages)} page(s) in {root}: OK')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())

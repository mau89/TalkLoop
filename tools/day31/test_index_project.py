import tempfile
import unittest
from pathlib import Path
from index_project import collect, markdown_sections


class ProjectIndexTest(unittest.TestCase):
    def test_sections_preserve_text_and_ignore_code_headings(self):
        text = 'Введение\n## Модули\ncore\n```shell\n# не заголовок\n```\n## API\nHTTP\n'
        sections = markdown_sections(text)
        self.assertEqual(['Введение', 'Модули', 'API'], [s['section'] for s in sections])
        self.assertEqual(text, ''.join(text[s['start']:s['end']] for s in sections))
        self.assertEqual('L7', sections[-1]['anchor'])

    def test_allowlist_and_nested_docs(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'README.md').write_text('# TalkLoop\nОписание', encoding='utf-8')
            (root / 'docs/nested').mkdir(parents=True)
            (root / 'docs/nested/api.md').write_text('# API\nМетоды', encoding='utf-8')
            (root / 'docs/report.json').write_text('{"secret":true}')
            (root / 'secrets.properties').write_text('secret')
            (root / 'docs/link.md').symlink_to(root / 'secrets.properties')
            documents = collect(root)
            self.assertEqual(['README.md', 'docs/nested/api.md'], [d['file'] for d in documents])
            self.assertTrue(all(len(d['content_sha256']) == 64 for d in documents))


if __name__ == '__main__':
    unittest.main()

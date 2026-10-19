import tempfile
import unittest
from pathlib import Path
from index_support import collect


class SupportIndexTest(unittest.TestCase):
    def test_only_support_docs_are_indexed_without_tickets_secrets_or_other_corpora(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            support = root / 'docs/support'
            support.mkdir(parents=True)
            (support / 'faq.md').write_text('# Вход\nПодтвердите почту', encoding='utf-8')
            (root / 'docs/project.md').write_text('Документация разработчика', encoding='utf-8')
            (root / 'README.md').write_text('Other corpus')
            (support / 'tickets.json').write_text('{"password":"secret"}')
            (root / 'secret.md').write_text('Secret')
            (support / 'link.md').symlink_to(root / 'secret.md')
            docs = collect(root)
            self.assertEqual(['docs/support/faq.md'], [d['file'] for d in docs])
            self.assertNotIn('Secret', str(docs))
            self.assertEqual('Вход', docs[0]['sections'][0]['section'])


if __name__ == '__main__':
    unittest.main()

import json
from pathlib import Path
import re
import tempfile
import unittest
from types import SimpleNamespace

from index_documents import assemble_sections, extract_html, make_chunks, relevant, search, token_windows, write_json


class WordTokenizer:
    def encode(self, text, add_special_tokens=False):
        matches = list(re.finditer(r'\S+', text))
        return SimpleNamespace(ids=list(range(len(matches))), offsets=[m.span() for m in matches])


class IndexingTests(unittest.TestCase):
    def setUp(self):
        self.tokenizer = WordTokenizer()
        text, sections = assemble_sections([
            ('Intro', 'intro', 'one two three four five six seven'),
            ('Cleaning', 'cleaning', 'восемь девять десять eleven twelve thirteen fourteen')])
        self.doc = {'source': 'https://example.com/guide', 'title': 'Guide', 'file': 'raw/guide.html',
                    'models': ['A1'], 'text': text, 'sections': sections,
                    'content_sha256': 'hash', 'downloaded_at': '2026-10-02'}

    def test_tail_overlap_unicode_and_no_loss(self):
        windows = list(token_windows(self.doc['text'], self.tokenizer, size=6, overlap=2))
        chunks = [self.doc['text'][a:b] for a, b in windows]
        self.assertEqual(chunks[0].split()[-2:], chunks[1].split()[:2])
        self.assertEqual(chunks[-1].split()[-1], 'fourteen')
        self.assertEqual(set(' '.join(chunks).split()), set(self.doc['text'].split()))
        self.assertTrue(all(len(c.split()) <= 6 for c in chunks))

    def test_structural_respects_boundaries_and_splits_large_sections(self):
        fixed = make_chunks(self.doc, 'fixed', self.tokenizer, size=6, overlap=2)
        structural = make_chunks(self.doc, 'structural', self.tokenizer, size=6, overlap=2)
        self.assertTrue(any(c['section_count'] > 1 for c in fixed))
        self.assertTrue(all(c['section_count'] == 1 for c in structural))
        self.assertEqual(set(' '.join(c['text'] for c in structural).split()), set(self.doc['text'].split()))
        self.assertEqual(structural, make_chunks(self.doc, 'structural', self.tokenizer, size=6, overlap=2))
        self.assertEqual(len({c['chunk_id'] for c in fixed + structural}), len(fixed + structural))

    def test_invalid_overlap_fails_instead_of_looping(self):
        with self.assertRaises(ValueError):
            list(token_windows('one two', self.tokenizer, 2, 2))

    def test_wiki_template_extraction_excludes_navigation_and_keeps_nested_steps(self):
        html = '''<nav>wrong content</nav><page title="A1 Maintenance" updated-at="2026-01-01">
          <template slot="contents"><h1 id="clean">Cleaning</h1><p>Use soap and water.</p>
          <ol><li><p>First step</p><ul><li>Nested detail</li></ul></li></ol>
          <h2 id="dry">Drying</h2><p>'''+('Dry the plate completely. ' * 20)+'''</p>
          <script>bad javascript</script></template></page>'''
        title, text, sections, date = extract_html(html, 'fallback')
        self.assertEqual(title, 'A1 Maintenance')
        self.assertNotIn('wrong content', text)
        self.assertNotIn('bad javascript', text)
        self.assertEqual(text.count('First step'), 1)
        self.assertEqual(text.count('Nested detail'), 1)
        self.assertEqual(sections[1]['section'], 'Cleaning / Drying')
        self.assertEqual(date, '2026-01-01')

    def test_persistence_model_filter_and_evidence_evaluation(self):
        index = {'chunks': [
            {'chunk_id': 'wrong-model', 'models': ['P1S'], 'embedding': [1.0, 0.0], 'source': 'source', 'text': 'needle'},
            {'chunk_id': 'a1', 'models': ['A1'], 'embedding': [0.8, 0.6], 'source': 'source', 'text': 'Use a needle'},
            {'chunk_id': 'other', 'models': ['A1'], 'embedding': [0.0, 1.0], 'source': 'other', 'text': 'needle'}]}
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'index.json'
            write_json(path, index)
            saved = json.loads(path.read_text())
            hits = search(saved, [1.0, 0.0], 'A1', 5)
        self.assertEqual([h['chunk_id'] for h in hits], ['a1', 'other'])
        self.assertNotIn('embedding', hits[0])
        self.assertTrue(relevant(hits[0], {'source': 'source', 'evidence_any': ['NEEDLE']}))
        self.assertFalse(relevant(hits[1], {'source': 'source', 'evidence_any': ['needle']}))

    def test_russian_recipe_selects_article_not_status_and_preserves_ingredients(self):
        html = '''<div id="mw-indicator-status"><div class="mw-parser-output">Wrong status</div></div>
          <nav>Wrong navigation</nav><div id="mw-content-text"><div class="mw-parser-output">
          <h2><span id="ingredients">Состав</span><span class="mw-editsection">править</span></h2>
          <table><tr><td>Лук</td><td>250 г</td></tr></table>
          <h2 id="cooking">Приготовление</h2><ol><li><p>Нарезать лук.</p></li></ol>
          <p>'''+('Варить на слабом огне и помешивать. ' * 12)+'''</p>
          <ol class="references"><li>Wrong reference</li></ol>
          </div></div>'''
        title, text, sections, _ = extract_html(html, 'Луковый суп', 'wikibooks')
        self.assertEqual(title, 'Луковый суп')
        self.assertNotIn('Wrong', text)
        self.assertNotIn('править', text)
        self.assertIn('Лук 250 г', text)
        self.assertEqual(text.count('Нарезать лук.'), 1)
        self.assertEqual([s['anchor'] for s in sections], ['ingredients', 'cooking'])
        self.assertEqual([s['section'] for s in sections], ['Состав', 'Приготовление'])
        from bs4 import BeautifulSoup
        fragment = str(BeautifulSoup(html, 'html.parser').select_one('#mw-content-text .mw-parser-output'))
        self.assertEqual(extract_html(fragment, 'Луковый суп', 'wikibooks'),
                         extract_html(html, 'Луковый суп', 'wikibooks'))


if __name__ == '__main__':
    unittest.main()

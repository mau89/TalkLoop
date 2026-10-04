#!/usr/bin/env python3
"""Day 21: Russian cookbook -> token chunks -> local E5 -> JSON indexes.

Only `prepare` accesses the network. `build`, `compare` and `search` use local files.
"""
from __future__ import annotations

import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import json
import math
import os
from pathlib import Path
import re
import subprocess
import sys
import time
from urllib.parse import urlparse, quote

ROOT = Path(__file__).resolve().parents[2]
SOURCES = Path(__file__).with_name('sources.json')
QUESTIONS = Path(__file__).with_name('questions.json')
MODEL = 'intfloat/multilingual-e5-small'
REVISION = '614241f622f53c4eeff9890bdc4f31cfecc418b3'
DIMENSION = 384
CHUNK_TOKENS = 320
OVERLAP = 48
WORDS_PER_PAGE = 500
MIN_WORDS = 30 * WORDS_PER_PAGE


def read_json(path):
    return json.loads(Path(path).read_text(encoding='utf-8'))


def write_json(path, value):
    """Atomic replacement keeps previous valid output on interruption."""
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + '.tmp')
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + '\n', encoding='utf-8')
    temporary.replace(path)


def digest(text):
    return hashlib.sha256(text.encode('utf-8')).hexdigest()


def download(url, path):
    if urlparse(url).scheme != 'https':
        raise ValueError('Only HTTPS sources are accepted')
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + '.part')
    result = subprocess.run(['curl', '--fail', '--silent', '--show-error', '--location',
                             '--retry', '2', '--max-time', '180', quote(url, safe=':/?=&%'), '--output', str(temporary)],
                            capture_output=True, text=True)
    if result.returncode:
        temporary.unlink(missing_ok=True)
        raise RuntimeError(f'{url}: {result.stderr.strip()}')
    temporary.replace(path)


def assemble_sections(parts):
    """One canonical text with exact section offsets shared by both strategies."""
    text = ''
    sections = []
    for title, anchor, content in parts:
        content = content.strip()
        if not content:
            continue
        if text:
            text += '\n\n'
        start = len(text)
        text += content
        sections.append({'section': title, 'anchor': anchor, 'start': start, 'end': len(text)})
    return text, sections


def extract_html(raw, fallback_title, extractor='bambu'):
    from bs4 import BeautifulSoup
    soup = BeautifulSoup(raw, 'html.parser')
    updated_at = None
    if extractor == 'wikibooks':
        body = soup.select_one('#mw-content-text .mw-parser-output')
        # action=render returns just the article fragment, without a page shell.
        if body is None and soup.find('html') is None:
            body = soup.select_one('div.mw-parser-output')
        if body is None:
            raise ValueError('Expected a Wikibooks article, received a different page')
        # A status indicator also has mw-parser-output; select the article only.
        title = fallback_title
    elif extractor == 'bambu':
        page = soup.find('page')
        template = soup.select_one('template[slot="contents"]')
        if template is None or page is None:
            raise ValueError('Expected a Bambu Wiki article, received a different page')
        # Reparse TemplateString nodes to obtain ordinary text nodes.
        body = BeautifulSoup(template.decode_contents(), 'html.parser')
        title = page.get('title', fallback_title)
        updated_at = page.get('updated-at')
    else:
        raise ValueError(f'Unknown HTML extractor: {extractor}')
    for node in body.select('script, style, iframe, .toc-anchor, .mw-editsection, '
                            '.toc, .navbox, .infobox, .metadata, .noprint, '
                            '.references, .reflist, sup.reference'):
        node.decompose()
    path = []
    current = title
    anchor = ''
    blocks = []
    parts = []
    for node in body.find_all(re.compile(r'^(h[1-6]|p|li|tr)$')):
        # A list item or table row owns its nested paragraphs/items.
        if any(p.name in ('p', 'li', 'tr') for p in node.parents):
            continue
        value = re.sub(r'\s+', ' ', node.get_text(' ', strip=True)).strip()
        if not value:
            continue
        if re.fullmatch(r'h[1-6]', node.name):
            parts.append((current, anchor, '\n'.join(blocks)))
            level = int(node.name[1])
            path = [(n, label) for n, label in path if n < level] + [(level, value)]
            current = ' / '.join(label for _, label in path)
            span = node.select_one('[id]')
            anchor = node.get('id') or (span.get('id') if span else '')
            blocks = [value]
        else:
            blocks.append(value)
    parts.append((current, anchor, '\n'.join(blocks)))
    text, sections = assemble_sections(parts)
    if len(text.split()) < 40:
        raise ValueError('Article has too little extractable text')
    return title, text, sections, updated_at


def extract_pdf(path, title):
    from pypdf import PdfReader
    reader = PdfReader(path)
    parts = [(f'PDF page {i + 1}', f'page={i + 1}', p.extract_text() or '')
             for i, p in enumerate(reader.pages)]
    text, sections = assemble_sections(parts)
    if len(text.split()) < 40:
        raise ValueError('PDF has too little text; OCR is required')
    return title, text, sections, None


def prepare(data, refresh=False):
    manifest = read_json(SOURCES)
    documents = []
    failures = []
    for n, entry in enumerate(manifest['sources'], 1):
        raw = data / 'raw' / (digest(entry['source'])[:16] + '.' + entry['format'])
        try:
            if refresh or not raw.exists():
                url = (entry['source'] + '?action=render'
                       if entry.get('extractor') == 'wikibooks' else entry['source'])
                download(url, raw)
            extracted = (extract_pdf(raw, entry['title']) if entry['format'] == 'pdf'
                         else extract_html(raw.read_text(encoding='utf-8'), entry['title'],
                                           entry.get('extractor', 'bambu')))
            title, text, sections, updated_at = extracted
            doc = {**entry, 'title': title, 'file': str(raw.relative_to(data)), 'text': text,
                   'sections': sections, 'content_sha256': digest(text), 'source_updated_at': updated_at,
                   'downloaded_at': datetime.fromtimestamp(raw.stat().st_mtime, timezone.utc).isoformat()}
            documents.append(doc)
            print(f'[{n}/{len(manifest["sources"])}] {title}: {len(text.split())} words', flush=True)
        except (ValueError, OSError, RuntimeError) as error:
            failures.append(str(error))
            print(f'FAILED: {error}', file=sys.stderr, flush=True)
    if failures:
        write_json(data / 'prepare-errors.json', failures)
        raise RuntimeError('Source collection is incomplete; see prepare-errors.json. Existing corpus was preserved.')
    words = sum(len(d['text'].split()) for d in documents)
    if words < MIN_WORDS:
        raise ValueError(f'Only {words} words; require {MIN_WORDS} (=30 pages at 500 words/page)')
    covered = set(m for d in documents for m in d['models'])
    missing = set(manifest['model_catalog']) - covered
    if missing:
        raise ValueError(f'Models without documents: {sorted(missing)}')
    write_json(data / 'corpus.json', {'schema_version': 1, 'documents': documents,
                                   'words': words, 'words_per_page': WORDS_PER_PAGE,
                                   'estimated_text_pages': round(words / WORDS_PER_PAGE, 2),
                                   'model_catalog': manifest['model_catalog'],
                                   'title': manifest.get('title', 'Документы'),
                                   'language': manifest.get('language', 'en'),
                                   'example_queries': manifest.get('example_queries', []),
                                   'attribution': manifest.get('attribution', {}),
                                   'manifest_sha256': digest(SOURCES.read_text(encoding='utf-8'))})
    (data / 'prepare-errors.json').unlink(missing_ok=True)
    for remote, filename in [('onnx/model.onnx', 'model.onnx'), ('tokenizer.json', 'tokenizer.json')]:
        target = data / 'model' / filename
        if not target.exists():
            print(f'Downloading {MODEL}: {filename}', flush=True)
            download(f'https://huggingface.co/{MODEL}/resolve/{REVISION}/{remote}', target)
    write_json(data / 'model' / 'identity.json', {'model': MODEL, 'revision': REVISION,
                                               'pooling': 'attention-mask mean, L2 normalization'})
    print(f'Corpus ready: {len(documents)} documents; {words} words; {words / WORDS_PER_PAGE:.1f} text pages.')


class LocalE5:
    def __init__(self, data):
        import numpy as np
        import onnxruntime as ort
        from tokenizers import Tokenizer
        self.np = np
        identity = read_json(data / 'model' / 'identity.json')
        if identity['model'] != MODEL or identity['revision'] != REVISION:
            raise ValueError('Cached model identity does not match the indexer')
        self.tokenizer = Tokenizer.from_file(str(data / 'model' / 'tokenizer.json'))
        self.tokenizer.no_truncation()
        self.tokenizer.no_padding()
        options = ort.SessionOptions()
        options.intra_op_num_threads = min(4, os.cpu_count() or 1)
        self.session = ort.InferenceSession(str(data / 'model' / 'model.onnx'), options,
                                           providers=['CPUExecutionProvider'])
        self.inputs = {i.name for i in self.session.get_inputs()}

    def encode(self, texts):
        np = self.np
        result = []
        for start in range(0, len(texts), 8):
            batch = self.tokenizer.encode_batch(texts[start:start + 8])
            if any(len(b.ids) > 512 for b in batch):
                raise ValueError('Embedding input exceeds 512 tokens; refusing silent truncation')
            length = max(len(b.ids) for b in batch)
            ids = np.full((len(batch), length), 1, dtype=np.int64)
            mask = np.zeros_like(ids)
            for i, b in enumerate(batch):
                ids[i, :len(b.ids)] = b.ids
                mask[i, :len(b.ids)] = 1
            values = {'input_ids': ids, 'attention_mask': mask, 'token_type_ids': np.zeros_like(ids)}
            hidden = self.session.run(None, {k: v for k, v in values.items() if k in self.inputs})[0]
            vectors = (hidden * mask[..., None]).sum(axis=1) / mask.sum(axis=1)[:, None]
            vectors /= np.linalg.norm(vectors, axis=1, keepdims=True)
            if vectors.shape[1] != DIMENSION or not np.isfinite(vectors).all():
                raise ValueError('Invalid E5 output')
            result.extend(vectors.tolist())
        return result


def token_windows(text, tokenizer, size=CHUNK_TOKENS, overlap=OVERLAP):
    if not 0 <= overlap < size:
        raise ValueError('Require 0 <= overlap < chunk size')
    offsets = tokenizer.encode(text, add_special_tokens=False).offsets
    start = 0
    while start < len(offsets):
        end = min(start + size, len(offsets))
        # SentencePiece can retokenize a slice differently at its new beginning.
        # Validate the actual standalone text, not only the original offset count.
        while end > start and len(tokenizer.encode(
                text[offsets[start][0]:offsets[end - 1][1]], add_special_tokens=False).ids) > size:
            end -= 1
        if end == start:
            raise ValueError('A single character cannot fit the requested token budget')
        # Use original character slices so Cyrillic and punctuation stay intact.
        yield offsets[start][0], offsets[end - 1][1]
        if end == len(offsets):
            break
        start = max(start + 1, end - overlap)


def make_chunks(doc, strategy, tokenizer, size=CHUNK_TOKENS, overlap=OVERLAP):
    if strategy not in ('fixed', 'structural'):
        raise ValueError(f'Unknown strategy: {strategy}')
    segments = [(0, len(doc['text']))] if strategy == 'fixed' else [(s['start'], s['end']) for s in doc['sections']]
    chunks = []
    for begin, end in segments:
        for a, b in token_windows(doc['text'][begin:end], tokenizer, size, overlap):
            a, b = a + begin, b + begin
            text = doc['text'][a:b]
            sections = [s for s in doc['sections'] if s['start'] < b and s['end'] > a]
            chunks.append({'chunk_id': digest(f'{doc["source"]}|{strategy}|{a}|{b}|{text}')[:24],
                           'source': doc['source'], 'title': doc['title'], 'file': doc['file'],
                           'section': ' | '.join(s['section'] for s in sections),
                           'anchors': [s['anchor'] for s in sections], 'models': doc['models'],
                           'strategy': strategy, 'char_start': a, 'char_end': b,
                           'token_count': len(tokenizer.encode(text, add_special_tokens=False).ids),
                           'section_count': len(sections), 'text': text,
                           'source_content_sha256': doc['content_sha256'],
                           'downloaded_at': doc['downloaded_at']})
            if 'attribution' in doc:
                chunks[-1]['attribution'] = doc['attribution']
    return chunks


def shortened(text, tokenizer, limit):
    offsets = tokenizer.encode(text, add_special_tokens=False).offsets
    return text if len(offsets) <= limit else text[:offsets[limit - 1][1]]


def embedding_text(chunk, tokenizer):
    title = shortened(chunk['title'], tokenizer, 40)
    section = shortened(chunk['section'], tokenizer, 80)
    return f'passage: {title}\n{section}\n{chunk["text"]}'


def build(data):
    corpus = read_json(data / 'corpus.json')
    if corpus['words'] < MIN_WORDS:
        raise ValueError('Corpus is below the minimum size')
    model = LocalE5(data)
    # Prepare both strategies before embedding; count all inputs, never truncate.
    all_chunks = {strategy: [c for d in corpus['documents'] for c in make_chunks(d, strategy, model.tokenizer)]
                  for strategy in ('fixed', 'structural')}
    for strategy, chunks in all_chunks.items():
        started = time.perf_counter()
        texts = [embedding_text(c, model.tokenizer) for c in chunks]
        for text in texts:
            if len(model.tokenizer.encode(text).ids) > 512:
                raise ValueError('Chunk including metadata exceeds the model token budget')
        print(f'Embedding {strategy}: {len(chunks)} chunks on CPU', flush=True)
        vectors = model.encode(texts)
        for chunk, vector in zip(chunks, vectors):
            chunk['embedding'] = vector
        index = {'schema_version': 1, 'strategy': strategy, 'model': MODEL, 'model_revision': REVISION,
                 'dimension': DIMENSION, 'normalized': True, 'pooling': 'attention-mask mean',
                 'embedding_template': 'passage: {title:40 tokens}\n{section:80 tokens}\n{text}',
                 'chunk_tokens': CHUNK_TOKENS, 'overlap_tokens': OVERLAP,
                 'corpus_sha256': digest((data / 'corpus.json').read_text(encoding='utf-8')),
                 'build_seconds': round(time.perf_counter() - started, 3), 'chunks': chunks}
        write_json(data / f'index-{strategy}.json', index)
        print(f'Saved index-{strategy}.json', flush=True)
    compare(data, model)


def load_index(data, strategy):
    index = read_json(data / f'index-{strategy}.json')
    if (index['schema_version'], index['model'], index['model_revision'], index['dimension']) != (1, MODEL, REVISION, DIMENSION):
        raise ValueError('Incompatible index format or embedding model')
    if not index['chunks']:
        raise ValueError('Empty index')
    for chunk in index['chunks']:
        vector = chunk['embedding']
        if len(vector) != DIMENSION or not all(math.isfinite(v) for v in vector):
            raise ValueError('Invalid saved embedding')
    return index


def search(index, vector, model_filter=None, limit=5):
    import numpy as np
    chunks = [c for c in index['chunks'] if model_filter is None or model_filter in c['models']]
    if not chunks:
        return []
    scores = np.asarray([c['embedding'] for c in chunks], dtype=np.float32) @ np.asarray(vector, dtype=np.float32)
    order = np.argsort(-scores, kind='stable')[:limit]
    return [{'score': round(float(scores[i]), 6), **{k: v for k, v in chunks[i].items() if k != 'embedding'}} for i in order]


def relevant(hit, expected):
    return hit['source'] == expected['source'] and any(
        fragment.casefold() in hit['text'].casefold() for fragment in expected['evidence_any'])


def compare(data, model=None):
    corpus = read_json(data / 'corpus.json')
    indexes = {s: load_index(data, s) for s in ('fixed', 'structural')}
    if len({i['corpus_sha256'] for i in indexes.values()}) != 1 or next(iter(indexes.values()))['corpus_sha256'] != digest((data / 'corpus.json').read_text(encoding='utf-8')):
        raise ValueError('Indexes must refer to the current, identical corpus')
    model = model or LocalE5(data)
    questions = read_json(QUESTIONS)
    known_sources = {d['source'] for d in corpus['documents']}
    if any(q['expected']['source'] not in known_sources for q in questions):
        raise ValueError('Evaluation refers to a document absent from the corpus')
    vectors = model.encode(['query: ' + q['query'] for q in questions])
    report = {'schema_version': 1, 'created_at': datetime.now(timezone.utc).isoformat(),
              'model': MODEL, 'revision': REVISION, 'corpus_sha256': indexes['fixed']['corpus_sha256'],
              'documents': len(corpus['documents']), 'words': corpus['words'],
              'estimated_text_pages': corpus['estimated_text_pages'], 'words_per_page': WORDS_PER_PAGE,
              'coverage': dict(sorted(Counter(m for d in corpus['documents'] for m in d['models']).items())),
              'evaluation': f'Russian queries -> {corpus.get("language", "en")} documents; cosine only; same filters for both strategies. Relevant = expected source AND one evidence substring. Small curated set, not a general benchmark.',
              'strategies': {}, 'questions': []}
    for strategy, index in indexes.items():
        chunks = index['chunks']
        lengths = sorted(c['token_count'] for c in chunks)
        report['strategies'][strategy] = {'chunks': len(chunks), 'min_tokens': lengths[0],
            'mean_tokens': round(sum(lengths) / len(lengths), 1), 'median_tokens': lengths[len(lengths)//2],
            'max_tokens': lengths[-1], 'cross_section_chunks': sum(c['section_count'] > 1 for c in chunks),
            'index_bytes': (data / f'index-{strategy}.json').stat().st_size,
            'build_seconds': index['build_seconds'], 'hit_at_1': 0, 'hit_at_5': 0, 'mrr_at_5': 0}
    for question, vector in zip(questions, vectors):
        result = {**question, 'results': {}}
        for strategy, index in indexes.items():
            hits = search(index, vector, question.get('model'), 5)
            ranks = [i + 1 for i, h in enumerate(hits) if relevant(h, question['expected'])]
            rank = ranks[0] if ranks else None
            metrics = report['strategies'][strategy]
            metrics['hit_at_1'] += int(rank == 1) / len(questions)
            metrics['hit_at_5'] += int(rank is not None) / len(questions)
            metrics['mrr_at_5'] += (1 / rank if rank else 0) / len(questions)
            result['results'][strategy] = {'first_relevant_rank': rank, 'top_5': hits}
        report['questions'].append(result)
    for metrics in report['strategies'].values():
        for name in ('hit_at_1', 'hit_at_5', 'mrr_at_5'):
            metrics[name] = round(metrics[name], 4)
    write_json(data / 'comparison.json', report)
    print(json.dumps(report['strategies'], ensure_ascii=False, indent=2))
    return report


def serve_worker(data):
    """Private JSON-lines protocol for the JVM server; no network or shell execution.

    Load model and indexes once. Request handling stays sequential, with identical
    query vectors and printer filters in the side-by-side search mode.
    """
    corpus = read_json(data / 'corpus.json')
    indexes = {s: load_index(data, s) for s in ('fixed', 'structural')}
    corpus_hash = digest((data / 'corpus.json').read_text(encoding='utf-8'))
    if any(i['corpus_sha256'] != corpus_hash for i in indexes.values()):
        raise ValueError('Rebuild the indexes after updating the corpus')
    model = LocalE5(data)
    report_path = data / 'comparison.json'
    report = read_json(report_path) if report_path.exists() else None
    if report and report.get('corpus_sha256') != corpus_hash:
        report = None
    stats = []
    for strategy, index in indexes.items():
        chunks = index['chunks']
        stats.append({'strategy': strategy, 'chunks': len(chunks),
                      'meanTokens': round(sum(c['token_count'] for c in chunks) / len(chunks), 1),
                      'crossSectionChunks': sum(c['section_count'] > 1 for c in chunks),
                      'hitAt5': report['strategies'][strategy]['hit_at_5'] if report else None})
    catalog = {'documents': len(corpus['documents']), 'words': corpus['words'],
               'title': corpus.get('title', 'Документы'), 'language': corpus.get('language', 'en'),
               'exampleQueries': corpus.get('example_queries', []),
               'attribution': ' · '.join(corpus.get('attribution', {}).get(k, '') for k in ('author', 'license')).strip(' ·'),
               'textPages': corpus['estimated_text_pages'], 'models': corpus['model_catalog'],
               'embeddingModel': MODEL, 'dimension': DIMENSION, 'strategies': stats}
    for line in sys.stdin:
        try:
            request = json.loads(line)
            if request.get('operation') == 'status':
                response = catalog
            elif request.get('operation') == 'search':
                query = request.get('query', '').strip()
                printer = request.get('model')
                strategy = request.get('strategy', 'fixed')
                limit = request.get('limit', 5)
                if not query or len(query) > 2000:
                    raise ValueError('Введите вопрос длиной от 1 до 2000 символов.')
                if printer is not None and printer not in catalog['models']:
                    raise ValueError('Неизвестная модель принтера.')
                if strategy not in ('fixed', 'structural', 'both') or not isinstance(limit, int) or not 1 <= limit <= 10:
                    raise ValueError('Некорректная стратегия или количество результатов.')
                started = time.perf_counter()
                vector = model.encode(['query: ' + query])[0]
                results = []
                for name in (('fixed', 'structural') if strategy == 'both' else (strategy,)):
                    hits = search(indexes[name], vector, printer, limit)
                    results.append({'strategy': name, 'hits': [
                        {'chunkId': h['chunk_id'], 'source': h['source'], 'title': h['title'],
                         'file': h['file'], 'section': h['section'], 'anchors': h['anchors'],
                         'models': h['models'], 'tokenCount': h['token_count'],
                         'text': h['text'], 'score': h['score']} for h in hits]})
                response = {'query': query, 'model': printer, 'results': results,
                            'searchSeconds': round(time.perf_counter() - started, 3)}
            else:
                raise ValueError('Неизвестная операция.')
            reply = {'ok': True, 'data': response}
        except (ValueError, TypeError, AttributeError) as error:
            reply = {'ok': False, 'error': str(error)}
        print(json.dumps(reply, ensure_ascii=False, allow_nan=False), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--data-dir', type=Path, default=ROOT / 'day21-data')
    sub = parser.add_subparsers(dest='command', required=True)
    prep = sub.add_parser('prepare', help='Download source documents and E5 once')
    prep.add_argument('--refresh', action='store_true')
    sub.add_parser('build', help='Build both indexes and compare, entirely offline')
    sub.add_parser('compare', help='Rerun the fixed evaluation questions, offline')
    sub.add_parser('worker', help='Private local server protocol over stdin/stdout')
    query = sub.add_parser('search', help='Search the saved index, offline')
    query.add_argument('query')
    query.add_argument('--strategy', choices=['fixed', 'structural'], default='fixed')
    query.add_argument('--model', help='Exact printer model, e.g. A1 or "A1 mini"')
    query.add_argument('--limit', type=int, default=5)
    args = parser.parse_args()
    try:
        if args.command == 'prepare':
            prepare(args.data_dir, args.refresh)
        elif args.command == 'build':
            build(args.data_dir)
        elif args.command == 'compare':
            compare(args.data_dir)
        elif args.command == 'worker':
            serve_worker(args.data_dir)
        else:
            if not 1 <= args.limit <= 50:
                raise ValueError('--limit must be between 1 and 50')
            catalog = read_json(args.data_dir / 'corpus.json')['model_catalog']
            if args.model and args.model not in catalog:
                raise ValueError(f'Unknown model. Choose one of: {", ".join(catalog)}')
            model = LocalE5(args.data_dir)
            index = load_index(args.data_dir, args.strategy)
            hits = search(index, model.encode(['query: ' + args.query])[0], args.model, args.limit)
            print(json.dumps(hits, ensure_ascii=False, indent=2))
    except (OSError, ValueError, RuntimeError) as error:
        print(f'Day 21: {error}', file=sys.stderr)
        print('First run: tools/day21/run.sh prepare; then tools/day21/run.sh build', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())

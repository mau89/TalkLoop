#!/usr/bin/env python3
"""README + docs -> separate local E5 index, compatible with the Day 21 worker.

No downloads. Run with .day21-venv/bin/python tools/day31/index_project.py.
Re-run after editing documentation; restart the server to load the new snapshot.
"""
from datetime import datetime, timezone
from pathlib import Path
import argparse
import re
import sys
import time

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools/day21'))
import index_documents as indexer


def markdown_sections(text):
    """Split on headings outside fenced code, preserving every character."""
    sections = []
    start, offset, title, fence = 0, 0, 'Введение', None
    for line in text.splitlines(keepends=True):
        stripped = line.lstrip()
        marker = re.match(r'(`{3,}|~{3,})', stripped)
        if marker:
            token = marker.group(1)
            if fence is None:
                fence = token
            elif token[0] == fence[0] and len(token) >= len(fence):
                fence = None
        elif fence is None and re.match(r'^#{1,6}\s+', line):
            if offset > start:
                sections.append({'section': title, 'anchor': f'L{text[:start].count(chr(10)) + 1}',
                                 'start': start, 'end': offset})
            start, title = offset, re.sub(r'^#+\s+', '', line).strip()
        offset += len(line)
    if offset > start:
        sections.append({'section': title, 'anchor': f'L{text[:start].count(chr(10)) + 1}',
                         'start': start, 'end': offset})
    return sections


def collect(root):
    # Deliberate allowlist: no secrets, raw logs, source code, build outputs or JSON results.
    paths = [root / 'README.md', *sorted((root / 'docs').rglob('*.md'))]
    documents = []
    for path in paths:
        if path.is_symlink() or not path.resolve().is_relative_to(root.resolve()):
            continue
        text = path.read_text(encoding='utf-8')
        if not text.strip():
            continue
        relative = path.relative_to(root).as_posix()
        sections = markdown_sections(text)
        documents.append({'source': relative, 'file': relative, 'title': relative,
                          'text': text, 'sections': sections, 'models': [],
                          'content_sha256': indexer.digest(text),
                          'downloaded_at': datetime.now(timezone.utc).isoformat()})
    return documents


def build(root, data, documents=None, title='Документация TalkLoop', example_queries=None):
    documents = collect(root) if documents is None else documents
    if not documents:
        raise ValueError('README.md и docs/*.md не найдены')
    model_directory = root / 'day21-data/model'
    if not (model_directory / 'model.onnx').is_file():
        raise ValueError('Сначала подготовьте E5: tools/day21/run.sh prepare')
    data.mkdir(parents=True, exist_ok=True)
    model_link = data / 'model'
    if not model_link.exists():
        model_link.symlink_to(model_directory.resolve(), target_is_directory=True)
    model = indexer.LocalE5(data)
    words = sum(len(d['text'].split()) for d in documents)
    corpus = {'schema_version': 1, 'title': title, 'language': 'ru',
              'documents': documents, 'words': words, 'words_per_page': 500,
              'estimated_text_pages': round(words / 500, 2), 'model_catalog': [],
              'example_queries': example_queries if example_queries is not None else ['Как устроен проект TalkLoop?', 'Где реализован MCP?',
                                  'Как запустить сервер и Android-приложение?']}
    # Publish the corpus last. A partial rebuild fails the worker's hash validation.
    import json
    corpus_text = json.dumps(corpus, ensure_ascii=False, indent=2, allow_nan=False) + '\n'
    for strategy in ('fixed', 'structural'):
        started = time.perf_counter()
        chunks = [chunk for doc in documents for chunk in indexer.make_chunks(doc, strategy, model.tokenizer)]
        texts = [indexer.embedding_text(chunk, model.tokenizer) for chunk in chunks]
        print(f'{strategy}: {len(documents)} документов, {len(chunks)} фрагментов', flush=True)
        for chunk, vector in zip(chunks, model.encode(texts)):
            chunk['embedding'] = vector
        result = {'schema_version': 1, 'strategy': strategy, 'model': indexer.MODEL,
                  'model_revision': indexer.REVISION, 'dimension': indexer.DIMENSION,
                  'corpus_sha256': indexer.digest(corpus_text), 'chunks': chunks,
                  'build_seconds': round(time.perf_counter() - started, 3)}
        indexer.write_json(data / f'index-{strategy}.json', result)
    indexer.write_json(data / 'corpus.json', corpus)
    print(f'Готово: {data}; перезапустите сервер.', flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--data-dir', type=Path, default=ROOT / 'day31-data')
    args = parser.parse_args()
    build(ROOT, args.data_dir.resolve())

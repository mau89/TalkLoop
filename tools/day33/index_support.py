#!/usr/bin/env python3
"""Build a separate offline E5 index containing only docs/support Markdown."""
from datetime import datetime, timezone
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools/day31'))
import index_project


def collect(root):
    documents = []
    allowed = root.resolve() / 'docs/support'
    for path in sorted(allowed.rglob('*.md')):
        if path.is_symlink() or not path.resolve().is_relative_to(allowed):
            continue
        text = path.read_text(encoding='utf-8')
        if not text.strip():
            continue
        name = path.relative_to(root.resolve()).as_posix()
        documents.append({'source': name, 'file': name, 'title': name, 'text': text,
                          'sections': index_project.markdown_sections(text), 'models': [],
                          'content_sha256': index_project.indexer.digest(text),
                          'downloaded_at': datetime.now(timezone.utc).isoformat()})
    return documents


if __name__ == '__main__':
    index_project.build(ROOT, ROOT / 'day33-data', documents=collect(ROOT),
                        title='Поддержка TalkLoop · FAQ и справка',
                        example_queries=['Почему не работает авторизация?', 'Как подключиться к локальному серверу?'])

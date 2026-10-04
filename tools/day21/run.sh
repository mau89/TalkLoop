#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
PYTHON="$ROOT/.day21-venv/bin/python"
if [ ! -x "$PYTHON" ]; then
  echo 'Create .day21-venv with Python 3.10+ and install tools/day21/requirements.txt (see docs/day21-document-indexing.md).' >&2
  exit 1
fi
exec "$PYTHON" "$ROOT/tools/day21/index_documents.py" "$@"

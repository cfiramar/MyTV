#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
if command -v python3 >/dev/null 2>&1 && python3 -c "import sys" >/dev/null 2>&1; then
  exec python3 setup_wizard.py "$@"
else
  exec python setup_wizard.py "$@"
fi

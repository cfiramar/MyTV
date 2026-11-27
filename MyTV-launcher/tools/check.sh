#!/usr/bin/env bash
# Runs the full MyTV verification suite (.github/workflows/check.yml):
#   tools/check.sh           # Android APK + JUnit + Remote tests + i18n + LG webOS (.ipk) + Samsung Tizen (.wgt) + ES5/CSS
#   tools/check.sh android   # Android APK build + JUnit suites + Remote key tests
#   tools/check.sh web       # i18n catalogues + WebPrefsTest + LG/Samsung packages + ES5 & CSS checks
#   tools/check.sh i18n      # Translation catalogue verification only
set -euo pipefail
cd "$(dirname "$0")/.."
what="${1:-all}"
case "$what" in
  all|android|web|i18n) ;;
  *) echo "Usage: tools/check.sh [all|android|web|i18n]" >&2; exit 2 ;;
esac

PY_CMD="python3"
"$PY_CMD" -c "import sys" >/dev/null 2>&1 || PY_CMD="python"

if [ "$what" = all ] || [ "$what" = android ]; then
  bash ./build.sh --generic
  bash tools/test-remote.sh
  "$PY_CMD" -m unittest discover -s tools/setup_gui -p "test_*.py"
fi

if [ "$what" = i18n ]; then
  "$PY_CMD" tools/translate.py --check
fi

if [ "$what" = all ] || [ "$what" = web ]; then
  "$PY_CMD" tools/translate.py --check
  bash tools/test-web.sh

  packages="MyTV-webos.ipk MyTV-tizen.wgt"
  kept="$(mktemp -d)"
  for p in $packages; do if [ -f "$p" ]; then cp "$p" "$kept/"; fi; done
  trap 'for p in $packages; do if [ -f "$kept/$p" ]; then mv "$kept/$p" "$p"; else rm -f "$p"; fi; done; rm -rf "$kept"' EXIT
  "$PY_CMD" webos/build.py --generic
  "$PY_CMD" tizen/build.py --generic

  # webOS 3 and Tizen 3 run Chromium from 2015–2017: verify strict ES5 syntax and APIs
  for f in web/*.js webos/service/*.js; do npx --yes acorn@8.14.0 --ecma5 --silent "$f"; done
  if grep -nE '\.(includes|startsWith|endsWith|padStart|padEnd|repeat|find|findIndex|closest)\(|Object\.(assign|entries|values)\(|Array\.from\(|\bPromise\b|\bfetch\(|new (Map|Set|WeakMap)\b' web/*.js; then
    echo "web/ calls an API older TVs lack (above)." >&2
    exit 1
  fi
  if grep -nE '(^|[{;])[[:space:]]*(--[a-zA-Z-]+[[:space:]]*:|[a-z-]+[[:space:]]*:[^;{}]*var\(|display[[:space:]]*:[[:space:]]*(inline-)?grid([^a-z-]|$)|grid-[a-z-]+[[:space:]]*:|(-webkit-)?backdrop-filter[[:space:]]*:)' web/*.css; then
    echo "web/ uses CSS older TVs lack (above)." >&2
    exit 1
  fi
fi

echo "All MyTV checks passed."

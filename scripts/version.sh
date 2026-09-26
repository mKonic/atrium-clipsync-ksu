#!/usr/bin/env bash
# The module's version, from git only (nothing in a file to forget to edit).
#   code   10000 + commits on HEAD: the versionCode KernelSU orders updates by
#   name   git describe: "v1.0" at the tag, "v1.0-3-gabc1234" past it
# 0 and "unknown" when git can't answer (or the clone is shallow).
#
#   ./scripts/version.sh [code|name|both]
set -euo pipefail

HERE="$(cd "$(dirname "$0")/.." && pwd)"
code=0
name="unknown"

if git -C "$HERE" rev-parse --git-dir >/dev/null 2>&1; then
    if [ -f "$(git -C "$HERE" rev-parse --git-dir)/shallow" ]; then
        echo "version.sh: shallow clone -- 'git fetch --unshallow' for a real build number" >&2
    else
        commits=$(git -C "$HERE" rev-list --count HEAD 2>/dev/null || echo 0)
        [ "$commits" -gt 0 ] && code=$((10000 + commits))
    fi
    name=$(git -C "$HERE" describe --tags --always --dirty 2>/dev/null || echo unknown)
fi

case "${1:-both}" in
    code) echo "$code" ;;
    name) echo "$name" ;;
    both) echo "$code $name" ;;
    *) echo "usage: $0 [code|name|both]" >&2; exit 2 ;;
esac

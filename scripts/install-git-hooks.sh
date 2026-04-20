#!/usr/bin/env bash
# Installs project git hooks from scripts/githooks/ into the local .git/hooks directory.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HOOKS_SRC="$REPO_ROOT/scripts/githooks"
HOOKS_DST="$REPO_ROOT/.git/hooks"

if [[ ! -d "$HOOKS_SRC" ]]; then
  echo "Error: $HOOKS_SRC not found" >&2
  exit 1
fi

mkdir -p "$HOOKS_DST"

for hook in "$HOOKS_SRC"/*; do
  hook_name="$(basename "$hook")"
  cp "$hook" "$HOOKS_DST/$hook_name"
  chmod +x "$HOOKS_DST/$hook_name"
  echo "Installed hook: $hook_name"
done

echo "Done. Git hooks installed from scripts/githooks/."

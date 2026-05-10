#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# install-hooks.sh
#
# Symlinks tracked git hooks from hooks/ into .git/hooks/. Symlink (not copy)
# so a future hook change in the repo automatically reaches all contributors
# who ran this once.
#
# Run after cloning:
#     ./scripts/install-hooks.sh
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HOOKS_SRC="$REPO_ROOT/hooks"
HOOKS_DST="$REPO_ROOT/.git/hooks"

if [ ! -d "$HOOKS_DST" ]; then
    echo "Not a git repo (no .git/hooks/) — refusing to install."
    exit 1
fi

for hook in "$HOOKS_SRC"/*; do
    name=$(basename "$hook")
    target="$HOOKS_DST/$name"

    if [ -e "$target" ] && [ ! -L "$target" ]; then
        echo "├─ $name: already exists as a real file (not a symlink); skipping"
        echo "│   move it aside if you want our version: mv $target $target.bak"
        continue
    fi

    ln -sf "$hook" "$target"
    chmod +x "$hook"
    echo "├─ $name: symlinked"
done

echo "└─ done"

#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# compose-release-body.sh
#
# Composes the GitHub Release body markdown from CHANGELOG-extracted notes,
# SHA256 checksums, and the canonical asset list. Extracted from inline yaml
# in build_release.yml so it can be:
#   - tested locally without pushing a tag,
#   - diff-reviewed properly (yaml-with-printf is hostile to humans),
#   - reused by future scripts (e.g. release dry-run / preview tools).
#
# Inputs (env):
#   APP_VERSION       — version string without the v prefix (e.g. 2.2.9)
#   CHANGELOG_NOTES   — full markdown body of the [APP_VERSION] CHANGELOG
#                       section, fed in by the changelog job's output
#   CHECKSUMS_FILE    — path to dist/SHA256SUMS.txt (default: dist/SHA256SUMS.txt)
#
# Output: writes the composed markdown to stdout.
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

: "${APP_VERSION:?APP_VERSION is required (e.g. 2.2.9)}"
: "${CHANGELOG_NOTES:?CHANGELOG_NOTES is required (markdown body for this version)}"
CHECKSUMS_FILE="${CHECKSUMS_FILE:-dist/SHA256SUMS.txt}"

REPO_BASE="https://github.com/Kitty-Hivens/Nexira/releases/download/v${APP_VERSION}"

# Extract the ### Highlights section verbatim — the in-app updater renders
# this as the "What's new" panel; the GitHub release body shows it too,
# above the full changelog.
HIGHLIGHTS=$(printf '%s' "$CHANGELOG_NOTES" \
  | awk '/^### Highlights/ {flag=1; next} /^### / {flag=0} flag' \
  | sed '/^[[:space:]]*$/d')

# The same notes WITHOUT the ### Highlights block -- highlights already render
# above as "## What's New", so dumping the full section below would repeat them.
CHANGELOG_DETAILS=$(printf '%s' "$CHANGELOG_NOTES" \
  | awk '/^### Highlights/ {skip=1; next} /^### / {skip=0} !skip')
# Degenerate entry (highlights only, no summary/details) -> keep the full notes.
if [ -z "$(printf '%s' "$CHANGELOG_DETAILS" | tr -d '[:space:]')" ]; then
  CHANGELOG_DETAILS="$CHANGELOG_NOTES"
fi

# Build the SHA256 checksum table by walking dist/SHA256SUMS.txt.
CHECKSUMS=""
while IFS= read -r line; do
  hash=$(echo "$line" | awk '{print $1}')
  file=$(echo "$line" | awk '{print $2}' | sed 's|^\./||')
  [ -z "$file" ] && continue
  CHECKSUMS="${CHECKSUMS}| \`${file}\` | \`${hash}\` |"$'\n'
done < "$CHECKSUMS_FILE"

# Compose the body. Heredoc-style printf so the structure is readable;
# the output is markdown, no shell expansion inside literal blocks.
{
  printf '> [!NOTE]\n'
  printf '> **Nexira** is an unofficial third-party launcher and is not affiliated with or endorsed by the original game developers.\n\n'

  if [ -n "$HIGHLIGHTS" ]; then
    printf "## What's New\n\n"
    printf '%s\n\n' "$HIGHLIGHTS"
  fi

  # Only officially-supported platforms are listed. The Intel macOS DMG is a
  # community build uploaded out-of-band by build-macos-x86_64-community.yml, so
  # a `-x86_64-community.dmg` asset may appear on the release without a row here.
  printf '## Downloads\n\n'
  printf '| Platform | File |\n|---|---|\n'
  printf '| Windows Installer | [`Nexira-%s-Setup.exe`](%s/Nexira-%s-Setup.exe) |\n' "$APP_VERSION" "$REPO_BASE" "$APP_VERSION"
  printf '| Windows Portable  | [`Nexira-%s-Windows-Portable.zip`](%s/Nexira-%s-Windows-Portable.zip) |\n' "$APP_VERSION" "$REPO_BASE" "$APP_VERSION"
  printf '| Linux AppImage    | [`Nexira-%s-x86_64.AppImage`](%s/Nexira-%s-x86_64.AppImage) |\n' "$APP_VERSION" "$REPO_BASE" "$APP_VERSION"
  printf '| macOS Apple Silicon | [`Nexira-%s-aarch64.dmg`](%s/Nexira-%s-aarch64.dmg) |\n\n' "$APP_VERSION" "$REPO_BASE" "$APP_VERSION"

  printf '<details>\n<summary>SHA256 Checksums</summary>\n\n'
  printf '| File | SHA256 |\n|---|---|\n'
  printf '%s' "$CHECKSUMS"
  printf '\n</details>\n\n'

  printf "## What's Changed\n\n"
  printf '%s\n' "$CHANGELOG_DETAILS"
}

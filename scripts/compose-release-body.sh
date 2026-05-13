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

REPO_BASE="https://github.com/Kitty-Hivens/Aura-Launcher/releases/download/v${APP_VERSION}"

# Extract the ### Highlights section verbatim — the in-app updater renders
# this as the "What's new" panel; the GitHub release body shows it too,
# above the full changelog.
HIGHLIGHTS=$(printf '%s' "$CHANGELOG_NOTES" \
  | awk '/^### Highlights/ {flag=1; next} /^### / {flag=0} flag' \
  | sed '/^[[:space:]]*$/d')

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
  printf '> **Aura Launcher** is an unofficial third-party launcher and is not affiliated with or endorsed by the original game developers.\n\n'

  if [ -n "$HIGHLIGHTS" ]; then
    printf "## What's New\n\n"
    printf '%s\n\n' "$HIGHLIGHTS"
  fi

  printf '## Downloads\n\n'
  printf '| Platform | File |\n|---|---|\n'
  printf '| Windows Installer | [`AuraLauncher-%s-Setup.exe`](%s/AuraLauncher-%s-Setup.exe) |\n' "$APP_VERSION" "$REPO_BASE" "$APP_VERSION"
  printf '| Windows Portable  | [`AuraLauncher-%s-Windows-Portable.zip`](%s/AuraLauncher-%s-Windows-Portable.zip) |\n' "$APP_VERSION" "$REPO_BASE" "$APP_VERSION"
  printf '| Linux AppImage    | [`AuraLauncher-%s-x86_64.AppImage`](%s/AuraLauncher-%s-x86_64.AppImage) |\n' "$APP_VERSION" "$REPO_BASE" "$APP_VERSION"
  printf '| macOS Apple Silicon | [`AuraLauncher-%s-aarch64.dmg`](%s/AuraLauncher-%s-aarch64.dmg) |\n' "$APP_VERSION" "$REPO_BASE" "$APP_VERSION"
  printf '| macOS Intel       | [`AuraLauncher-%s-x86_64.dmg`](%s/AuraLauncher-%s-x86_64.dmg) |\n\n' "$APP_VERSION" "$REPO_BASE" "$APP_VERSION"

  printf '<details>\n<summary>SHA256 Checksums</summary>\n\n'
  printf '| File | SHA256 |\n|---|---|\n'
  printf '%s' "$CHECKSUMS"
  printf '\n</details>\n\n'

  printf "## What's Changed\n\n"
  printf '%s\n' "$CHANGELOG_NOTES"
}

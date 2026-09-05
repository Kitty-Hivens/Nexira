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
#   PLAYER_NOTES      — the same version's section of CHANGELOG_EN.md, the
#                       player-facing notes. Optional: empty for a nightly and
#                       for any release nobody wrote notes for.
#   CHECKSUMS_FILE    — path to dist/SHA256SUMS.txt (default: dist/SHA256SUMS.txt)
#
# Output: writes the composed markdown to stdout.
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

: "${APP_VERSION:?APP_VERSION is required (e.g. 2.2.9)}"
: "${CHANGELOG_NOTES:?CHANGELOG_NOTES is required (markdown body for this version)}"
PLAYER_NOTES="${PLAYER_NOTES:-}"
CHECKSUMS_FILE="${CHECKSUMS_FILE:-dist/SHA256SUMS.txt}"

REPO_BASE="https://github.com/Kitty-Hivens/Nexira/releases/download/v${APP_VERSION}"

# The two halves come from two files now and neither is cut out of the other:
# What's New is CHANGELOG_EN.md as written for a player, What's Changed is the
# engineering log. A release with no player notes prints no What's New heading
# rather than an empty one.
# Blank lines are kept: the block is a paragraph, a heading and a list now, not
# the flat bullet run the old extraction squeezed.
HIGHLIGHTS="$PLAYER_NOTES"
CHANGELOG_DETAILS="$CHANGELOG_NOTES"

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

  if [ -n "$(printf '%s' "$HIGHLIGHTS" | tr -d '[:space:]')" ]; then
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

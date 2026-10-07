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
#   APP_VERSION           version string without the v prefix (e.g. 2.2.9)
#   CHANGELOG_NOTES_FILE  file holding the markdown body of the [APP_VERSION]
#                         CHANGELOG section
#   PLAYER_NOTES_FILE     file holding the same version's section of
#                         CHANGELOG_EN.md, the player-facing notes. Optional:
#                         absent or empty for a nightly and for any release
#                         nobody wrote notes for.
#   CHECKSUMS_FILE        path to dist/SHA256SUMS.txt (default: dist/SHA256SUMS.txt)
#   BODY_LIMIT            the most bytes the body may take (default: 125000)
#
# The notes come in as files because a release's engineering log is past the
# 128 KiB Linux allows a single environment variable.
#
# Output: writes the composed markdown to stdout.
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

: "${APP_VERSION:?APP_VERSION is required (e.g. 2.2.9)}"
: "${CHANGELOG_NOTES_FILE:?CHANGELOG_NOTES_FILE is required (the markdown body for this version)}"
PLAYER_NOTES_FILE="${PLAYER_NOTES_FILE:-}"
CHECKSUMS_FILE="${CHECKSUMS_FILE:-dist/SHA256SUMS.txt}"
# GitHub refuses a release body past 125000 characters. Counted in bytes here,
# which is never fewer, so a body that passes this passes there.
BODY_LIMIT="${BODY_LIMIT:-125000}"

REPO_BASE="https://github.com/Kitty-Hivens/Nexira/releases/download/v${APP_VERSION}"
CHANGELOG_URL="https://github.com/Kitty-Hivens/Nexira/blob/v${APP_VERSION}/CHANGELOG.md"

# The two halves come from two files now and neither is cut out of the other:
# What's New is CHANGELOG_EN.md as written for a player, What's Changed is the
# engineering log. A release with no player notes prints no What's New heading
# rather than an empty one.
# Blank lines are kept: the block is a paragraph, a heading and a list now, not
# the flat bullet run the old extraction squeezed.
HIGHLIGHTS=""
if [ -n "$PLAYER_NOTES_FILE" ] && [ -f "$PLAYER_NOTES_FILE" ]; then
  HIGHLIGHTS="$(cat "$PLAYER_NOTES_FILE")"
fi
CHANGELOG_DETAILS="$(cat "$CHANGELOG_NOTES_FILE")"

# Build the SHA256 checksum table by walking dist/SHA256SUMS.txt.
CHECKSUMS=""
APPIMAGE_FILE=""
while IFS= read -r line; do
  hash=$(echo "$line" | awk '{print $1}')
  file=$(echo "$line" | awk '{print $2}' | sed 's|^\./||')
  [ -z "$file" ] && continue
  CHECKSUMS="${CHECKSUMS}| \`${file}\` | \`${hash}\` |"$'\n'
  # The AppImage is named by channel rather than version (see build-appimage.sh),
  # so it is read off the list of what was built instead of being spelled here.
  case "$file" in *.AppImage) APPIMAGE_FILE="$file" ;; esac
done < "$CHECKSUMS_FILE"

# Compose the body around the given What's Changed text. Heredoc-style printf so
# the structure is readable; the output is markdown, no shell expansion inside
# literal blocks.
compose() {
  local details="$1"
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
  if [ -n "$APPIMAGE_FILE" ]; then
    printf '| Linux AppImage    | [`%s`](%s/%s) |\n' "$APPIMAGE_FILE" "$REPO_BASE" "$APPIMAGE_FILE"
  fi
  printf '| macOS Apple Silicon | [`Nexira-%s-aarch64.dmg`](%s/Nexira-%s-aarch64.dmg) |\n\n' "$APP_VERSION" "$REPO_BASE" "$APP_VERSION"

  printf '<details>\n<summary>SHA256 Checksums</summary>\n\n'
  printf '| File | SHA256 |\n|---|---|\n'
  printf '%s' "$CHECKSUMS"
  printf '\n</details>\n\n'

  printf "## What's Changed\n\n"
  printf '%s\n' "$details"
}

BODY="$(compose "$CHANGELOG_DETAILS")"
# A log that does not fit is linked rather than cut. Cut, it would end in the
# middle of an entry with nothing saying the rest exists. The launcher reads
# this section as the fallback notes and shows the link as it is.
if [ "$(printf '%s' "$BODY" | wc -c)" -gt "$BODY_LIMIT" ]; then
  BODY="$(compose "The engineering log for this release is longer than a release page can hold. It is in [CHANGELOG.md](${CHANGELOG_URL}) as of this tag.")"
fi
printf '%s\n' "$BODY"

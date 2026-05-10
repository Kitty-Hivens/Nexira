#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# Inject a <release> entry into resources/<id>.metainfo.xml at build time.
#
# Inputs:
#   $1                    semantic version (e.g. 2.2.7 or 2.2.7-rc1)
#
# Optional env:
#   DATE=YYYY-MM-DD       release date; defaults to today UTC
#   METAINFO=<path>       file to edit; defaults to the project's only metainfo
#   REPO_BASE=<url>       release URL prefix; defaults to the GitHub releases page
#
# Replaces the previous sed approach. sed-on-XML is fragile to attribute
# order, whitespace, and the placement of XML namespaces; one prettier
# whitespace edit upstream silently breaks the regex. xmlstarlet parses the
# real DOM and inserts a structured node, so the script keeps working as
# long as the schema does.
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

APP_VERSION="${1:?usage: $0 <version>}"
DATE="${DATE:-$(date -u +%Y-%m-%d)}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
METAINFO="${METAINFO:-$ROOT/resources/io.github.kitty_hivens.auralauncher.metainfo.xml}"
REPO_BASE="${REPO_BASE:-https://github.com/Kitty-Hivens/Aura-Launcher}"
DETAIL_URL="${REPO_BASE}/releases/tag/v${APP_VERSION}"

[ -f "$METAINFO" ] || { echo "error: metainfo file not found: $METAINFO" >&2; exit 1; }
command -v xmlstarlet >/dev/null || { echo "error: xmlstarlet not in PATH" >&2; exit 1; }

# xmlstarlet 'ed' chains operations against the live document. -i (insert)
# adds a sibling node BEFORE the matched node; -s (subnode) adds a child;
# attributes are inserted onto the matched node. After step 1, the new
# (empty) <release> becomes /component/releases/release[1]; subsequent
# steps decorate it with version, date, and a <url type="details"> child.
xmlstarlet ed -L \
    -i "/component/releases/release[1]" -t elem -n release -v "" \
    -i "/component/releases/release[1]" -t attr -n version -v "$APP_VERSION" \
    -i "/component/releases/release[1]" -t attr -n date    -v "$DATE" \
    -s "/component/releases/release[1]" -t elem -n url -v "$DETAIL_URL" \
    -i "/component/releases/release[1]/url" -t attr -n type -v "details" \
    "$METAINFO"

echo "Injected <release version=\"$APP_VERSION\" date=\"$DATE\"> into $METAINFO"

#!/usr/bin/env bash
# ============================================================================
# verify-release.sh — Pre-release update pipeline smoke test
#
# Usage:
#   ./scripts/verify-release.sh                     # check latest release
#   ./scripts/verify-release.sh v2.0.0              # check specific tag
#   ./scripts/verify-release.sh --draft             # check latest draft
#   DRY_RUN=1 ./scripts/verify-release.sh v2.0.0   # skip downloads
#
# What it does:
#   1. Fetches release metadata from GitHub API
#   2. Verifies all expected assets exist and follow naming conventions
#   3. Downloads each installer asset and verifies SHA256 (unless DRY_RUN)
#   4. Simulates what UpdateService.findAssetForCurrentOS() would select
#   5. Reports pass/fail for each check
#
# Requires: curl, jq, sha256sum (or shasum on macOS)
# ============================================================================

set -euo pipefail

REPO="Kitty-Hivens/Aura-Launcher"
API_BASE="https://api.github.com/repos/$REPO"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

pass=0
fail=0
warn=0

ok()   { ((pass++)); echo -e "  ${GREEN}✓${NC} $1"; }
fail() { ((fail++)); echo -e "  ${RED}✗${NC} $1"; }
warn() { ((warn++)); echo -e "  ${YELLOW}⚠${NC} $1"; }
info() { echo -e "  ${CYAN}ℹ${NC} $1"; }

# ── Resolve SHA256 command ─────────────────────────────────────────────────

if command -v sha256sum &>/dev/null; then
    SHA_CMD="sha256sum"
elif command -v shasum &>/dev/null; then
    SHA_CMD="shasum -a 256"
else
    echo "ERROR: sha256sum or shasum not found"
    exit 1
fi

# ── Parse arguments ────────────────────────────────────────────────────────

TAG="${1:-latest}"
DRY_RUN="${DRY_RUN:-0}"
GITHUB_TOKEN="${GITHUB_TOKEN:-}"

AUTH_HEADER=""
if [[ -n "$GITHUB_TOKEN" ]]; then
    AUTH_HEADER="Authorization: Bearer $GITHUB_TOKEN"
fi

fetch() {
    if [[ -n "$AUTH_HEADER" ]]; then
        curl -sfL -H "$AUTH_HEADER" -H "Accept: application/vnd.github.v3+json" "$1"
    else
        curl -sfL -H "Accept: application/vnd.github.v3+json" "$1"
    fi
}

# ── Fetch release ──────────────────────────────────────────────────────────

echo ""
echo "═══════════════════════════════════════════════════════════"
echo " Aura Launcher — Release Verification"
echo "═══════════════════════════════════════════════════════════"
echo ""

if [[ "$TAG" == "latest" ]]; then
    info "Fetching latest release..."
    RELEASE_JSON=$(fetch "$API_BASE/releases/latest") || {
        fail "Could not fetch latest release (maybe no releases yet?)"
        exit 1
    }
elif [[ "$TAG" == "--draft" ]]; then
    info "Fetching latest draft release..."
    RELEASE_JSON=$(fetch "$API_BASE/releases" | jq '[ .[] | select(.draft == true) ] | first') || {
        fail "Could not fetch draft releases (need GITHUB_TOKEN with repo access)"
        exit 1
    }
    if [[ "$RELEASE_JSON" == "null" || -z "$RELEASE_JSON" ]]; then
        fail "No draft release found"
        exit 1
    fi
else
    info "Fetching release $TAG..."
    RELEASE_JSON=$(fetch "$API_BASE/releases/tags/$TAG") || {
        fail "Could not fetch release $TAG"
        exit 1
    }
fi

VERSION=$(echo "$RELEASE_JSON" | jq -r '.tag_name')
NAME=$(echo "$RELEASE_JSON" | jq -r '.name')
BODY=$(echo "$RELEASE_JSON" | jq -r '.body // ""')
IS_PRERELEASE=$(echo "$RELEASE_JSON" | jq -r '.prerelease')
IS_DRAFT=$(echo "$RELEASE_JSON" | jq -r '.draft')
ASSET_COUNT=$(echo "$RELEASE_JSON" | jq '.assets | length')

echo " Release:    $NAME"
echo " Tag:        $VERSION"
echo " Draft:      $IS_DRAFT"
echo " Prerelease: $IS_PRERELEASE"
echo " Assets:     $ASSET_COUNT"
echo ""

# ── 1. Check expected assets ──────────────────────────────────────────────

echo "── Asset naming conventions ──────────────────────────────"

VER_NUM="${VERSION#v}"

check_asset() {
    local pattern="$1"
    local label="$2"
    local found
    found=$(echo "$RELEASE_JSON" | jq -r ".assets[].name" | grep -c "$pattern" || true)
    if [[ "$found" -ge 1 ]]; then
        ok "$label found"
    else
        fail "$label MISSING (expected pattern: $pattern)"
    fi
}

check_asset "Setup\.exe$"     "Windows Installer (.exe)"
check_asset "Portable\.zip$"  "Windows Portable (.zip)"
check_asset "\.AppImage$"     "Linux AppImage"
check_asset "\.dmg$"          "macOS DMG"
check_asset "SHA256SUMS"      "SHA256 checksums file"

echo ""

# ── 2. Verify UpdateService asset detection ───────────────────────────────

echo "── UpdateService.findAssetForCurrentOS() simulation ──────"

# Windows: must select Setup.exe (NOT Portable.zip or SHA256SUMS.txt)
WIN_ASSET=$(echo "$RELEASE_JSON" | jq -r '[.assets[] | select(.name | test("Setup.*\\.exe$"))] | first | .name // "NONE"')
if [[ "$WIN_ASSET" != "NONE" ]]; then
    ok "Windows → $WIN_ASSET"
else
    fail "Windows → no Setup.exe found (UpdateService will return null!)"
fi

# Verify it does NOT contain "Portable" (the old .msi bug variant)
MSI_ASSET=$(echo "$RELEASE_JSON" | jq -r '[.assets[] | select(.name | test("\\.msi$"))] | first | .name // "NONE"')
if [[ "$MSI_ASSET" == "NONE" ]]; then
    ok "No .msi artifact (correct — we use Inno Setup .exe)"
else
    warn ".msi artifact found ($MSI_ASSET) — UpdateService ignores this"
fi

MAC_ASSET=$(echo "$RELEASE_JSON" | jq -r '[.assets[] | select(.name | test("\\.dmg$"))] | first | .name // "NONE"')
if [[ "$MAC_ASSET" != "NONE" ]]; then
    ok "macOS  → $MAC_ASSET"
else
    fail "macOS  → no .dmg found"
fi

LIN_ASSET=$(echo "$RELEASE_JSON" | jq -r '[.assets[] | select(.name | test("\\.AppImage$"))] | first | .name // "NONE"')
if [[ "$LIN_ASSET" != "NONE" ]]; then
    ok "Linux  → $LIN_ASSET"
else
    fail "Linux  → no .AppImage found"
fi

echo ""

# ── 3. Check release body has checksums ───────────────────────────────────

echo "── Checksum section in release body ──────────────────────"

if echo "$BODY" | grep -qi "SHA256"; then
    ok "Release body contains SHA256 section"
else
    warn "Release body does not mention SHA256 — checksums won't be verified by UpdateService"
fi

# Try to extract checksums for each installer
for ASSET_NAME in "$WIN_ASSET" "$MAC_ASSET" "$LIN_ASSET"; do
    [[ "$ASSET_NAME" == "NONE" ]] && continue

    # Match markdown table: | `filename` | `hash` |
    HASH=$(echo "$BODY" | grep -oP "\`${ASSET_NAME}\`\s*\|\s*\`\K[a-fA-F0-9]{64}" || true)
    if [[ -z "$HASH" ]]; then
        # Match plain text: SHA256: filename - hash
        HASH=$(echo "$BODY" | grep -oP "SHA256:\s*${ASSET_NAME}\s*-\s*\K[a-fA-F0-9]{64}" || true)
    fi

    if [[ -n "$HASH" ]]; then
        ok "Checksum for $ASSET_NAME: ${HASH:0:16}..."
    else
        warn "No checksum found for $ASSET_NAME in release body"
    fi
done

echo ""

# ── 4. Download and verify (unless DRY_RUN) ──────────────────────────────

if [[ "$DRY_RUN" == "1" ]]; then
    info "DRY_RUN=1 — skipping downloads"
else
    echo "── Download & SHA256 verification ────────────────────────"

    TMPDIR=$(mktemp -d)
    # shellcheck disable=SC2064
    trap "rm -rf $TMPDIR" EXIT

    # Download SHA256SUMS.txt first
    SUMS_URL=$(echo "$RELEASE_JSON" | jq -r '.assets[] | select(.name == "SHA256SUMS.txt") | .browser_download_url // ""')
    if [[ -n "$SUMS_URL" ]]; then
        curl -sfL -o "$TMPDIR/SHA256SUMS.txt" "$SUMS_URL"
        ok "Downloaded SHA256SUMS.txt"
    else
        warn "SHA256SUMS.txt not found in assets — skipping hash verification"
    fi

    # Download and verify each installer
    echo "$RELEASE_JSON" | jq -r '.assets[] | select(.name | test("\\.(exe|dmg|AppImage)$")) | "\(.name)\t\(.browser_download_url)\t\(.size)"' | \
    while IFS=$'\t' read -r NAME URL SIZE; do
        SIZE_MB=$((SIZE / 1024 / 1024))
        info "Downloading $NAME (${SIZE_MB} MB)..."

        if curl -sfL -o "$TMPDIR/$NAME" "$URL"; then
            ok "Downloaded $NAME"

            # Verify checksum if we have SHA256SUMS.txt
            if [[ -f "$TMPDIR/SHA256SUMS.txt" ]]; then
                EXPECTED=$(grep "$NAME" "$TMPDIR/SHA256SUMS.txt" | awk '{print $1}')
                if [[ -n "$EXPECTED" ]]; then
                    ACTUAL=$($SHA_CMD "$TMPDIR/$NAME" | awk '{print $1}')
                    if [[ "${ACTUAL,,}" == "${EXPECTED,,}" ]]; then
                        ok "SHA256 verified: $NAME"
                    else
                        fail "SHA256 MISMATCH: $NAME"
                        info "  Expected: $EXPECTED"
                        info "  Actual:   $ACTUAL"
                    fi
                else
                    warn "No checksum entry for $NAME in SHA256SUMS.txt"
                fi
            fi
        else
            fail "Download failed: $NAME"
        fi
    done
fi

echo ""

# ── 5. Version sanity ────────────────────────────────────────────────────

echo "── Version sanity checks ─────────────────────────────────"

if [[ "$VERSION" =~ ^v[0-9]+\.[0-9]+\.[0-9]+ ]]; then
    ok "Tag follows semver: $VERSION"
else
    fail "Tag does not follow semver: $VERSION"
fi

if echo "$NAME" | grep -q "$VER_NUM"; then
    ok "Release name contains version number"
else
    warn "Release name does not contain version: $NAME"
fi

echo ""

# ── Summary ───────────────────────────────────────────────────────────────

echo "═══════════════════════════════════════════════════════════"
echo -e " Results: ${GREEN}$pass passed${NC}, ${RED}$fail failed${NC}, ${YELLOW}$warn warnings${NC}"
echo "═══════════════════════════════════════════════════════════"
echo ""

if [[ $fail -gt 0 ]]; then
    echo -e "${RED}RELEASE VERIFICATION FAILED${NC}"
    echo "Fix the issues above before publishing."
    exit 1
else
    echo -e "${GREEN}RELEASE VERIFICATION PASSED${NC}"
    exit 0
fi

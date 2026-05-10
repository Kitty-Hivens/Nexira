#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# Regenerate every icon variant from the canonical sources in resources/branding/.
# Run after editing app-icon.png or tray-icon.png; commit the resulting derived
# files so the build stays hermetic for contributors who don't have ImageMagick.
#
# Sources:
#   resources/branding/app-icon.png   — detailed launcher identity
#   resources/branding/tray-icon.png  — simplified silhouette for runtime tray
#
# Outputs:
#   client-ui/src/commonMain/composeResources/drawable/icon.png      (1024×1024)
#   client-ui/src/commonMain/composeResources/drawable/favicon.png   (64×64)
#   resources/icons/icon.ico                                         (Inno Setup + Windows Compose iconFile)
#   resources/icons/256x256.png                                      (AppImage)
#   resources/icons/512x512.png                                      (AppImage)
#
# Note: icon.ico is intentionally NOT under composeResources/drawable/.
# Compose Resources groups by stem, so colocating icon.ico with icon.png
# makes Res.drawable.icon ambiguous and crashes painterResource at runtime.
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

# ── ImageMagick selection ───────────────────────────────────────────────────
if   command -v magick  >/dev/null 2>&1; then IM=(magick)
elif command -v convert >/dev/null 2>&1; then IM=(convert)
else
    echo "error: ImageMagick not found (need 'magick' or 'convert' in PATH)" >&2
    exit 1
fi

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP_SRC="$ROOT/resources/branding/app-icon.png"
TRAY_SRC="$ROOT/resources/branding/tray-icon.png"
DRAWABLE="$ROOT/client-ui/src/commonMain/composeResources/drawable"
HICOLOR="$ROOT/resources/icons"

[[ -f "$APP_SRC"  ]] || { echo "error: $APP_SRC missing"  >&2; exit 1; }
[[ -f "$TRAY_SRC" ]] || { echo "error: $TRAY_SRC missing" >&2; exit 1; }

mkdir -p "$DRAWABLE" "$HICOLOR"

# ── App icon ────────────────────────────────────────────────────────────────
echo "── app-icon.png  →  variants ──────────────────────────────────────────"

# Compose desktop Linux iconFile — generous resolution for HiDPI scaling.
"${IM[@]}" "$APP_SRC" -resize 1024x1024 -strip -define png:compression-level=9 \
    "$DRAWABLE/icon.png"

# AppImage hicolor sizes (referenced by build_release.yml).
"${IM[@]}" "$APP_SRC" -resize 256x256 -strip -define png:compression-level=9 \
    "$HICOLOR/256x256.png"
"${IM[@]}" "$APP_SRC" -resize 512x512 -strip -define png:compression-level=9 \
    "$HICOLOR/512x512.png"

# Multi-size .ico for Windows. Single-size ICO (the prior state) renders
# blurry at 16/32/48px in Explorer; pack the standard ladder so Windows
# can pick the closest match. Lives under resources/icons/, NOT drawable/
# — see header note about Compose Resources stem-grouping.
"${IM[@]}" "$APP_SRC" \
    \( -clone 0 -resize 256x256 \) \
    \( -clone 0 -resize 128x128 \) \
    \( -clone 0 -resize 64x64   \) \
    \( -clone 0 -resize 48x48   \) \
    \( -clone 0 -resize 32x32   \) \
    \( -clone 0 -resize 16x16   \) \
    -delete 0 \
    "$HICOLOR/icon.ico"

# ── Tray icon ───────────────────────────────────────────────────────────────
echo "── tray-icon.png  →  favicon.png ──────────────────────────────────────"

"${IM[@]}" "$TRAY_SRC" -resize 64x64 -strip -define png:compression-level=9 \
    "$DRAWABLE/favicon.png"

echo
echo "done. sources:"
echo "  $APP_SRC"
echo "  $TRAY_SRC"
echo "regenerated:"
ls -la \
    "$DRAWABLE/icon.png" \
    "$DRAWABLE/favicon.png" \
    "$HICOLOR/icon.ico" \
    "$HICOLOR/256x256.png" \
    "$HICOLOR/512x512.png"

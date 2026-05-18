#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# Assemble a Linux AppImage from a Compose-Desktop fat jar.
#
# Inputs:
#   $1                    semantic version (e.g. 2.2.7 or 2.2.7-rc1)
#   $2                    path to the release uber-jar
#                         (typically client-ui/build/compose/jars/*-release*.jar)
#
# Optional env:
#   APPDIR=AppDir         scratch directory for AppImage contents
#   ARCH=x86_64           appimagetool architecture
#   OUTPUT=<derived>      final .AppImage path; defaults to
#                         AuraLauncher-<version>-<arch>.AppImage in CWD
#
# Requires on PATH: jlink (from JDK), appimagetool (continuous build).
#
# Why this lives in a script: the inline yaml in build_release.yml grew to
# ~50 lines with a heredoc, three mkdir trees, four cp blocks and an
# appimagetool invocation — testable locally as a script, opaque inline.
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

APP_VERSION="${1:?usage: $0 <version> <jar-path>}"
JAR="${2:?usage: $0 <version> <jar-path>}"
APPDIR="${APPDIR:-AppDir}"
ARCH="${ARCH:-x86_64}"
OUTPUT="${OUTPUT:-AuraLauncher-${APP_VERSION}-${ARCH}.AppImage}"

[ -f "$JAR" ] || { echo "error: jar not found: $JAR" >&2; exit 1; }
[ -d "$APPDIR" ] && { echo "error: $APPDIR already exists; refusing to overwrite" >&2; exit 1; }

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# ── 1. Minimal JRE via jlink ────────────────────────────────────────────────
# jlink refuses to write into an existing directory, so it has to run before
# any of the mkdir steps below.
# --vm=server: drop client + minimal HotSpot variants (saves ~22 MB on
#   the bundled runtime; the launcher is a long-running Compose app, the
#   client VM's faster-startup-slower-steady-state trade-off was always
#   the wrong shape for us).
# --include-locales=en,ru,de + jdk.localedata: keep locale-aware
#   formatting paths (DateTimeFormatter, NumberFormat, Collator) working
#   for the three locales Aura's i18n actually ships
#   (EnglishStrings / RussianStrings / GermanStrings). Without the
#   restriction, --add-modules jdk.localedata would pull in the full
#   ~50 MB locale dataset; without jdk.localedata at all, anything not
#   in java.base's en_US fallback silently degrades.
# --compress=zip-9: modern syntax (the old `--compress=2` form is
#   deprecated in JDK 21+ and emits a warning on every build).
jlink \
    --output "$APPDIR/usr" \
    --add-modules java.base,java.desktop,java.logging,java.management,java.prefs,jdk.crypto.ec,jdk.unsupported,jdk.zipfs,jdk.localedata \
    --vm=server \
    --include-locales=en,ru,de \
    --no-header-files \
    --no-man-pages \
    --strip-debug \
    --compress=zip-9

# ── 2. Remaining subdirs (usr/bin already exists from jlink) ────────────────
mkdir -p \
    "$APPDIR/usr/lib" \
    "$APPDIR/usr/share/applications" \
    "$APPDIR/usr/share/icons/hicolor/256x256/apps" \
    "$APPDIR/usr/share/icons/hicolor/512x512/apps" \
    "$APPDIR/usr/share/metainfo"

# ── 3. AppRun entry-point ───────────────────────────────────────────────────
# WM_CLASS hygiene: Main.kt reflects into sun.awt.X11.XToolkit.awtAppClassName
# before the first window is created so the X11 WM_CLASS hint matches
# StartupWMClass=AuraLauncher in resources/aura-launcher.desktop. The
# reflection is JPMS-guarded behind --add-opens=java.desktop/sun.awt.X11.
# Stock OpenJDK derives WM_CLASS from argv[0] by default; without the
# reflection the launcher would show up as "java" in the taskbar. The fat
# jar bypasses the Compose-generated launcher script, so the jvmArgs in
# client-ui/build.gradle.kts do not flow through here -- the AppRun is the
# only place where flags actually reach the AppImage runtime.
cat > "$APPDIR/AppRun" << EOF
#!/bin/sh
HERE="\$(dirname "\$(readlink -f "\$0")")"
exec "\$HERE/usr/bin/java" \\
     --add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED \\
     --enable-native-access=ALL-UNNAMED \\
     -jar "\$HERE/usr/lib/aura-launcher.jar" \\
     "\$@"
EOF
chmod +x "$APPDIR/AppRun"

# ── 4. Copy assets ──────────────────────────────────────────────────────────
cp "$JAR" "$APPDIR/usr/lib/aura-launcher.jar"
cp "$ROOT/resources/aura-launcher.desktop" "$APPDIR/usr/share/applications/"
cp "$ROOT/resources/aura-launcher.desktop" "$APPDIR/"
cp "$ROOT/resources/icons/256x256.png" "$APPDIR/usr/share/icons/hicolor/256x256/apps/aura-launcher.png"
cp "$ROOT/resources/icons/512x512.png" "$APPDIR/usr/share/icons/hicolor/512x512/apps/aura-launcher.png"
cp "$ROOT/resources/icons/256x256.png" "$APPDIR/aura-launcher.png"
# Provide both filenames so appimagetool finds the AppStream metadata under
# either of the two names it has historically searched.
cp "$ROOT/resources/io.github.kitty_hivens.auralauncher.metainfo.xml" \
   "$APPDIR/usr/share/metainfo/io.github.kitty_hivens.auralauncher.metainfo.xml"
cp "$ROOT/resources/io.github.kitty_hivens.auralauncher.metainfo.xml" \
   "$APPDIR/usr/share/metainfo/io.github.kitty_hivens.auralauncher.appdata.xml"

# ── 5. Build AppImage ───────────────────────────────────────────────────────
ARCH="$ARCH" appimagetool "$APPDIR" "$OUTPUT"

echo "AppImage written to $OUTPUT"

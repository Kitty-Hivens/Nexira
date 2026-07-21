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
#                         Nexira-<version>-<arch>.AppImage in CWD
#   PACKAGING_PROFILE     override path to the generated packaging
#                         profile (defaults to the standard
#                         client-ui/build/generated/packaging/
#                         packaging-profile.sh, produced by the
#                         `:client-ui:emitAppImageProfile` gradle task)
#
# Requires on PATH: jlink (from JDK), appimagetool (continuous build),
# zip + unzip (Info-ZIP -- the foreign-JNA strip below).
#
# Why this lives in a script: the inline yaml in build_release.yml grew to
# ~50 lines with a heredoc, three mkdir trees, four cp blocks and an
# appimagetool invocation — testable locally as a script, opaque inline.
#
# jlink module list + flag set live in the packaging profile, NOT inline
# here. Single source of truth is the `packaging { ... }` block in
# client-ui/build.gradle.kts; the gradle `emitAppImageProfile` task
# materializes it into the shell-sourceable file we consume below.
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

APP_VERSION="${1:?usage: $0 <version> <jar-path>}"
JAR="${2:?usage: $0 <version> <jar-path>}"
APPDIR="${APPDIR:-AppDir}"
ARCH="${ARCH:-x86_64}"
OUTPUT="${OUTPUT:-Nexira-${APP_VERSION}-${ARCH}.AppImage}"

[ -f "$JAR" ] || { echo "error: jar not found: $JAR" >&2; exit 1; }
[ -d "$APPDIR" ] && { echo "error: $APPDIR already exists; refusing to overwrite" >&2; exit 1; }

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# ── Packaging profile (single source of truth for jlink flags) ──────────────
PACKAGING_PROFILE="${PACKAGING_PROFILE:-$ROOT/client-ui/build/generated/packaging/packaging-profile.sh}"
if [ ! -f "$PACKAGING_PROFILE" ]; then
    echo "error: packaging profile not found at $PACKAGING_PROFILE" >&2
    echo "       run \`./gradlew :client-ui:emitAppImageProfile\` first," >&2
    echo "       or set PACKAGING_PROFILE=<path> to override." >&2
    exit 1
fi
# Populates AURA_JLINK_MODULES (comma-joined string) and AURA_JLINK_OPTIONS
# (bash array). See buildSrc/.../EmitAppImageProfileTask.kt for the writer.
source "$PACKAGING_PROFILE"

# ── 1. Minimal JRE via jlink ────────────────────────────────────────────────
# jlink refuses to write into an existing directory, so it has to run before
# any of the mkdir steps below.
# Module list and flag set come from the sourced packaging profile above.
# Rationale per flag lives in the PackagingExtension KDoc + the per-flag
# comments in client-ui/build.gradle.kts. Headline: --vm=server (-22 MB),
# --include-locales=en,ru,de + jdk.localedata (keeps i18n-relevant locale
# data, prunes the rest), --strip-debug / --no-header-files / --no-man-pages
# (size hygiene). No inner --compress: the outer squashfs-zstd compressor
# squeezes a non-zip-9 runtime image harder than it can a pre-compressed one.
jlink \
    --output "$APPDIR/usr" \
    --add-modules "$AURA_JLINK_MODULES" \
    "${AURA_JLINK_OPTIONS[@]}"

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
# StartupWMClass=Nexira in resources/nexira.desktop. The
# reflection is JPMS-guarded behind --add-opens=java.desktop/sun.awt.X11.
# Stock OpenJDK derives WM_CLASS from argv[0] by default; without the
# reflection the launcher would show up as "java" in the taskbar. The fat
# jar bypasses the Compose-generated launcher script, so the jvmArgs in
# client-ui/build.gradle.kts do not flow through here -- the AppRun is the
# only place where flags reach the AppImage runtime, so the ones that matter
# are mirrored here: the Linux AWT/X11 rendering + tiling-WM hints
# (_JAVA_AWT_WM_NONREPARENTING fixes AWT window sizing under tiling WMs like
# Hyprland/sway), the sun.nio.ch open some deps reflect through, and G1 +
# string dedup. Heap caps are deliberately NOT mirrored: the default (1/4 RAM)
# suits a GUI that spikes on skins / 3D / backgrounds better than a hard
# -Xmx512m. (Windows/macOS still cap via jpackage --java-options; reconcile
# separately.)
cat > "$APPDIR/AppRun" << EOF
#!/bin/sh
HERE="\$(dirname "\$(readlink -f "\$0")")"
export MALLOC_ARENA_MAX=2
exec "\$HERE/usr/bin/java" \\
     --add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED \\
     --add-opens=java.base/sun.nio.ch=ALL-UNNAMED \\
     --enable-native-access=ALL-UNNAMED \\
     -Dawt.useSystemAAFontSettings=on \\
     -Djdk.gtk.version=3 \\
     -D_JAVA_AWT_WM_NONREPARENTING=1 \\
     -Drobot.need_x11=false \\
     -XX:+UseG1GC \\
     -XX:+UseStringDeduplication \\
     -jar "\$HERE/usr/lib/nexira.jar" \\
     "\$@"
EOF
chmod +x "$APPDIR/AppRun"

# ── 4. Copy assets ──────────────────────────────────────────────────────────
cp "$JAR" "$APPDIR/usr/lib/nexira.jar"

# Strip foreign JNA dispatchers. This is a linux-x86-64 AppImage, and JNA (pulled
# by FileKit for native file dialogs) only ever loads com/sun/jna/linux-x86-64/.
# The jar bundles ~30 platforms of libjnidispatch (~5 MB of incompressible .so);
# drop every one but the host. `zip -d` rewrites the central directory and copies
# the remaining entries' bytes verbatim, so the STORED method the gradle uber-jar
# post-process applied is preserved (no re-compression).
# Match only the native dispatcher blobs (libjnidispatch.so / .jnilib / .a,
# jnidispatch.dll), NOT the com/sun/jna/{platform,internal,win32,...} Java
# packages that also live one level under com/sun/jna/.
mapfile -t _jna_foreign < <(
    unzip -Z1 "$APPDIR/usr/lib/nexira.jar" 'com/sun/jna/*' 2>/dev/null \
        | grep -E '/(libjnidispatch\.(so|jnilib|a)|jnidispatch\.dll)$' \
        | grep -v '^com/sun/jna/linux-x86-64/' || true
)
if [ "${#_jna_foreign[@]}" -gt 0 ]; then
    zip -q -d "$APPDIR/usr/lib/nexira.jar" "${_jna_foreign[@]}"
fi
cp "$ROOT/resources/nexira.desktop" "$APPDIR/usr/share/applications/"
cp "$ROOT/resources/nexira.desktop" "$APPDIR/"
cp "$ROOT/resources/icons/256x256.png" "$APPDIR/usr/share/icons/hicolor/256x256/apps/nexira.png"
cp "$ROOT/resources/icons/512x512.png" "$APPDIR/usr/share/icons/hicolor/512x512/apps/nexira.png"
cp "$ROOT/resources/icons/256x256.png" "$APPDIR/nexira.png"
# Provide both filenames so appimagetool finds the AppStream metadata under
# either of the two names it has historically searched.
cp "$ROOT/resources/dev.hivens.nexira.metainfo.xml" \
   "$APPDIR/usr/share/metainfo/dev.hivens.nexira.metainfo.xml"
cp "$ROOT/resources/dev.hivens.nexira.metainfo.xml" \
   "$APPDIR/usr/share/metainfo/dev.hivens.nexira.appdata.xml"

# ── 5. Build AppImage ───────────────────────────────────────────────────────
# Squashfs compression: zstd at max level, 1 MB blocks (the mksquashfs ceiling).
# appimagetool already defaults to zstd, but at the mksquashfs default level (15)
# with 128 KB blocks; pinning level 22 + 1 MB blocks lets the single squashfs pass
# get the most out of the now-STORED uber jar (see the release-uber-jar post-process
# in client-ui/build.gradle.kts). The `=` form keeps the leading-dash mksquashfs
# flags from being mis-parsed as appimagetool options.
ARCH="$ARCH" appimagetool \
    --comp zstd \
    --mksquashfs-opt=-Xcompression-level --mksquashfs-opt=22 \
    --mksquashfs-opt=-b --mksquashfs-opt=1M \
    "$APPDIR" "$OUTPUT"

echo "AppImage written to $OUTPUT"

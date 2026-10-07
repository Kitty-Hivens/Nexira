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
#                         Nexira-<arch>.AppImage in CWD, or
#                         Nexira-nightly-<arch>.AppImage for a nightly.
#                         Also written to $GITHUB_OUTPUT as `path` when
#                         that is set, so a workflow reads the name
#                         from here instead of deriving it again. A
#                         release also gets $OUTPUT.zsync, written there
#                         as `zsync` (empty for any other build).
#   PACKAGING_PROFILE     override path to the generated packaging
#                         profile (defaults to the standard
#                         client-ui/build/generated/packaging/
#                         packaging-profile.sh, produced by the
#                         `:client-ui:emitAppImageProfile` gradle task)
#
# Requires on PATH: jlink (from JDK), appimagetool (continuous build),
# zip + unzip (Info-ZIP -- the foreign-JNA strip below), sha256sum (coreutils --
# the class-data archive name).
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
# The file name carries the channel and the architecture, never the version. An
# update replaces the file the user already has, so a version in the name would
# soon describe a build the file no longer holds. Release and beta are one
# install and share a name. A nightly is kept beside a release, so it has its
# own. The suffix is read the way ReleaseChannel.classify reads it, and
# UpdateService.linuxAssetName asks for the same names.
CHANNEL_PART=""
VERSION_SUFFIX="${APP_VERSION#*-}"
if [ "$VERSION_SUFFIX" != "$APP_VERSION" ]; then
    case "${VERSION_SUFFIX,,}" in
        nightly*) CHANNEL_PART="-nightly" ;;
    esac
fi
OUTPUT="${OUTPUT:-Nexira${CHANNEL_PART}-${ARCH}.AppImage}"

# Update information for AppImageUpdate and the tools built on it. They decide
# that an update exists by comparing the file's hash with the .zsync, not by
# version, and `latest` resolves to /releases/latest. A beta or a nightly
# carrying it would therefore be "updated" back to the release, so only a
# release gets it and the other two are updated by the launcher alone.
# appimagetool writes $OUTPUT.zsync beside the image when it is given.
UPDATE_ARGS=()
ZSYNC=""
if [ "$VERSION_SUFFIX" = "$APP_VERSION" ]; then
    UPDATE_ARGS=(-u "gh-releases-zsync|Kitty-Hivens|Nexira|latest|$(basename "$OUTPUT").zsync")
    ZSYNC="$OUTPUT.zsync"
fi

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
# Populates NEXIRA_JLINK_MODULES (comma-joined string), NEXIRA_JLINK_OPTIONS,
# NEXIRA_JVM_MODULE_OPTIONS and NEXIRA_APPIMAGE_JVM_OPTIONS (bash arrays). See
# buildSrc/.../EmitAppImageProfileTask.kt for the writer.
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
    --add-modules "$NEXIRA_JLINK_MODULES" \
    "${NEXIRA_JLINK_OPTIONS[@]}"

# ── 1b. Base class-data archive ─────────────────────────────────────────────
# Dumped by the image's own java instead of jlink's --generate-cds-archive.
# An archive records the module-system flags in force when it was written, and
# a run whose flags differ gets the archived module graph thrown away with an
# error line per flag ("Mismatched values for property jdk.module.addopens ...
# specified during runtime but not during dump time") on every launch.
# NEXIRA_JVM_MODULE_OPTIONS is the module-system subset of the AppRun's own
# launch line below, so the two sides cannot drift apart.
#
# -Xshare:dump also writes the compressed-oops archive only. jlink additionally
# emitted classes_nocoops.jsa for the >32 GB heap mode the launcher never
# reaches -- ~14 MB of image that had to be deleted right after.
if [ "${NEXIRA_GENERATE_CDS:-0}" = "1" ]; then
    "$APPDIR/usr/bin/java" -Xshare:dump "${NEXIRA_JVM_MODULE_OPTIONS[@]}"
fi

# ── 2. Remaining subdirs (usr/bin already exists from jlink) ────────────────
mkdir -p \
    "$APPDIR/usr/lib" \
    "$APPDIR/usr/share/applications" \
    "$APPDIR/usr/share/icons/hicolor/256x256/apps" \
    "$APPDIR/usr/share/icons/hicolor/512x512/apps" \
    "$APPDIR/usr/share/metainfo"

# ── 3. Copy assets ──────────────────────────────────────────────────────────
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

# ── 4. AppRun entry-point ───────────────────────────────────────────────────
# Written after the jar is final: the class-data archive name below is keyed to
# the jar's digest, which the JNA strip above changes.
#
# The JVM flags are not typed here. They come from the packaging profile as
# NEXIRA_APPIMAGE_JVM_OPTIONS, built in client-ui/build.gradle.kts from the
# launch profile every build shares plus the AppImage's own heap delta, with the
# reasons and measurements for each next to them. A list typed into this script
# had drifted from the other two. Its module-system subset is what the base
# archive in step 1b was dumped under.
#
# What stays here is what only a shell can do: the glibc allocator environment
# and the per-build name of the class-data archive in the user data dir.
NX_JVM_OPTS="${NEXIRA_APPIMAGE_JVM_OPTIONS[*]}"
NX_JAR_ID="$(sha256sum "$APPDIR/usr/lib/nexira.jar" | cut -c1-12)"
cat > "$APPDIR/AppRun" << EOF
#!/bin/sh
HERE="\$(dirname "\$(readlink -f "\$0")")"
export MALLOC_ARENA_MAX=2
# Idle footprint, glibc half. Without a trim threshold the allocator keeps the
# arena high water mark a pack update leaves behind (hundreds of files hashed
# and unpacked at once) for the rest of the session, which on an idle launcher
# is over a hundred megabytes of resident memory nothing is using. Setting it
# also pins the mmap threshold at its 128 KB default (glibc stops raising it
# dynamically once any of these is set from the environment), so the companion
# MALLOC_MMAP_THRESHOLD_ that would say the same thing is left out.
export MALLOC_TRIM_THRESHOLD_=131072
# Application class-data archive. The JVM writes it on the first clean exit, so
# nothing ships in the download and CI needs no training step. It has to live in
# the user data dir because the AppImage is mounted read-only.
#
# The name carries the jar's digest because an archive is bound to the exact jar
# it was dumped from, and a stale one is not re-created: -XX:+AutoCreateSharedArchive
# only covers a missing or version-incompatible archive, so a plain app.jsa left
# over from the previous build would report "shared class paths mismatch" on every
# launch and never recover. A new build therefore starts on a new name, and the
# ones belonging to builds that are gone are cleaned up here.
NX_DATA="\${NEXIRA_DATA_DIR:-\${XDG_DATA_HOME:-\$HOME/.local/share}/nexira}"
mkdir -p "\$NX_DATA" 2>/dev/null || true
NX_ARCHIVE="\$NX_DATA/app-${APP_VERSION}-${NX_JAR_ID}.jsa"
for old in "\$NX_DATA"/app-*.jsa; do
    [ "\$old" = "\$NX_ARCHIVE" ] || rm -f "\$old"
done
# Baked from the packaging profile at build time. Its module-system flags must
# stay the set the base archive in usr/lib/server was dumped under, which they
# are by construction. Word splitting is the point -- each flag is one argv
# element and none of them contain whitespace.
NX_JVM_OPTS="$NX_JVM_OPTS"
# shellcheck disable=SC2086
exec "\$HERE/usr/bin/java" \\
     \$NX_JVM_OPTS \\
     -XX:+AutoCreateSharedArchive \\
     -XX:SharedArchiveFile="\$NX_ARCHIVE" \\
     -jar "\$HERE/usr/lib/nexira.jar" \\
     "\$@"
EOF
chmod +x "$APPDIR/AppRun"

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
    "${UPDATE_ARGS[@]}" \
    "$APPDIR" "$OUTPUT"

echo "AppImage written to $OUTPUT"
if [ -n "$ZSYNC" ] && [ ! -f "$ZSYNC" ]; then
    echo "error: update information was embedded but $ZSYNC was not written" >&2
    exit 1
fi
if [ -n "${GITHUB_OUTPUT:-}" ]; then
    echo "path=$OUTPUT" >> "$GITHUB_OUTPUT"
    echo "zsync=$ZSYNC" >> "$GITHUB_OUTPUT"
fi

#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# wayland-probe — investigative probe for "is JBR really picking WLToolkit?".
#
# Run this on a Linux Wayland session (KDE / GNOME / Hyprland / ...) to
# answer:
#   1. Is the current session actually Wayland (XDG_SESSION_TYPE)?
#   2. Which AWT toolkit does the active JDK resolve to (X11 fallback or
#      native WLToolkit)?
#   3. Are JBR's Wayland-only services (RelativePointerMovement, HiDPIInfo)
#      reachable?
#
# Two run modes:
#   - Default: uses whichever `java` is on PATH. On Liberica/Temurin/etc.
#     this will report XToolkit only — that's expected; those JDKs do not
#     ship WLToolkit.
#   - With AppImage: pass an Aura AppImage path to extract its bundled
#     JBR runtime and probe through that. This is the realistic scenario
#     because shipped artifacts use JBR.
#
# Usage:
#   ./scripts/wayland-probe.sh
#   ./scripts/wayland-probe.sh /path/to/AuraLauncher-2.2.11-x86_64.AppImage
#
# Output: human-readable report. Save the output to attach to the
# Wayland-native investigation in docs/dev/wayland-investigation.md.
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

APPIMAGE="${1:-}"
WORK="$(mktemp -d -t wayland-probe.XXXXXX)"
trap 'rm -rf "$WORK"' EXIT

echo "── Wayland probe — Aura Launcher"
echo "── $(date -Iseconds)"
echo

# ── Section 1: session environment ─────────────────────────────────────────
echo "▼ Session environment"
echo "  XDG_SESSION_TYPE   = ${XDG_SESSION_TYPE:-<unset>}"
echo "  XDG_CURRENT_DESKTOP= ${XDG_CURRENT_DESKTOP:-<unset>}"
echo "  WAYLAND_DISPLAY    = ${WAYLAND_DISPLAY:-<unset>}"
echo "  DISPLAY            = ${DISPLAY:-<unset>}"
echo "  GDK_BACKEND        = ${GDK_BACKEND:-<unset>}"
echo

if [[ "${XDG_SESSION_TYPE:-}" != "wayland" ]]; then
    echo "  ⚠ Not on a Wayland session — toolkit results below are X11-by-design."
    echo "    Re-run from a Wayland session (KDE / GNOME / Hyprland) for a useful probe."
    echo
fi

# ── Section 2: pick a JDK ──────────────────────────────────────────────────
JAVA_BIN=""
if [[ -n "$APPIMAGE" ]]; then
    if [[ ! -f "$APPIMAGE" ]]; then
        echo "✗ AppImage not found: $APPIMAGE" >&2
        exit 1
    fi
    echo "▼ Extracting bundled JBR from $APPIMAGE"
    cp "$APPIMAGE" "$WORK/aura.AppImage"
    chmod +x "$WORK/aura.AppImage"
    (cd "$WORK" && ./aura.AppImage --appimage-extract >/dev/null 2>&1)
    # AppImages built by build-appimage.sh place the JRE under usr/lib/runtime/
    if [[ -x "$WORK/squashfs-root/usr/lib/runtime/bin/java" ]]; then
        JAVA_BIN="$WORK/squashfs-root/usr/lib/runtime/bin/java"
    else
        # Fallback: search for any java binary in the extracted tree.
        JAVA_BIN="$(find "$WORK/squashfs-root" -type f -name java -executable | head -n 1 || true)"
    fi
    if [[ -z "$JAVA_BIN" ]]; then
        echo "✗ No java binary found inside AppImage extraction." >&2
        exit 1
    fi
    echo "  using: $JAVA_BIN"
else
    JAVA_BIN="$(command -v java || true)"
    if [[ -z "$JAVA_BIN" ]]; then
        echo "✗ No java on PATH and no AppImage path provided." >&2
        exit 1
    fi
    echo "▼ No AppImage given — probing PATH java"
    echo "  using: $JAVA_BIN"
fi
echo "  $($JAVA_BIN -version 2>&1 | head -n 2 | sed 's/^/    /')"
echo

# ── Section 3: tiny Java probe ────────────────────────────────────────────
PROBE_DIR="$WORK/probe"
mkdir -p "$PROBE_DIR"
cat > "$PROBE_DIR/ToolkitProbe.java" <<'JAVA'
public class ToolkitProbe {
    public static void main(String[] args) throws Exception {
        // Fence off any window pop — Toolkit init is OK headless.
        System.setProperty("java.awt.headless", "false");

        java.awt.Toolkit tk = java.awt.Toolkit.getDefaultToolkit();
        System.out.println("toolkit.class      = " + tk.getClass().getName());
        System.out.println("awt.toolkit.name   = " + System.getProperty("awt.toolkit.name", "<unset>"));
        System.out.println("awt.toolkit        = " + System.getProperty("awt.toolkit", "<unset>"));

        // Try to peek at JBR Wayland-only services without forcing the
        // jbr-api jar onto the classpath. Reflection through com.jetbrains.JBR
        // resolves only when running on JBR with WLToolkit + the service
        // implemented.
        try {
            Class<?> jbr = Class.forName("com.jetbrains.JBR");
            Object hidpi = jbr.getMethod("getHiDPIInfo").invoke(null);
            System.out.println("JBR.getHiDPIInfo   = " + (hidpi != null ? "available" : "null"));
            Object rpm = jbr.getMethod("getRelativePointerMovement").invoke(null);
            System.out.println("JBR.getRPM         = " + (rpm != null ? "available (Wayland-only)" : "null (not WLToolkit?)"));
        } catch (ClassNotFoundException e) {
            System.out.println("JBR API            = not present (this is not JBR — expected on Liberica/Temurin/...)");
        } catch (Throwable t) {
            System.out.println("JBR API            = error: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }
}
JAVA

# Locate javac. The AppImage runtime jlink build rarely includes javac;
# fall back to system javac when running through AppImage.
JAVAC_BIN=""
if [[ -n "$APPIMAGE" ]]; then
    JAVAC_BIN="$(command -v javac || true)"
    if [[ -z "$JAVAC_BIN" ]]; then
        echo "✗ Need a system javac to compile the probe. Install JDK or run without an AppImage arg." >&2
        exit 1
    fi
else
    JAVAC_BIN="${JAVA_BIN%/java}/javac"
    [[ -x "$JAVAC_BIN" ]] || JAVAC_BIN="$(command -v javac || true)"
fi
"$JAVAC_BIN" -d "$PROBE_DIR" "$PROBE_DIR/ToolkitProbe.java"

run_probe() {
    local label="$1"
    shift
    echo "▼ $label"
    "$JAVA_BIN" "$@" -cp "$PROBE_DIR" ToolkitProbe 2>&1 | sed 's/^/  /'
    echo
}

# Auto-selection: whatever JBR / JDK picks given the session.
run_probe "Default toolkit selection (auto)"

# Forced X11 — guaranteed-portable baseline.
run_probe "Forced -Dawt.toolkit.name=XToolkit" -Dawt.toolkit.name=XToolkit

# Forced WLToolkit — only meaningful on JBR; OpenJDK forks will fall back
# silently and report XToolkit again, which is itself a useful data point.
run_probe "Forced -Dawt.toolkit.name=WLToolkit" -Dawt.toolkit.name=WLToolkit

echo "── Probe complete."
echo "── Attach the output above to docs/dev/wayland-investigation.md or paste"
echo "   it into the next iteration's prompt for the go/no-go decision."

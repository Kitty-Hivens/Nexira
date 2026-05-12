---
title: Wayland-Native — investigation notes
description: Current state of Wayland support in Aura Launcher and a roadmap for going from "XWayland-fallback" to "Wayland-native".
---

## ── Status as of 2026-05-12 ─────────────────────────────────────────────────

Aura Launcher ships today as an **XWayland client**, not a native Wayland
client. Everywhere we set "WM_CLASS" or reach into the JDK toolkit, we
assume `XToolkit` (the X11 backend). On a pure Wayland session the JDK
silently falls back to XWayland; visually nothing breaks, but we lose:

- Native window-class identity (`xdg-shell` `app_id` is not set, so
  GNOME / KDE / Hyprland associate windows by inferred heuristics rather
  than the `.desktop` entry).
- DPI accuracy on per-monitor fractional scaling.
- Direct clipboard / portal integration.
- Native input — Wayland-only features like `RelativePointerMovement` are
  unreachable.

The `troubleshooting.md` page implicitly admits this with the
"if blank window — force XWayland" workaround.

## ── Why this matters ───────────────────────────────────────────────────────

KDE on Arch defaults to Wayland. GNOME on most modern distros defaults
to Wayland. Hyprland is Wayland-only. Anyone reporting a "wrong icon" or
"window won't focus" issue on those compositors is hitting our XToolkit
assumptions, not a real bug in the launcher.

Going Wayland-native also unlocks the usual modern-Linux niceties for
free: HDR-correct colour, fractional scaling, gestures, secure portals
for screen capture / file dialog. Compose Desktop already knows how to
draw into a Wayland surface — the gap is in JDK windowing and our
platform-specific glue.

## ── What JBR 25 actually exposes ───────────────────────────────────────────

JetBrains Runtime 25 ships **`WLToolkit`** as a peer to `XToolkit`.
Confirmed via the `jbr-api` 1.9.0 javadoc:

- `JBR.getHiDPIInfo()` — explicitly documents
  *"Supported on Linux ({@code XToolkit}, {@code WLToolkit}) only"*.
- `JBR.getRelativePointerMovement()` — documents
  *"Supported on Linux and with {@code WLToolkit} only"* (this is a
  Wayland-exclusive feature).

Selection mechanism (per JBR docs / OpenJDK upstream):

- Default selection is auto: when `XDG_SESSION_TYPE=wayland` and the JBR
  toolkit was built with Wayland support, JBR picks `WLToolkit`.
- Explicit override: `-Dawt.toolkit.name=WLToolkit` (or `XToolkit` to
  force XWayland fallback).
- The classic `awt.toolkit` system property still works for binary class
  names but is the legacy path.

## ── Where Aura currently breaks the abstraction ────────────────────────────

Inventory of X11-specific assumptions in our code:

### `client-ui/src/desktopMain/kotlin/hivens/ui/Main.kt`

| Lines | Code | Wayland behaviour |
|-------|------|-------------------|
| 121-137 | `setLinuxXToolkitAppClassName` reflects into `sun.awt.X11.XToolkit.awtAppClassName` | `Class.forName("sun.awt.X11.XToolkit")` succeeds (class is on the classpath) but on `WLToolkit` the field assignment has zero effect — `xdg-shell` `app_id` is set elsewhere. **Silent no-op**. |
| 437-446 | `isAlwaysOnTop = true → toFront → requestFocus → false` raise trick | X11-specific behaviour: under Wayland compositors raise is gated by `xdg-activation-v1` or compositor policy, often refused entirely (Hyprland by default, GNOME without focus stealing prevention). The pulse trick may visibly flicker `always-on-top` without producing a raise. |

### `client-ui/src/desktopMain/kotlin/hivens/ui/build.gradle.kts`

| Line | Flag | Wayland relevance |
|------|------|-------------------|
| 179 | `-Dawt.appClassName=AuraLauncher` | JBR-only; ignored under `WLToolkit`. JBR sets `app_id` differently for Wayland — likely needs a new mechanism. |
| 180 | `--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED` | Harmless no-op on Wayland (the package exists but our reflection target doesn't apply). |
| 184 | `-D_JAVA_AWT_WM_NONREPARENTING=1` | X11-specific Mutter workaround. Harmless under Wayland. |
| 185 | `-Drobot.need_x11=false` | Hint for `Robot` class init under headless / X11. Wayland Robot uses its own portal-based path. Harmless. |

### `client-ui/.../tray/TrayManager.kt`

`dorkbox/SystemTray` 4.4 uses `LinuxAppIndicator` over DBus by default —
the `StatusNotifierItem` protocol is display-server agnostic. **Should
survive Wayland**, but needs verification:

- KDE: native StatusNotifierWatcher. Works.
- GNOME: needs the `AppIndicator and KStatusNotifierItem Support`
  extension. Without it, no tray (already a known limitation, not
  Wayland-specific).
- Hyprland: works via waybar's SNI host.

### `Desktop.getDesktop().browse(URI)` / `.open(File)`

Used in `RightPanel.kt`, `ProfileScreen.kt`, `AboutScreen.kt`,
`SettingsScreen.kt`, `ServerSettingsScreen.kt`, `UpdateDialog.kt`,
`CrashReporter.kt`. All go through `xdg-open` on Linux, which routes
through xdg-desktop-portal on Wayland sessions — works natively. No
change needed.

### Clipboard

- `Toolkit.getDefaultToolkit().systemClipboard` in `SettingsScreen.kt`
  and `CrashReporter.kt` — under `WLToolkit` JBR routes via
  `wl_data_device_manager`. Should just work.
- `LocalClipboard.current` in `ConsoleWindow.kt` — Compose Desktop
  abstraction, delegates to JBR clipboard impl.

### `Toolkit.getDefaultToolkit().screenSize` (`AboutScreen.kt:72`)

Returns the primary screen's logical size. Under Wayland multi-monitor
with fractional scaling this can be wrong; we use it for an "About"
splash sizing decision so the impact is cosmetic, not functional.

## ── Probe results — 2026-05-12, Hyprland Wayland ───────────────────────────

`scripts/wayland-probe.sh` captured the following on the user's machine
(Arch + Hyprland Wayland session):

**System Liberica JDK 25** (`/usr/bin/java`, OpenJDK 25.0.3):

| Mode | `Toolkit.getDefaultToolkit().getClass().getName()` |
|------|---------------------------------------------------|
| auto | `sun.awt.X11.XToolkit` |
| `-Dawt.toolkit.name=XToolkit` | `sun.awt.X11.XToolkit` |
| `-Dawt.toolkit.name=WLToolkit` | `sun.awt.X11.XToolkit` (silently ignored — no class) |

Confirms upstream OpenJDK forks (Liberica, Temurin, Adoptium) **do not
ship `WLToolkit`** even on JDK 25 / Wayland session.

**Bundled JBR from the v2.2.11 AppImage** (`JBR-25.0.2+10-432.48-nomod`):

| Mode | `Toolkit.getDefaultToolkit().getClass().getName()` |
|------|---------------------------------------------------|
| auto (Wayland session) | `sun.awt.X11.XToolkit` — JBR does **not** auto-pick WLToolkit |
| `-Dawt.toolkit.name=XToolkit` | `sun.awt.X11.XToolkit` |
| `-Dawt.toolkit.name=WLToolkit` | `sun.awt.wl.WLToolkit` ← **native Wayland reachable** |

Two important corollaries:

1. **WLToolkit is opt-in, not auto.** Even on a clear Wayland session
   (`XDG_SESSION_TYPE=wayland`, `WAYLAND_DISPLAY=wayland-1`,
   `XDG_CURRENT_DESKTOP=Hyprland`) JBR defaults to XToolkit. We must
   add `-Dawt.toolkit.name=WLToolkit` to `compose.desktop.application.jvmArgs`,
   gated on Linux + Wayland session.
2. **`com.jetbrains.JBR` is missing from our jlinked runtime.** Probing
   `Class.forName("com.jetbrains.JBR")` returns ClassNotFoundException
   even on JBR. The Compose plugin's `modules(...)` list in
   `client-ui/build.gradle.kts` lines 128-140 includes only standard
   `java.*` and `jdk.crypto.ec` / `jdk.unsupported` / `jdk.zipfs`. Any
   JBR-only Wayland API (`HiDPIInfo`, `RelativePointerMovement`,
   `Screenshoter`) is unreachable in our shipped artifacts. Adding
   `jetbrains.api` (or whatever the correct module name is) to the
   modules list pulls JBR API into the jlinked runtime.

Smoke-level **window-creation** test (Swing JFrame, 2.5s pop-and-close)
on JBR / Hyprland with both toolkits:

- Default (XToolkit auto, XWayland-backed): exits 0, no warnings.
- Forced WLToolkit (`sun.awt.wl.WLToolkit`): exits 0, no warnings.

Both modes produce a visible window on screen with no stderr noise. This
is a *necessary* but not *sufficient* signal — Compose Desktop, with its
Skiko renderer, focus management, and tray integration, is the real
test. But the basic JBR Wayland toolkit path is alive on Hyprland.

## ── Proposed prove-or-deny test ────────────────────────────────────────────

Two-step verification before committing to a Wayland-native chunk:

1. **Toolkit detection**. Run a short JBR program (~20 lines) that prints
   `Toolkit.getDefaultToolkit().getClass().getName()` under both
   `XToolkit` (forced) and auto-selection on a Wayland session. Confirms
   our assumption that JBR picks `WLToolkit` automatically without
   intervention.

2. **End-to-end visual smoke**. Run the **CI-built v2.2.11 AppImage**
   (which carries jlinked JBR runtime, not the dev-machine Liberica) on
   a KDE Wayland session. Capture:

   - Window opens and renders the splash + main UI.
   - Tray icon appears (or expected GNOME-without-extension caveat).
   - `Toolkit.getDefaultToolkit().getClass().getName()` from a runtime
     log line — confirms which toolkit is active.
   - WM_CLASS / `app_id` as reported by `qdbus` / `hyprctl clients` —
     confirms whether the `.desktop` association works.
   - Focus / raise behaviour when triggering the second-instance pulse.

3. **Failure-mode log capture**. Even if it works, the
   `setLinuxXToolkitAppClassName` reflection will hit the `onFailure`
   branch and produce a `WARN` line. Confirm that warning is the only
   visible error — anything else is unexpected and goes on the
   investigation worksheet.

A test script for step 1+3 lives at `scripts/wayland-probe.sh` (to be
added alongside this doc).

## ── Trial result — 2026-05-13 (BLOCKED on upstream Skiko) ─────────────────

The Trial AppImage (commit `c70e1d6`, `AURA_WAYLAND_TRIAL=1` baked into
AppRun) was run on the user's Hyprland Wayland session. Verdict:
**Wayland-native is currently blocked by Skiko, not by our code.**

### What worked

- `AURA_WAYLAND_TRIAL=1 → -Dawt.toolkit.name=WLToolkit` propagation
  through `scripts/build-appimage.sh` to AppRun → JBR runtime
  (fix in PR #135 `efa0d5b`).
- `logToolkitAndSession()` captured the active toolkit. Confirmed line
  in `launcher.log`:
  ```
  Display: toolkit=sun.awt.wl.WLToolkit XDG_SESSION_TYPE=wayland
  XDG_CURRENT_DESKTOP=Hyprland WAYLAND_DISPLAY=wayland-1 DISPLAY=:1
  ```
- `setLinuxXToolkitAppClassName` failed with the expected
  `ExceptionInInitializerError` (X11 classes don't initialise on
  WLToolkit). Logged as WARN, non-fatal — exactly as designed.

### What broke

Crash within ~22s of startup, before the main window paints:

```
java.lang.IllegalStateException: Can't lock DrawingSurface
    at org.jetbrains.skiko.DrawingSurface.lock(AWT.kt:35)
    at org.jetbrains.skiko.AWTKt.useDrawingSurfacePlatformInfo(AWT.kt:12)
    at org.jetbrains.skiko.HardwareLayer.init(HardwareLayer.kt:24)
    at org.jetbrains.skiko.SkiaLayer.init(SkiaLayer.awt.kt:372)
    at org.jetbrains.skiko.SkiaLayer.addNotify(SkiaLayer.awt.kt:257)
    ...
    at androidx.compose.ui.window.Application_desktopKt$awaitApplication
```

Skiko's AWT bridge for surface acquisition is X11-specific. On
WLToolkit it can't obtain a `DrawingSurface` because the JDK doesn't
expose a `sun.awt.wl.*` analogue of the X11 GLX surface that
`useDrawingSurfacePlatformInfo` expects.

### Skiko 0.144.6 inspection (the version pulled by `compose 1.11.0-alpha04`)

`skiko-awt-0.144.6.jar` ships these renderer backends:

| Class | API | Platform target |
|-------|-----|-----------------|
| `LinuxOpenGLRedrawer` | OpenGL via GLX | X11 only |
| `LinuxSoftwareRedrawer` | CPU pixmap | Linux generic |
| `WindowsOpenGLRedrawer` / `Direct3DRedrawer` / `AngleRedrawer` | GL / D3D11 / ANGLE | Windows |
| `MetalRedrawer` | Metal | macOS |
| `SoftwareRedrawer` | CPU | any |

Notably absent: `LinuxVulkanRedrawer`, `LinuxWaylandRedrawer`,
`WaylandRedrawer`, any `Vulkan*Redrawer`.

`libskiko-linux-x64.so` (the native part) carries `XOpenDisplay`,
`glXCreateContext`, `glXCreatePbuffer` symbols — and **none** of
`wl_display`, `wl_compositor`, `xdg_*`, `vkInstance`. There's no
Wayland surface code path in the native lib at all.

### What this means for Vulkan

User asked whether we could switch the renderer to Vulkan as a
workaround. Answer: not in this version. Skiko 0.144.6 has no Vulkan
backend on any platform — `SkikoRenderApi.VULKAN` constant exists in
the enum but the corresponding redrawer class is not shipped.
`-Dskiko.renderApi=VULKAN` is forward-looking config that future
Skiko versions will honour.

### Verdict

The trial demonstrates the path is **architecturally correct** (env
propagation works, toolkit selection works, our diagnostic infra
works) but **upstream isn't ready**. Compose Desktop on WLToolkit
requires Skiko to ship a Wayland surface acquisition path, which
0.144.x does not.

**Adoption chunk: FROZEN until upstream ships.** All the trial-side
infrastructure (`AURA_WAYLAND_TRIAL` env, AppRun propagation, startup
log line, trial CI workflow, investigation doc) stays in place — when
a future Skiko/Compose bump adds Wayland support, re-firing the trial
workflow is one click. JetBrains is actively building Compose for
Linux native, so the wait is more likely measured in Compose minor
versions than in years.

**Re-trigger criteria:**
- Skiko 0.145+ ships any class matching `*Wayland*Redrawer` or
  `LinuxVulkanRedrawer`, OR
- Compose Desktop changelog mentions Wayland surface support, OR
- A non-Aura Compose project demonstrably runs on WLToolkit on a
  pure Wayland session.

When any of these land, re-fire `trial-appimage.yml`, re-test on
Hyprland, document the new outcome in this section.

## ── Decision — 2026-05-12 ──────────────────────────────────────────────────

**GO**, with conditions. Probe data above shows the path exists
and is alive at the JBR layer on the user's Hyprland session. Promote
**Wayland-native** to a real chunk in Project Void as a Bridge-pillar
continuation.

The chunk splits into a **trial** (small, low-risk, reversible) and an
**adoption** (only after the trial demonstrates Compose-level success):

### Trial chunk — toolkit opt-in + measure

Smallest reversible change that exposes the truth at Compose level:

1. Add `-Dawt.toolkit.name=WLToolkit` to
   `compose.desktop.application.jvmArgs` in `client-ui/build.gradle.kts`,
   gated by an env var `AURA_WAYLAND_TRIAL=1` so default builds and
   regular users are unaffected. (Use `providers.environmentVariable` at
   configuration time to keep the configuration cache happy.)
2. Add a startup log line in `Main.kt` capturing
   `Toolkit.getDefaultToolkit().getClass().getName()`,
   `XDG_SESSION_TYPE`, `WAYLAND_DISPLAY` so any field tester's
   `launcher.log` immediately reports which toolkit they got.
3. Build the trial AppImage in CI behind `workflow_dispatch` (not on
   release tags) and have the user run it on Hyprland + KDE Plasma 6
   Wayland sessions.
4. Capture failure modes per [[Where Aura currently breaks]] inventory
   above: WM_CLASS reflection (expected silent fail), raise pulse
   (expected to refuse), tray (expected to survive via DBus), clipboard,
   Desktop.browse, screen size.

Trial is **S-sized**: ~30 LOC + a workflow override. Output is a
follow-up writeup in this same doc with concrete pass/fail per surface.

### Adoption chunk — Linux-Wayland-by-default

Only after trial demonstrates Compose actually starts and renders:

- **S** — add the `jetbrains.api` (or correct name; verify by inspecting
  the JBR runtime) module to the `modules(...)` list so
  `com.jetbrains.JBR` becomes reachable in jlinked artifacts. Without
  this, Wayland-only JBR services stay locked behind `ClassNotFoundException`.
- **S** — wrap `setLinuxXToolkitAppClassName` in a toolkit check so it
  only runs under `XToolkit`; on `WLToolkit` route to a placeholder
  `setLinuxWLAppId(name)` (initially a TODO log line, fixed when JBR
  exposes the right setter — needs separate research).
- **M** — replace the X11-specific `isAlwaysOnTop` raise pulse with
  either `xdg-activation-v1` activation tokens (if JBR exposes them
  via API) or a documented "Wayland users: use the tray icon to
  surface the window" caveat. Flickering `always-on-top` under
  Hyprland is worse than no raise at all.
- **S** — flip the env-var gate off, default to WLToolkit on Linux
  Wayland sessions, keep XToolkit fallback on `XDG_SESSION_TYPE=x11`
  or when `WAYLAND_DISPLAY` is unset. The toolkit choice is per-launch,
  not a user-toggle in Settings (no good reason to ship both code
  paths at runtime).
- **S** — update `troubleshooting.md` (en + ru) to remove the
  "force XWayland" workaround and replace with "if Wayland renders
  wrong, set `AURA_TOOLKIT=X11` env to fall back".

### Open research items (don't block trial)

- **JBR `app_id` setter mechanism** — JBR-API has `WindowDecorations`
  and `WindowMove` exposed; an `app_id` setter may live there or be
  set via the platform window peer. Needs source-level look at JBR
  upstream (separate task; the trial doesn't need it because the
  AppImage's `.desktop` entry's `StartupWMClass` covers the splash
  case adequately on most compositors).
- **Skiko + Vulkan on Wayland** — Compose Desktop renderer. Skiko is
  GPU-agnostic; Vulkan path may already work natively. Worth measuring
  fps/jitter against XWayland baseline as a side-benefit number for
  the chunk's commit message.
- **Tray on GNOME Wayland without extension** — known caveat,
  pre-existing, not Wayland-native-induced. Not in scope for this
  chunk; handled by [[dorkbox-systemtray-replace]] when that lands.

### Sequencing

Independent of Echo / smoke / contract tests. Slots in cleanly anywhere
after smoke is merged to stable (so we have a release-gate when the
trial AppImage breaks something subtle). Plausibly 2.2.13 (trial) +
2.2.14 (adoption) given the size split, or one combined release if the
trial reports clean.

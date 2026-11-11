---
title: Architecture
description: Modules, boot pipeline, dependency graph and the seams Nexira is built on.
---

## Module map

Twenty four Gradle modules make up the launcher. The dependency direction is one-way and load-bearing: the engine never sees the UI, and the design system never sees the domain.

| Module                    | Role                                                                                                                                                                                                                           |
|---------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `client-config`           | Branding, storage filenames, protocol constants and their runtime overrides.                                                                                                                                                   |
| `client-core`             | Domain models, wire DTOs, the `I*` service interfaces, the cache engine, launch state types.                                                                                                                                   |
| `client-auth`             | Auth SPI: `AuthProvider`, `AuthCapabilities`, the account store, the credential manager.                                                                                                                                       |
| `client-auth-smartycraft` | SmartyCraft provider.                                                                                                                                                                                                          |
| `client-auth-microsoft`   | Microsoft (MSA) provider.                                                                                                                                                                                                      |
| `client-launcher`         | The engine: DI wiring, mirror client and sync, runtime provisioning, loader resolution, the launch flow, pack update apply and rollback.                                                                                       |
| `client-update`           | The launcher updating itself: release check, delta bundle, binary patch, and the per-platform applicators.                                                                                                                     |
| `client-cli`              | Headless entry point over the same engine.                                                                                                                                                                                     |
| `client-media`            | Video cache and yt-dlp resolution for the media surfaces.                                                                                                                                                                      |
| `client-playback`         | How sound plays: the queue, the repeat modes and the state machine over an engine. Shared by the desktop and Android builds, the engines are handed in.                                                                        |
| `client-tray`             | System tray behind one interface, backed by libtray (Panama bindings).                                                                                                                                                         |
| `client-window`           | The launcher window on each desktop system: the one owner of its maximised and minimised state, the system's own frame on Windows, the window manager's word on X11, and the record of how the window was left. No Compose.    |
| `client-ui`               | The Compose Desktop shell: screens, widgets, editor, console.                                                                                                                                                                  |
| `client-i18n`             | The `AppStrings` interface, the English, Russian, German and Japanese implementations, and the `LocalStrings` composition local.                                                                                               |
| `client-render3d`         | Software 3D: the rasteriser and scene graph, and the Minecraft skin rig built on them.                                                                                                                                         |
| `client-easter`           | The April Fools engine, behind an SPI that resolves to a no-op unless a provider is registered.                                                                                                                                |
| `nx-ui`                   | The design system: theme, tokens, surfaces, primitives.                                                                                                                                                                        |
| `widget-model`            | Layout graph, slot and widget identity, service and command keys. Compose-free.                                                                                                                                                |
| `widget-api`              | Widget runtime: the slot renderer, the registries, the composition locals.                                                                                                                                                     |
| `widget-processor`        | KSP processor that generates the widget registry from `@Widget`.                                                                                                                                                               |
| `widget-loader`           | Finds widget modules in the widgets folder at startup, checks the interface version they declare and hands back their registries. Separate from `widget-api`, so a module author builds against the kernel without the loader. |
| `authlib-agent`           | Java agent that redirects authlib endpoints for a SmartyCraft-bound join.                                                                                                                                                      |
| `profiler-agent`          | Java agent that observes heap and GC inside the game JVM.                                                                                                                                                                      |
| `android-app`             | The Android application: hosts the shared modules on a phone. Left out of a build on a machine without an Android SDK.                                                                                                         |

Besides these, `client-core-testing` holds test doubles shared by the modules' tests, `examples/` holds three widget modules built the way an outside author would build one, and `experimental/client-boot` is parked groundwork that is kept compiling.

Two boundaries are worth stating outright, because nothing enforces them mechanically:

- `client-launcher` and everything below it carry no `java.awt`, `javax.swing` or `javax.imageio` import. That is what keeps AWT out of the CLI's reachable graph and out of a native image. The only AWT-looking strings in the engine are JVM flags handed to the game process.
- `nx-ui` depends on Compose, coroutines, serialization and a colour library, and on no module of this project. A component belongs there only if it names no domain type and resolves no string of its own. That is why a source badge maps `PackOrigin` to a colour in `client-ui` and then calls a colour-taking primitive in `nx-ui`.

## Boot

The pipeline is split so a window is on screen before the slow work runs.

1. `LauncherBootstrap.preWindow` -- milliseconds only. Resolve the logs directory before the first logger call, stamp a session id into a system property so every log line traces back to one process, take the single-instance lock. A duplicate launch exits here without flashing a window.
2. The window opens and renders the boot threshold.
3. `LauncherBootstrap.completeCore` -- on a background thread. Apply a pending data-directory move, re-resolve paths after it, restore persisted SSL bypasses before any request can be made, construct the crash reporter.
4. `LauncherBootstrap.finishBoot` -- detect a pending migration, then start Koin.

Progress is reported as four phases (`Data`, `Network`, `Migration`, `Modules`) that drive the threshold's bar.

The toolkit-touching edge lives in `hivens.ui.bootstrap.GuiBootstrap`: the X11 window-class override, the Skiko vsync property, a one-line display diagnostic, and a crash handler that surfaces a Swing dialog. The CLI composes the same core through `preBootHeadless` with a log-and-persist handler and no single-instance lock.

## Shell recovery

The Compose entry point runs inside a restart loop. `application` is invoked with `exitProcessOnExit = false`, so a composition crash unwinds and returns instead of killing the process; a crash on the render thread is caught by a window exception handler and routed to the same place. The loop escalates: retry with a fresh composition, latch safe mode on a crash loop, fall back to a terminal Swing dialog if safe mode itself fails.

Koin and the data directories are created outside the loop, so a restart keeps data, session and audio playback. Only composition state is lost.

A separate recovery entry (environment variable, command-line flag, or a one-shot marker file) renders a recovery surface before Koin exists at all, so a broken module cannot take the recovery path down with it.

## Dependency graph

Koin is the real map of this project, and static analysis cannot see it: a class asks the container for an interface and never names the implementation.

The engine registers seven modules shared with Android, listed by `launcherModules()` -- `networkModule`, `authModule`, `cacheModule`, `runtimeModule`, `mirrorModule`, `launchPipelineModule`, `appModule` -- and three that only the desktop has, listed by `desktopModules()` -- `desktopHostModule`, `updateModule`, `desktopImportModule`. Membership is grouping only; all start together.

The UI contributes one more, `uiModule` in `hivens.ui.Main`, handed to `GuiBootstrap.completeBoot` as an extra module. That is the direction guard: the engine imports no UI type, and the UI still lands in the same container.

Six definitions are eager: platform paths, the data directory, the settings restore hook, the shared process-lifetime coroutine scope, the shutdown hook that cancels it, and a pass that puts back any `fml.toml` a session left changed because the launcher closed before its game did. Everything else resolves on demand.

Some bindings alias one instance rather than construct a second. The credential manager is bound as both the read-only credential store and the account store; the pack update service is bound as the updater contract, and the background auto-updater as the status hub the UI reads.

## Network channels

Outbound traffic is split in two, and every binding picks one explicitly.

- The SmartyCraft channel carries everything on the upstream host. It connects directly, and it is the only channel that honours an SSL bypass: while the user holds a live bypass for that host, its requests go through a trust-all client. There is no in-launcher proxy, so a user who cannot reach the host needs a VPN or a system proxy.
- The direct channel has strict TLS and no bypass of any kind, and serves every third-party CDN: Mojang, BellSoft, Maven Central, Modrinth, the Hivens mirror, GitHub releases.

The update path is pinned to the direct channel on purpose: the auto-updater has to keep working while the upstream host is unreachable, or it cannot deliver the fix that restores connectivity. Microsoft sign-in is pinned there for a second reason -- its tokens must never travel over a client the user has told to trust any certificate.

`HttpClientProvider` is a provider rather than an injected client, so the per-request decision (bypass or strict) is re-read on every call and a granted bypass takes effect without rebuilding the container.

## Data directory

Resolved in order: the `NEXIRA_DATA_DIR` environment variable, a `data-dir` key in a bootstrap config that deliberately lives outside the data directory, then the per-OS default.

| OS      | Path                                                      |
|---------|-----------------------------------------------------------|
| Windows | `%LOCALAPPDATA%\Nexira`                                   |
| macOS   | `~/Library/Application Support/Nexira`                    |
| Linux   | `$XDG_DATA_HOME/nexira` (default `~/.local/share/nexira`) |

Layout inside it:

```
instances/      pack instances -- the unit of installation
clients/        legacy SmartyCraft per-server layout
libraries/      shared Maven-layout libraries, deduped across packs
assets/         shared vanilla assets (indexes plus content-addressed objects)
db/             Xodus: the pack registry and the content-scan cache
cache/          TTL cache namespaces for pack and Modrinth metadata
loader-cache/   headless loader-installer output
snapshots/      pre-apply snapshots for update rollback
presets/        layout presets
logs/  crash-reports/  skin-cache/  video-cache/  tools/
```

`libraries/` and `assets/` sit outside any instance on purpose: two packs on the same Minecraft version share one copy instead of each downloading its own. Historical data directories from earlier releases are walked once by the migration screen and copied, never deleted.

## Runtime provisioning

A pack declares a Minecraft version and a loader; the provisioner turns that into a launchable classpath.

The vanilla base comes from Mojang's own CDN into the shared roots. A loader contributes an overlay: extra libraries plus launch metadata (main class, JVM and game argument additions). The two are merged with the loader winning on a collision, keyed on `group:artifact:classifier` -- the classifier matters, because a modern version json lists a library's base jar and its natives jar under the same coordinate.

Loaders differ in kind, and the profile says which:

- Additive loaders (Forge, NeoForge, Fabric, Quilt) merge onto the vanilla set.
- A loader that swaps LWJGL names the vanilla group to drop and supplies its own natives, so LWJGL3 replaces LWJGL2 while unrelated vanilla natives survive.
- A self-contained loader replaces the vanilla library set wholesale, because cross-coordinate twins (two spellings of the same library) cannot be deduplicated by a group-and-artifact merge.
- Modern installers emit files that must exist on disk but stay off the classpath, because the loader's own locator finds them by path.

The Java major is resolved by precedence: a loader override wins over Mojang's declaration, which wins over the launcher's heuristic. The same Minecraft version on a different loader can need a different JDK.

With every artifact already present, a relaunch needs no network at all: the version json and the asset index are reused from disk.

## Update apply

An update is a transaction. Before the first byte is written, the pack-managed files are snapshotted (hardlinked, so it is cheap) and a journal entry records the in-flight apply. The plan is then applied, the new baseline committed, and the journal closed. A failure restores the snapshot; a hard crash is rolled back from the journal on the next start.

Every file lands atomically: written to a temporary sibling, then moved with an atomic move, with documented fallbacks for filesystems that refuse it.

Structural mutations of an instance serialise on a per-directory lock, so a sync, a content relabel and an update apply can never interleave.

## Widget kernel

The shell is itself a widget surface. `AppLayout` ends in a single slot render of `appshell.root`; the left rail, the centre and the right panel are widgets in the layout graph. Navigation is not a surface yet, so the screen router is passed down through the shell context.

Four modules, four roles:

- `widget-model` holds the graph (`LayoutGraph`, `SurfaceLayout`, `SlotContent`, `WidgetInstance`, recursive through `children`) and the identity types. It carries no Compose dependency, so a headless consumer can read a layout.
- `widget-api` holds the runtime: the slot renderer, the widget registry, and the service, data and command registries.
- `widget-processor` generates the registry from `@Widget` annotations.
- `widget-loader` finds widget modules in the widgets folder at startup and hands their registries to the runtime.

Extension happens through composition locals with identity defaults. The decorator that wraps every widget, the empty-slot placeholder, the unknown-widget placeholder, the slot chrome modifier and the per-instance backing renderer all default to doing nothing; the editor swaps in real ones. Production pays nothing for an editor it does not mount.

A slot owns its own arrangement: column, row, grid, free canvas, or an addressed cube grid. A widget's content is wrapped in per-instance movable content, so toggling edit mode moves the subtree rather than disposing it, and the widget keeps its loaded state.

A widget whose kind is absent from the registry keeps its stored props and children; it renders as nothing in production and as a visible placeholder in the editor. Orphans are only reaped after a schema bump.

## Themes

A theme says what the interface may be drawn with, and nothing about where. It is a ladder of steps (the page first, then the planes outward from it), the inks text is set in (a main one and a quiet one, each readable on every step), and the theme's colours in order of emphasis. Which step a card sits on and which colour a status takes is decided in `nx-ui`, not in the theme, so a theme cannot paint one place without the rest.

A theme has a dark scheme, and a light one when its author drew one. A light scheme is never derived: a theme without one stays in its own mode, and the day and night switch says so. Nine ship, `Celestia` with both modes and eight dark ones. The wallpaper can make one more from its colours, widget modules can bring their own, and the user's own are stored beside the selection. A theme a module brought is never saved, so it leaves with the module.

The form is one. Radii, borders and motion are not a choice, and there is no second style to switch to. Beside the theme sit a few switches of the user's: whether surfaces blur, whether motion is reduced, and how the navigation rail marks its selection.

## Testing and what runs

Roughly 3800 test methods. The engine and the widget model are covered densely. The UI is covered thinly, and its visual output has only a few assertions: most render tests assert that a non-empty image was produced, not what is in it.

Continuous integration on a pull request runs every module that has tests -- twenty seven suites, and the build logic in `buildSrc` as a build of its own -- across Linux, macOS and Windows. It used to run eight, and each module left out of that list was one whose regressions reached a release: the widget loader is the runtime half of the validator the KSP processor enforces at build time, and the worked example is the only thing in the tree compiled against the widget ABI from outside, so it is what notices an ABI change the launcher's own modules cannot see.

Four custom scanners run strictly on every pull request: one fails on a user-facing string hardcoded outside the localisation layer, one on process metadata in comments, one on an animation that bypasses the user's motion setting, and one on a test suite missing from the matrix. None of them keeps a module list, because a list is how `nx-ui` went unscanned when it was split out: each takes every top-level directory carrying sources, and the string scanner also asks each build file whether Compose is applied. A new module is covered the day it lands.

## Packaging

Distribution is assembled by custom Gradle plugins in `buildSrc` rather than by the Compose plugin's defaults: a jlink runtime trimmed to a measured module set, a jpackage image, a macOS disk image, and an AppImage profile assembled by script with desktop-entry and AppStream metadata injected.

A verification task fails the build if the trimmed module set omits a module the runtime actually reads, since the failure it prevents is silent rather than loud.

jlink takes the runtime's native files from the JDK's jmods, where they are unsigned, while the JDK vendor signs only the finished JDK. On Windows each of the runtime's binaries is therefore replaced by the vendor's signed copy from the same JDK, after checking that the two are the same image apart from the signature. Windows' Smart App Control blocks an unsigned binary it has no reputation for, whoever loads it.

## Protocol constants

`hivens.config.Protocol` holds the values the SmartyCraft wire format requires: the mimicked launcher version, the default launcher hash, and the signature scheme's inputs. They were derived from the upstream launcher (see [`Kitty-Hivens/smrt-deco`](https://github.com/Kitty-Hivens/smrt-deco)). They are interop constants, not secrets, and are documented as such -- hiding them would only make the protocol harder to reason about.

Both the mimicked version and the base URL have runtime overrides behind explicit opt-ins, so an upstream version pin can be answered faster than a release cycle.

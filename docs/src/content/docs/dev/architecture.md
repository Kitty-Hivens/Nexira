---
title: Architecture
description: Modules, boot pipeline, dependency graph and the seams Nexira is built on.
---

## Module map

Twenty Gradle modules. The dependency direction is one-way and load-bearing: the engine never sees the UI, and the design system never sees the domain.

| Module | Role |
|---|---|
| `client-config` | Branding, storage filenames, protocol constants and their runtime overrides. |
| `client-core` | Domain models, wire DTOs, the `I*` service interfaces, the cache engine, launch state types. |
| `client-auth` | Auth SPI: `AuthProvider`, `AuthCapabilities`, the account store, the credential manager. |
| `client-auth-smartycraft` | SmartyCraft provider. |
| `client-auth-microsoft` | Microsoft (MSA) provider. |
| `client-launcher` | The engine: DI wiring, mirror client and sync, runtime provisioning, loader resolution, the launch flow, pack update apply and rollback. |
| `client-update` | The launcher updating itself: release check, delta bundle, binary patch, and the per-platform applicators. |
| `client-cli` | Headless entry point over the same engine. |
| `client-media` | Video cache and yt-dlp resolution for the media surfaces. |
| `client-tray` | System tray behind one interface, backed by libtray (Panama bindings). |
| `client-ui` | The Compose Desktop shell: screens, widgets, editor, console. |
| `client-i18n` | The `AppStrings` interface, the English, Russian and German implementations, and the `LocalStrings` composition local. |
| `client-render3d` | Software 3D: the rasteriser and scene graph, and the Minecraft skin rig built on them. |
| `client-easter` | The April Fools engine, behind an SPI that resolves to a no-op unless a provider is registered. |
| `nx-ui` | The design system: theme, tokens, surfaces, primitives. |
| `widget-model` | Layout graph, slot and widget identity, service and command keys. Compose-free. |
| `widget-api` | Widget runtime: the slot renderer, the registries, the composition locals. |
| `widget-processor` | KSP processor that generates the widget registry from `@Widget`. |
| `authlib-agent` | Java agent that redirects authlib endpoints for a SmartyCraft-bound join. |
| `profiler-agent` | Java agent that observes heap and GC inside the game JVM. |

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

The engine registers eight modules in `client-launcher/di/Modules.kt` -- `networkModule`, `authModule`, `cacheModule`, `runtimeModule`, `mirrorModule`, `launchPipelineModule`, `updateModule`, `appModule`. Membership is grouping only; all start together.

The UI contributes a ninth, `uiModule` in `hivens.ui.Main`, handed to `GuiBootstrap.completeBoot` as an extra module. That is the direction guard: the engine imports no UI type, and the UI still lands in the same container.

Five definitions are eager: platform paths, the data directory, the settings restore hook, the shared process-lifetime coroutine scope, and the shutdown hook that cancels it. Everything else resolves on demand.

Some bindings alias one instance rather than construct a second. The credential manager is bound as both the read-only credential store and the account store; the pack update service is bound as the updater contract, and the background auto-updater as the status hub the UI reads.

## Network channels

Outbound traffic is split in two, and every binding picks one explicitly.

- The SmartyCraft channel is SOCKS-proxied and required for everything on the upstream host. A direct attempt runs first and falls back to the proxy; a user on a censored network can force the proxy from Settings.
- The direct channel has no proxy and strict TLS, and serves every third-party CDN: Mojang, BellSoft, Maven Central, Modrinth, the Hivens mirror, GitHub releases.

The update path is pinned to the direct channel on purpose: the auto-updater has to keep working while the upstream proxy is down, or it cannot deliver the fix that restores connectivity.

`HttpClientProvider` is a provider rather than an injected client, so the per-request decision (bypass, forced proxy, direct) is re-read on every call and a Settings change takes effect without rebuilding the container.

## Data directory

Resolved in order: the `NEXIRA_DATA_DIR` environment variable, a `data-dir` key in a bootstrap config that deliberately lives outside the data directory, then the per-OS default.

| OS | Path |
|---|---|
| Windows | `%LOCALAPPDATA%\Nexira` |
| macOS | `~/Library/Application Support/Nexira` |
| Linux | `$XDG_DATA_HOME/nexira` (default `~/.local/share/nexira`) |

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

Three modules, three roles:

- `widget-model` holds the graph (`LayoutGraph`, `SurfaceLayout`, `SlotContent`, `WidgetInstance`, recursive through `children`) and the identity types. It carries no Compose dependency, so a headless consumer can read a layout.
- `widget-api` holds the runtime: the slot renderer, the widget registry, and the service, data and command registries.
- `widget-processor` generates the registry from `@Widget` annotations.

Extension happens through composition locals with identity defaults. The decorator that wraps every widget, the empty-slot placeholder, the unknown-widget placeholder, the slot chrome modifier and the per-instance backing renderer all default to doing nothing; the editor swaps in real ones. Production pays nothing for an editor it does not mount.

A slot owns its own arrangement: column, row, grid, free canvas, or an addressed cube grid. A widget's content is wrapped in per-instance movable content, so toggling edit mode moves the subtree rather than disposing it, and the widget keeps its loaded state.

A widget whose kind is absent from the registry keeps its stored props and children; it renders as nothing in production and as a visible placeholder in the editor. Orphans are only reaped after a schema bump.

## Style and palette

Two independent axes, deliberately separate so a palette and a form can be chosen without a combined preset.

Palette is colour: a fixed dark or light base, optionally reseeded from the wallpaper through Material colour science, then a preset, then an accent override. Brand and semantic tokens -- source colours, severity accents, the decorative ramp -- are deliberately not derived, because a badge whose colour follows the wallpaper stops identifying its source.

Style is form: corner radii, border weight, surface treatment, a motion multiplier, glow, panel elevation, and the switch and badge shells. Two ship today, `Celestia` (rounded, glass, glow, animated) and `Brut` (square, flat, still).

Adoption of the style tokens is uneven. Corner radius is read widely; motion and glow by a handful of call sites; the surface-treatment token by almost nothing. Rendering the same screen under both styles currently differs by well under a tenth of a percent of pixels, and the difference is concentrated in corners.

## Testing and what runs

Roughly 1780 test methods. The engine and the widget model are covered densely. The UI is covered thinly, and its visual output has only a few assertions: most render tests assert that a non-empty image was produced, not what is in it.

Continuous integration on a pull request runs eight suites -- `client-config`, `client-core`, `client-launcher`, `client-update`, `client-ui`, `client-i18n`, `client-render3d` and `widget-processor` -- across Linux, macOS and Windows. The design system, the widget model and runtime, the auth modules, the CLI, media, tray and the two agents are not run there.

Two custom scanners do run strictly on every pull request: one fails on a user-facing string hardcoded outside the localisation layer, the other on process metadata in comments. Neither keeps a module list, because a list is how `nx-ui` went unscanned when it was split out: the comment scanner takes every top-level directory carrying sources, and the string scanner asks each build file whether Compose is applied. A new module is covered the day it lands.

## Packaging

Distribution is assembled by custom Gradle plugins in `buildSrc` rather than by the Compose plugin's defaults: a jlink runtime trimmed to a measured module set, a jpackage image, a macOS disk image, and an AppImage profile assembled by script with desktop-entry and AppStream metadata injected.

A verification task fails the build if the trimmed module set omits a module the runtime actually reads, since the failure it prevents is silent rather than loud.

## Protocol constants

`hivens.config.Protocol` holds the values the SmartyCraft wire format requires: the mimicked launcher version, the default launcher hash, and the signature scheme's inputs. They were derived from the upstream launcher (see [`Kitty-Hivens/smrt-deco`](https://github.com/Kitty-Hivens/smrt-deco)). They are interop constants, not secrets, and are documented as such -- hiding them would only make the protocol harder to reason about.

Both the mimicked version and the base URL have runtime overrides behind explicit opt-ins, so an upstream version pin can be answered faster than a release cycle.

---
title: Architecture
description: Modules, boot pipeline, dependency graph and the seams Nexira is built on.
---

## Module map

The launcher is made of Gradle modules. The dependency direction is one-way and load-bearing: the engine never sees the UI, and the design system never sees the domain.

| Module                     | Role                                                                                                                                                                                                                           |
|----------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `client-config`            | Branding, storage filenames, protocol constants and their runtime overrides.                                                                                                                                                   |
| `client-core`              | Domain models, wire DTOs, the `I*` service interfaces, the cache engine, launch state types.                                                                                                                                   |
| `client-auth`              | Auth SPI: `AuthProvider`, `AuthCapabilities`, the account store, the credential manager.                                                                                                                                       |
| `client-auth-smartycraft`  | SmartyCraft provider.                                                                                                                                                                                                          |
| `client-auth-microsoft`    | Microsoft (MSA) provider.                                                                                                                                                                                                      |
| `client-launcher`          | The engine: DI wiring, mirror client and sync, runtime provisioning, loader resolution, the launch flow, pack update apply and rollback.                                                                                       |
| `client-update`            | The launcher updating itself: release check, delta bundle, binary patch, and the per-platform applicators.                                                                                                                     |
| `client-media`             | Video cache and yt-dlp resolution for the media surfaces.                                                                                                                                                                      |
| `client-playback`          | How sound plays: the queue, the repeat modes and the state machine over an engine. Shared by the desktop and Android builds, the engines are handed in.                                                                        |
| `client-tray`              | System tray behind one interface, backed by libtray (Panama bindings).                                                                                                                                                         |
| `client-window`            | The launcher window on each desktop system: the one owner of its maximised and minimised state, the system's own frame on Windows, the window manager's word on X11, and the record of how the window was left. No Compose.    |
| `client-ui`                | The Compose Desktop shell: screens, widgets, editor, console.                                                                                                                                                                  |
| `client-i18n`              | The launcher's own words: the `nexira` tables for every shipped language, the composable that reads them, and the build check that warns about missing translations.                                                           |
| `i18n-api`                 | The language module's API: looking words up by namespace and key, the composition local the host provides them through, and the language keys and settings.                                                                    |
| `i18n`                     | The language module: the engine that answers every namespace in every language, a person's own translation files among them, with the message syntax and the plural rules.                                                     |
| `look/manager-api`         | The look manager's API: an axis of the look, a provider answering one, a pack naming a provider per axis, the choice, and the one key the host reads the look through.                                                         |
| `look/manager`             | The look manager: picks one provider per axis from the choice, opens only those, answers the look before the boot and after, and knows no axis.                                                                                |
| `look/colour-api`          | The colour axis: what a widget asks of colour by role, the answers a provider fills in, and the colourless defaults used when no provider is chosen for the axis.                                                              |
| `look/motion-api`          | The motion axis: what a widget asks of movement by role, the answers a provider fills in, and the plain defaults used when no provider is chosen for the axis.                                                                 |
| `look/shape-api`           | The shape axis: the corners and outlines a widget asks by role, the answers a provider fills in, and the square defaults used when no provider is chosen for the axis.                                                         |
| `look/stroke-api`          | The stroke axis: the edges, marks, dividers and placeholder outlines a widget asks by role, answers that draw themselves, and the hairline defaults used when no provider is chosen for the axis.                              |
| `look/surface-api`         | The surface axis: the planes a widget asks by kind, what each is made of, the plate a widget's own plane takes, and the flat defaults used when no provider is chosen for the axis.                                            |
| `look/timing-api`          | The timing axis: the delay before a tooltip appears, by what the tooltip is for, and Compose's default delay when no provider is chosen for the axis.                                                                          |
| `look/feedback-api`        | The feedback axis: how a widget answers hover, focus, press and drag, as a Compose indication by what the widget is, and Compose's default indication when no provider is chosen for the axis.                                 |
| `look/type-api`            | The type axis: the face text is drawn in by what the text is, the code face and the face for a script the others lack, never a size, and Compose's own families when no provider is chosen for the axis.                       |
| `look/icon-api`            | The icon axis: an icon by its Material Symbols name or carried by the widget itself, drawn by a library in the widget's own size and colour, and a few plain icons when no provider is chosen for the axis.                    |
| `look/packs`               | The packs that ship: the default pack, `nexira`, naming a provider family per axis.                                                                                                                                            |
| `look/colour-nx-api`       | The themes module's API: the keys for the theme in use, the themes on offer, the choice and what decides dark and light, and the theme a module brings.                                                                        |
| `look/colour-nx`           | The themes module, the nx provider of colour: the themes that ship, the one made from the wallpaper, the user's own, which is in use and where that choice is kept.                                                            |
| `look/motion-nx`           | The nx provider of motion: nx's durations and curves, and the person's reduce motion, kept in its own settings.                                                                                                                |
| `look/motion-nx-controls`  | The controls of the nx motion provider: the reduce motion switch, shown while nx draws the motion.                                                                                                                             |
| `look/shape-nx`            | The nx provider of shape: nx's corners, and the outline a widget's own plate takes where its node leaves it unsaid.                                                                                                            |
| `look/stroke-nx`           | The nx provider of stroke: nx's edges, marks, dividers and placeholder outlines.                                                                                                                                               |
| `look/surface-nx`          | The nx provider of surface: nx's planes on its depth ladder.                                                                                                                                                                   |
| `look/timing-nx`           | The nx provider of timing: the delays before nx's tooltips appear.                                                                                                                                                             |
| `look/feedback-nx`         | The nx provider of feedback: nx's state layers, and the opacity of disabled content.                                                                                                                                           |
| `look/type-nx`             | The nx provider of type: the bundled Roboto Flex, JetBrains Mono and Noto Sans CJK.                                                                                                                                            |
| `look/icon-google-rounded` | The Google provider of icons: Material Symbols Rounded, every icon of it. Compiles against the icon axis and no design system.                                                                                                 |
| `look/preview`             | The icon library for previews and tests, which `nx-ui` does not know. On their class path only and in no application.                                                                                                          |
| `catalogue-api`            | The catalogue module's API: the keys Browse and the project and pack pages read, and the contract a source implements.                                                                                                         |
| `catalogue`                | The catalogue module over the sources the modules bring. Browse and pack pages name no source, project pages and installs still read Modrinth.                                                                                 |
| `source-mirror`            | The Hivens mirror as a catalogue source: its own packs and its community's, a pack's page and its builds, and its installs, updates, reattach and repair.                                                                      |
| `source-modrinth`          | Modrinth as a catalogue source: its modpacks, its installs, updates and reattach, and its project search with the lists the filters are built from.                                                                            |
| `client-render3d`          | Software 3D: the rasteriser and scene graph, and the Minecraft skin rig built on them.                                                                                                                                         |
| `nx-ui`                    | The design system: tokens, surfaces, primitives, and the drawing of whatever theme it is handed.                                                                                                                               |
| `widget-model`             | Layout graph, slot and widget identity, service and command keys. Compose-free.                                                                                                                                                |
| `widget-api`               | Widget runtime: the slot renderer, the registries, the composition locals.                                                                                                                                                     |
| `widget-processor`         | KSP processor that generates the widget registry from `@Widget`.                                                                                                                                                               |
| `widget-loader`            | Finds widget modules in the widgets folder at startup, checks the interface version they declare and hands back their registries. Separate from `widget-api`, so a module author builds against the kernel without the loader. |
| `authlib-agent`            | Java agent that redirects authlib endpoints for a SmartyCraft-bound join.                                                                                                                                                      |
| `profiler-agent`           | Java agent that observes heap and GC inside the game JVM.                                                                                                                                                                      |
| `android-app`              | The Android application: hosts the shared modules on a phone. Left out of a build on a machine without an Android SDK.                                                                                                         |

Besides these, `client-core-testing` holds test doubles shared by the modules' tests, `examples/` holds three widget modules built the way an outside author would build one, and `experimental/client-boot` is parked groundwork that is kept compiling.

Two boundaries are worth stating outright, because nothing enforces them mechanically:

- `client-launcher` and everything below it carry no `java.awt`, `javax.swing` or `javax.imageio` import. That keeps the toolkit in the UI layer, and the part of the engine the Android app shares could not use AWT anyway. The only AWT-looking strings in the engine are JVM flags handed to the game process.
- `nx-ui` depends on Compose, coroutines, serialization and a colour library, on the contracts of the look's axes, and on `ui-layers`, and on no other module of this project. A component belongs there only if it names no domain type and resolves no string of its own. That is why a source badge maps `PackOrigin` to a colour in `client-ui` and then calls a colour-taking primitive in `nx-ui`.

## Boot

The pipeline is split so a window is on screen before the slow work runs.

1. `LauncherBootstrap.preWindow` -- milliseconds only. Resolve the logs directory before the first logger call, stamp a session id into a system property so every log line traces back to one process, take the single-instance lock. A duplicate launch exits here without flashing a window.
2. The window opens and renders the boot threshold, in the theme and the words the modules compiled into the launcher answer before the boot (see [Themes](#themes)).
3. `LauncherBootstrap.completeCore` -- on a background thread. Apply a pending data-directory move, re-resolve paths after it, restore persisted SSL bypasses before any request can be made, construct the crash reporter.
4. `LauncherBootstrap.finishBoot` -- detect a pending migration, then start Koin.

Progress is reported as four phases (`Data`, `Network`, `Migration`, `Modules`) that drive the threshold's bar.

The toolkit-touching edge lives in `hivens.ui.bootstrap.GuiBootstrap`: the X11 window-class override, the Skiko vsync property, a one-line display diagnostic, and a crash handler that surfaces a Swing dialog.

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
- `widget-loader` finds widget modules in the widgets folder at startup and hands their registries to the runtime. It loads each after what it requires, over one graph of the jars and what the launcher offers (`ModuleGraph` in `widget-model`), and leaves one whose requirements are not met waiting with the reason. The modules compiled in declare the same (`requires` and `uses`), a test holds them to what their classes name, and the host brings in what a named module requires.

Extension happens through composition locals with identity defaults. The decorator that wraps every widget, the empty-slot placeholder, the unknown-widget placeholder, the slot chrome modifier and the per-instance backing renderer all default to doing nothing; the editor swaps in real ones. Production pays nothing for an editor it does not mount.

A slot owns its own arrangement: column, row, grid, free canvas, or an addressed cube grid. A widget's content is wrapped in per-instance movable content, so toggling edit mode moves the subtree rather than disposing it, and the widget keeps its loaded state.

A widget whose kind is absent from the registry keeps its stored props and children; it renders as nothing in production and as a visible placeholder in the editor. Orphans are only reaped after a schema bump.

## Themes

A theme says what the interface may be drawn with, and nothing about where. It is a ladder of steps (the page first, then the planes outward from it), the inks text is set in (a main one and a quiet one, each readable on every step), and the theme's colours in order of emphasis. Which step a card sits on and which colour a status takes is decided in `nx-ui`, not in the theme, so a theme cannot paint one place without the rest.

A theme has a dark scheme, and a light one when its author drew one. A light scheme is never derived: a theme without one stays in its own mode, and the day and night switch says so. Five ship: `Celestia`, `Lotus` and `Signal` with both modes, `Abyssal` and `Blood Rain` dark only. The wallpaper can make one more from its colours, widget modules can bring their own, and the user's own are kept in the theme file. A theme a module brought is never saved, so it leaves with the module, and a choice of it draws the default theme until the module is back.

Drawing a theme and choosing one are two modules. `nx-ui` draws the theme it is handed and never knows where a choice is kept. The themes module holds the library and the choice: which theme is in use, whether it is dark, and whether the user, the system's scheme or the wallpaper's brightness decides that. The choice is its settings domain, mirrored into the old settings file for a build from before the move. The module makes its manager itself, as an app service, from what the host hands the modules it trusts: the data directory its theme file is in, and the themes the modules brought in contribute. The host feeds it the system's scheme and the wallpaper through the inputs the module keeps for the host, and names nothing else of it.

The shell draws its windows the same way a widget reads a key: it reads one key of the look manager, `look.provide`, and wraps all it draws in it, in every window, and the words are what `I18nKeys.Words` answers at the app level. The theme and the scheme are the colour axis's answers inside that look, which the themes module gives as the look's nx colour provider, and `NxTheme { }` sets Compose and Material from them. Where the document brings in no look manager, every axis draws its base answers, colourless and dark, and where it brings in no language module, every word is drawn as its key. The language module makes its engine as an app service the same way, from the data directory and the words every owner ships.

The boot window is drawn before any key is up. Every module compiled into the launcher that the document names can answer the app sources it declares for it through `CompiledModule.boot`, worked out from the same files its keys open on, so the window opens in the theme, the scheme and the language the shell will draw, and nothing in the host names the module that answered. The document is read for that the way the shell reads it, so a document that leaves the themes or the language module out has both drawn with the same fallbacks.

The look beyond the theme is split into axes, each a module of its own under `look/`, colour, motion, shape and so on, and a module may add one. The look manager, `look/manager`, knows no axis. It takes the axes, the providers that answer them and the packs that name a provider per axis as contributions through `TakenContributions`, from every module compiled in whether the document names it or not, and from every jar switched on. It picks one provider per axis from the user's choice, its settings domain `look`: the user's own family for the axis, then the chosen pack's, then the default pack's, then the axis's base answers. Only those providers are opened, by a worker of the manager's own and never on the drawing thread, a provider the choice switches away from is closed, and one that fails in any way, a cancellation of its own included, costs only itself. An axis's answers are provided under the axis's own composition local, which the axis cannot choose, so no module reaches the locals the host provides. Settings of a provider are its own, and the manager knows none of them. A subtree can ask for the look the person would have by choosing another. The document names the manager and nothing else of the look, and the manager answers the same look before the boot, so the boot window opens in it. `nx-ui` reads the axes and still holds what the nx providers answer, except the icons, which are a provider of their own.

The form is one. Radii, borders and motion are not a choice, and there is no second style to switch to. Beside the theme sit a few switches of the user's: whether surfaces blur, whether motion is reduced, and how the navigation rail marks its selection.

## Catalogue and sources

The catalogue is an engine over sources and names none. A source is a module's contribution of the catalogue API: `PackSourceContribution` for a source of packs (what it lists, a pack's page and its builds) and `ProjectSearchContribution` for a source that searches mods, resource packs, shaders and modpacks by the catalogue's filters. The Hivens mirror and Modrinth are two such modules, `source-mirror` and `source-modrinth`, and a jar can bring a third. The host hands the catalogue module what the modules brought in through the widget kernel's `TakenContributions`, the same way for a module compiled in and for a jar, and there is no registry a source is put into. The catalogue opens each source once, with its own module's services, and the packs module installs a catalogue pack through that same listing, which the catalogue offers as the `PackSources` service. A source is refused when its id is one the core keeps (`Local`, `Smartycraft`, `Unknown`), one a source compiled into the launcher declares from another module, or one an earlier module already brought, and a source whose code throws or cannot link is left out while the others go on.

A pack's source is an open id, `PackOrigin`, kept in every pack record exactly as it was written: `Mirror`, `Modrinth`, `Local` and `Smartycraft` as before, and whatever id a source module declares. A pack whose source module is not brought in keeps its id, still lists, opens and plays, and says its source is not loaded. `Local` and `Smartycraft` are the core's own meanings. A source declares the filters it splits its own list by, such as the mirror's own packs and its community's, and Browse shows them as switches in the source's own words.

What a source does to the packs it installs is its own too. A module the host trusts brings it on what its pack source opens, as the engine's `SourcePackWork`: an installer, an updater, a reattacher, a repairer and the cover of a pack, each optional. The engine's `SourceWorks` finds it by a pack's source id through the listing the catalogue opened, and the install coordinator, the one `PackUpdater`, the reattach in `PackInstanceService` and the art of an installed pack go through it and name no source. What a source can do is what its work holds, never a declaration: only a module compiled into the launcher brings work, a jar's source only lists, and the catalogue lists each source with the abilities its work holds. Anything asked of a source that it cannot do is refused and says so, in the player's language. The machinery stays the engine's and generic: the apply guard, the snapshots and the journal, the manifest sync and its reconciling, the `.mrpack` installer and the archive updater, which Modrinth drives through a feed of its builds and any other source that publishes archives drives through its own. A pack whose source module is not loaded launches, opens, detaches, is renamed and deleted as before, its snapshots still roll back, since that needs nothing from the source, and an update, a reattach or an install says the source is missing instead of reaching another one.

What is still the engine's: the mirror's manifest updater and installer classes live in `client-launcher`, and the mirror module assembles its work from them. The content tab's updates and what a pack takes from the player, the project and build pages, the install that asks where, the mirror lookup on project pages, the parsing of Modrinth links, the brand colours and the pack screens that show what only the mirror's manifest describes still name the mirror or Modrinth. They move into the source modules next.

## Testing and what runs

Roughly 3800 test methods. The engine and the widget model are covered densely. The UI is covered thinly, and its visual output has only a few assertions: most render tests assert that a non-empty image was produced, not what is in it.

Continuous integration on a pull request runs every module that has tests -- twenty seven suites, and the build logic in `buildSrc` as a build of its own -- across Linux, macOS and Windows. It used to run eight, and each module left out of that list was one whose regressions reached a release: the widget loader is the runtime half of the validator the KSP processor enforces at build time, and the worked example is the only thing in the tree compiled against the widget ABI from outside, so it is what notices an ABI change the launcher's own modules cannot see.

Four custom scanners run strictly on every pull request: one fails on a user-facing string hardcoded outside the localisation layer, one on process metadata in comments, one on an animation that bypasses the user's motion setting, and one on a test suite missing from the matrix. None of them keeps a module list, because a list is how `nx-ui` went unscanned when it was split out: each takes every top-level directory carrying sources, and the string scanner also asks each build file whether Compose is applied. A new module is covered the day it lands. The look modules run through one aggregate, `:look:allTests`, which their parent project registers over all of them, and the matrix scanner accepts it for every module under `look/` whose tests it runs, and fails one whose tests sit where it does not.

## Packaging

Distribution is assembled by custom Gradle plugins in `buildSrc` rather than by the Compose plugin's defaults: a jlink runtime trimmed to a measured module set, a jpackage image, a macOS disk image, and an AppImage profile assembled by script with desktop-entry and AppStream metadata injected.

A verification task fails the build if the trimmed module set omits a module the runtime actually reads, since the failure it prevents is silent rather than loud.

jlink takes the runtime's native files from the JDK's jmods, where they are unsigned, while the JDK vendor signs only the finished JDK. On Windows each of the runtime's binaries is therefore replaced by the vendor's signed copy from the same JDK, after checking that the two are the same image apart from the signature. Windows' Smart App Control blocks an unsigned binary it has no reputation for, whoever loads it.

## Protocol constants

`hivens.config.Protocol` holds the values the SmartyCraft wire format requires: the mimicked launcher version, the default launcher hash, and the signature scheme's inputs. They were derived from the upstream launcher (see [`Kitty-Hivens/smrt-deco`](https://github.com/Kitty-Hivens/smrt-deco)). They are interop constants, not secrets, and are documented as such -- hiding them would only make the protocol harder to reason about.

Both the mimicked version and the base URL have runtime overrides behind explicit opt-ins, so an upstream version pin can be answered faster than a release cycle.

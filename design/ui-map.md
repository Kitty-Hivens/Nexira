# The launcher's UI: how it is put together

Measured 2026-09-17 against the tree at `8ef625b1`. Every count here was produced by a
command, not carried forward from an earlier document. If a number is quoted from
somewhere else it says so, and if a command now returns something different this document
is wrong and the command wins.

This is the architecture map: what the layers are, what each one owns, and where they
touch. It is deliberately not a defect list. The sibling documents hold those and are not
duplicated here:

- `state-of-the-codebase.md` is the census of what was built and never connected.
- `visual-work-map.md` is the list of visual defects, ordered by what a person notices.
- `editor-map.md` is the layout editor and the decisions behind it.
- `launch-content-and-auth-map.md` is the launch path.

---

## 1. The module graph, and the one rule it encodes

Twenty-one modules. `client-ui` is the terminal node: it depends on sixteen, and nothing
depends on it.

```
                   client-config
                        |
                    client-core ----------------.
                   /    |     \                  \
        client-auth  client-media  client-i18n    client-update
             |                                          |
   auth-microsoft, auth-smartycraft            client-launcher
                                                        |
   widget-model -> widget-api -> widget-loader           |
                        \-> widget-processor             |
                                                         |
              nx-ui <- client-render3d, client-easter    |
                  \                                      /
                   `------------ client-ui -------------'
```

**The rule: the core does not know Compose.** `client-launcher` (43k lines) and
`client-core` (17.5k) contain zero files importing `androidx.compose`. This holds today and
is the reason a CLI and, in principle, another front end are possible. The one file in
`widget-model` that mentions Compose is a comment explaining why `@Immutable` is not used
there.

Compose-coupled file counts: `client-ui` 262, `nx-ui` 95, `widget-api` 11, `client-easter`
7, `client-render3d` 3, `client-i18n` 1 (`AppLocale.kt`, for the `CompositionLocal`).

**Sizes.** `client-ui` 58.1k lines of main plus 17.1k of test across 431 files. `nx-ui`
7.2k main plus 6.9k test. `client-i18n` 7.6k. The entire widget kernel is 3.4k lines of
main across four modules: `widget-model` 1336, `widget-api` 1281, `widget-processor` 582,
`widget-loader` 180.

`client-ui` by package: screens 14.6k, widgets 14.1k, editor 5.5k, components 5.1k, then a
cliff to audio 2.0k, notifications 1.9k, activity 1.4k, render 1.2k.

Files over 1000 lines: `ConsoleWindow.kt` 1864, the four locale files and `AppStrings.kt`
at about 1500 each, `AppShell.kt` 1371, `ContentTabPane.kt` 1079, `EditorSurfaceHost.kt`
1055, `ContentTabState.kt` 1011.

**Resources.** The UI modules ship almost nothing: twelve fonts in `nx-ui` (Roboto Flex in
four weights, JetBrains Mono in four, Material Symbols, Noto Sans CJK JP at 7.8MB, DejaVu),
Press Start 2P in `client-ui`, two drawables, and `default-layout.json` in `widget-model`.

---

## 2. Coming up: five stages before anything is composed

`Main.kt` -> `GuiBootstrap.preWindow()` -> recovery decision -> **window** -> Koin on a
background thread -> first real frame.

The order matters and is deliberate. `preWindow` is everything that must happen before a
window and must not enter the headless CLI's reachable graph: resolve the data directory,
set the logs system property before logback first resolves a logger, take the
single-instance lock, override the X11 `WM_CLASS` by reflecting into
`sun.awt.X11.XToolkit`, install the crash handler (eagerly touching the crash dialog's
classes while the process is still healthy, because the one moment they are wanted is the
one where loading a class may no longer work), seed `settings.json` on first run, and read
three keys out of it raw, before Koin exists.

Then the recovery entry decides whether to boot at all: an environment variable, a
`--recovery` argument, a one-shot marker file, or Shift held at launch, probed through the
launcher's own short-lived libX11 connection over Panama rather than through AWT.

**Then the window is created, before boot has started.** `ShellHost` builds the single real
`Window()` and mounts `AppShellContent` underneath a `ThresholdOverlay`. The window's
callbacks are late-bound through mutable hooks rather than passed at construction,
precisely so a shell crash and restart never recreates the AWT peer and flashes.

The threshold is Tier 0 by construction: no Koin, no `NxTheme`, no widget kernel. A pixel
block-frame bar on Press Start 2P, filled per boot phase, over an opaque veil that lifts
through a Bayer-dither shader with a plain-alpha fallback if the shader will not compile.
The phase weights are hand-tuned constants (module startup owns 0.40 to 0.92 because Koin
dominates a steady-state boot) and **nothing tests them**, so a phase that changes cost
makes the bar lie silently.

### Recovery is four layers, plus one that bypasses all of them

1. **The shell restart loop.** Anything escaping composition, or stashed off the render
   thread by the window exception handler, unwinds `application {}` and re-enters it with a
   fresh composition. Koin singletons and disk state survive.
2. **The safe-mode latch.** `UiRecoverySignal` counts crashes in a fast window (20s, two
   crashes, but **one** if the cause chain holds a `LinkageError` or
   `ClassNotFoundException`) measured from the last time the shell stayed composed for five
   seconds, plus an absolute window of 300s and six. Tripping either renders the recovery
   window instead of the shell. Held in memory only, so it resets every process start.
3. **The recovery surface.** Reached at boot, never composed with Koin. Offers a switch per
   disableable module (Tray, Notify, Skinema, Keyring, written to `disabledModules` in
   `settings.json` and consumed at the next boot), four confirm-gated resets, and Continue,
   which relaunches through `AppRelauncher` (resolving `$APPIMAGE`, then the jpackage path,
   then `/proc/self/exe`, releasing the lock first and stripping the recovery variable from
   the child so Continue cannot loop back into recovery).
4. **The terminal Swing dialog**, reached only when safe mode itself crashed.

The bypass: a crash on a background thread or in a coroutine never enters any of this. It
hits the default uncaught handler installed in `preWindow` and goes straight to the crash
reporter.

### Window chrome

Custom chrome is `undecorated = useCustomChrome && !IS_TILING_WM`, read before Koin because
flipping `undecorated` afterwards recreates the peer. `IS_TILING_WM` is a Linux heuristic
over three environment variables and a fifteen-token list. On a tiling compositor custom
chrome is forced off, and the recorded reason is that undecorated AWT ignores
`_NET_WM_STATE_FULLSCREEN`, so the compositor's own fullscreen binding breaks.

Everything else in this area is a recorded workaround: dragging uses the absolute cursor
delta because the Compose-local delta oscillates; the maximized state is recomputed from
the EWMH atom or from a 95% work-area geometry check and never faked; the initial size asks
for the whole work area because asking for less while Hyprland tiled the window full-screen
produced about 1.9 seconds of white; the eight resize grips are synthetic because
undecorated drops the native border, and they lag under a compositor that owns the surface;
mouse thumb buttons are read through a raw AWT event listener because Compose surfaces only
three buttons on this platform.

---

## 3. The composition root

`AppShellContent` provides, in order: the April Fools local, then the locale (which
provides `LocalStrings`), then one block of sixteen: the layout graph, the widget registry,
the four widget registries (service, data, command, state), the three decorator hooks, the
surface renderer, the customization record, and five window handles. `NxTheme` wraps
everything after that.

The recovery window provides none of them and sits under a plain `MaterialTheme`.

**DI.** Nine Koin modules, eight from `client-launcher` plus `uiModule`, which is defined
in `client-ui` and passed in as an extra, because `client-launcher` must not know about
`client-ui` types. `uiModule` holds roughly forty singletons: the widget registries and
repositories, the audio player and its output, the media session bridge, the background
optimizer, the notification centre and archive, the four activity registries, and five
drivers marked `createdAtStart`.

One binding rule is worth stating because breaking it cost a boot: **register under the
interface the consumer resolves, not under the implementing class.** `AudioOutput` was
registered as `SystemAudioOutput`, the lookup found nothing, and the launcher died before
its first frame. Every other interface a consumer resolves has a matching
`single<Interface>`.

---

## 4. Navigation

**The model.** `sealed class Screen`, fourteen members (nine objects, five data classes),
declared in `AppShell.kt` rather than in `navigation/`. Payloads are identity only, never
domain objects, because a copied server profile aged with every fetch and made two visits
to the same server structurally different entries.

**The back stack** is `NavBackStack`: two lists, the history and a forward stack that
replays what was popped. One instance, remembered in `AppRoot`, in memory only. No
serialization, no deep links, no restore across restarts. Mutators are `navigate` (push),
`switchTo` (clear history, new root, what the nav rail uses so hopping rails never piles up
a trail), `replaceCurrent`, `back`, `forward`, and `popTo` for a breadcrumb segment.

Five things push or pop it: the router's callbacks, the nav rail, the breadcrumb, the AWT
thumb buttons, and `NavRequests`, a shared-flow singleton for callers with no composition
context. The last one has two producers and both open the same destination, and one of them
is an inconsistency: a Library card's update badge pushes through the out-of-composition
channel, which lands the versions screen on top of whatever was current with no detail page
beneath it.

**A destination's transition is one mechanism at one call site:** `NxSwap` around the
router's `when`, a thin wrapper over `AnimatedContent` carrying a `MotionRole`. No
per-screen override exists, and a back pop animates identically to a forward push because
nothing adds direction awareness.

### How a destination is assembled: three classes, and the split ignores the directories

- **Assembled from widgets (6):** Home classic, Home new, Profile, About, Background
  settings, Theme picker, Server details. These provide a context local and delegate to
  `SlotRenderer`.
- **Hand-built Compose (7):** Browse, Catalogue pack detail, Settings, Server settings,
  Pack detail, Pack versions, Wardrobe.
- **Hybrid (1):** Library, whose list is the widget graph and whose chrome (the button, the
  create dialog, the import flows) is hand-built.

Most widget-assembled destinations live under `widgets/`, not under `screens/`. Only three
files in `screens/` touch `SlotRenderer` at all. Wardrobe is the outlier in the other
direction: it lives in `widgets/`, is named like the surfaces around it, and contains no
`SlotRenderer` call.

**The router is permanently not a widget surface**, and the code says so in three separate
places. The shell chrome around it is fully graph-driven, but the screen dispatch is
smuggled through as a plain lambda, because navigation is Kotlin and not data the editor can
rearrange.

### Overlays

Mounted at the shell root, and therefore surviving navigation because they were never in
the router's composition: the activity pill (a widget, on the `appshell.overlay` surface,
deliberately inside the centre column so it never rides over the rails), the notification
stack, the two-factor and certificate prompts, the debug overlay, the update manager, the
logout confirm, and the console.

**The console is the only real second OS window in the whole UI.** Being its own
composition root, it re-wraps its own `NxTheme`, and the consequence is recorded: the
customization accent override does not reach it, only the base palette does.

Mounted inside a destination, and dying with it despite the names: `PackSettingsWindow`,
`VersionPickerWindow` and `ModVersionsWindow` are all in-composition scrim-and-card
overlays with no `Window`, `Dialog` or `Popup` call between them. **"Window" means three
different things in this tree** and only one of them is an OS window.

### State retention: three tiers in use at once

The sanctioned mechanism is a `rememberSaveableStateHolder` in the router keyed by
`Screen.retentionKey`. Only `rememberSaveable` participates. **Three sites use it**: the
pack detail's tab index, the settings category (with a name-keyed saver so reordering the
enum cannot scramble it), and a console art-editor draft.

The second tier is an app-scoped singleton standing in for retention: `BrowseSession` keys
per origin and query and holds the list, the paging cursor and the scroll position, which
is the entire reason Browse feels persistent while every one of its own fields is a plain
`remember`.

The third tier is plain `remember`, which is the default and the majority.

The gap between tiers is where the data loss lives, and two cases are valid-input bugs
rather than validator complaints. Server settings saves only from three call sites and has
no dispose-time autosave, so every shell-level way of leaving (the breadcrumb, a thumb
button, a rail switch) discards the edited Java path, heap, JVM arguments and window
settings without warning, while a mod toggle on the same screen persists immediately.
Settings persists two text fields on a 400ms debounce, and leaving inside that window
cancels the effect and silently reverts what was typed.

The one place overlay state is smuggled through a destination's parameters is
`PackDetail.openSettings`, and the reason is visible four lines apart in the same file: the
tab index uses `rememberSaveable` and the overlay flag uses `remember`, so the round trip
through `Screen` is the only way the overlay survives. It then forced a second mechanism to
exist, because `retentionKey` has to exclude those flags so two visits do not throw away the
state that is retained correctly.

---

## 5. The widget kernel

**The model.** `LayoutGraph` -> `SurfaceLayout` -> `SlotContent` -> `WidgetInstance`,
addressed by `SlotPath(surface, rootSlot, nested)`. A slot is in one of two modes: `flow`
non-null derives positions from the sequence, null means each instance carries its own
`Placement`, and `grid` is the unit (0 is one dp, N is one cell of an N-column lattice).
Schema 10. `editor-map.md` holds why.

**Persistence: three stores, three lifecycles.** The layout graph and the widget state
debounce 200ms and flush synchronously from shutdown hooks; presets write one file each,
immediately. All three go through atomic write plus fsync.

**Two migration ladders, and both are needed.** One runs before the decoder, on the raw
JSON object, because `ignoreUnknownKeys` drops a retired field before any typed step could
read it, so structural change has to happen there. The other runs after, on the model, and
is the only one that can rename a widget kind, because that means walking instances. Both
are keyed off the same version and both run on every external read, layout file and preset
alike. Below schema 8 a file is refused rather than migrated, and **left on disk**, so
downgrading the build recovers it. Above the build's schema a file is read and never
written back.

**The registry.** A KSP processor validates each `@Widget` declaration and emits one object
per module plus a `META-INF/services` entry, so the same generated shape is both the
compiled-in registry and the discovery seam for a jar. `CompositeWidgetRegistry` merges
sources with first-source-wins and records every shadowed id. The built-in registry is
always first, which is what makes the six non-removable kinds unshadowable.

**A widget pack is a plain jar** dropped into the data directory. Parent-first classloader
per module, so Compose and `widget-api` resolve to the launcher's copies (a second
compose-runtime would hand the wrong `Composer` type across the boundary). The API version
must match exactly. No sandbox, and the code says so rather than pretending otherwise.

**The inventory.** 67 compiled-in kinds (66 under `widgets/`, one in `activity/`), plus one
in `examples/`. By prefix: `home.new.*` 20 of which 12 are players, `bg.*` 15,
`appshell.*` 8, `about.*` 5, `profile.*` 4, `server.details.*` 4, and singles elsewhere.
Six are non-removable.

**42 of 67 are pinned to one surface, 25 are portable.** Pinned means the widget, or a
helper it calls, reads a context local that only one screen provides. Counting this
correctly requires following the helpers: the fifteen background widgets never name
`LocalBgSettingsContext` themselves, they reach it through `BgSlider` and `BgPicker`.

The bundled default graph places 55 instances of 47 distinct kinds across 14 surfaces and
23 slots. Twenty kinds exist only in the palette. All 23 slots are flow slots.

**The SPI a widget sees:** sixteen composition locals, and four registries reached by four
functions. `rememberSource` for reads and `rememberCommand` for writes both throw on a
missing key, because a miss is a wiring bug. `useService` returns null as a normal state,
because providers mount and unmount. `rememberWidgetState` is per-instance and persisted.

**Three of 67 widgets adapt to the size they are given** through the `AdaptiveWidget`
contract, and eleven of the twelve players do it by hand with their own
`BoxWithConstraints`, stepping a discrete control ladder by width without touching type
size. `AdaptiveWidget` computes a scale only when both axes are bounded, so **inside a flow
slot it is exactly 1** and the mechanism is inert. Every slot in the shipped layout is a
flow slot.

**The ceiling on "a widget can make a screen" is concrete.** A pack can contribute kinds and
nothing else. It cannot register a data source or a command, because those registries are
populated by hand in `Main.kt` with no discovery hook. It cannot add a surface, because
`SlotRenderer` is invoked from a screen composable naming an existing id, and the set of
fourteen is fixed by the build.

**The prune gate is armed.** The reconciler deletes instances whose kind is absent from the
registry, gated on a schema bump just having happened, sparing only kinds present in the
bundled default. Its own comment says the gate must be made conditional on registry
completeness before a second source ships. The loader shipped. Nothing has been added. What
protects users today is only that no pack ships by default.

---

## 6. The design system

Seven token families live in `nx-ui/theme`: colour (36 named fields), spacing (eleven
rungs), fixed dimensions, shape (Material's own bundle, not a parallel one), typography
(Material's scale repointed at bundled faces, with separate mono and CJK families resolved
once at the root because a fresh `FontFamily` per call is a fresh resolver cache entry),
and motion (eight roles, three easings, plus a named escape hatch for decorative rhythms).
There is **no elevation scale**: `shadowDp` is a free float, the one visual axis with no
closed vocabulary.

**Adoption is the whole story.** `Spacing` is read 51 to 66 times inside `nx-ui` and **zero
times in `client-ui` production code**. Against that stand 223 raw dp literals in
`components/`, 458 in `screens/`, 539 in `widgets/`, 251 in `editor/`. `nx-ui` itself holds
122 literals across 36 distinct values, of which 23 are the token definitions themselves.

Motion is the counter-example and the reason to believe the rule: eight roles, 73 call
sites, no bypasses, and the one bypass it did have was found by the scanner that enforces
it. Spacing is the same kind of rule with no scanner.

**Colour is a three-layer resolution, not one generator.** A hand-authored base palette per
theme, then, if a seed exists, a Material-You retint through `materialkolor` that preserves
each field's own tone (the seed decides the colour, the palette keeps the lightness), then
a theme preset overwriting four fields, then a user accent overwriting two more, and
finally the tertiary and container roles derived locally by interpolation rather than taken
from the generated scheme. The seed is either extracted from the wallpaper with a Celebi
quantizer over a 12000-pixel budget, or derived from the active theme's primary.

Nine tonal strategies and a contrast axis exist and are tested. `NxTheme` only ever calls
the default at zero contrast, so eight of them are unreachable from any UI.

**`SurfaceSpec` is seven values**, persisted with the layout and rendered by `NxSurface`
through an adapter. Null means inherit, never zero, and both states have to be
expressible. Blur is a real Skia backdrop filter behind a global switch. Three of the seven
are free-text fields hiding a closed vocabulary, which `editor-map.md` already names. The
shape vocabulary is also overstated by one: `round` is documented in the KDoc and in the
editor's hint and has no branch in the renderer.

**There is no global reduce-motion switch**, verified three ways, and the motion test's own
comment marks where its test belongs when one arrives.

**`client-ui/components` is the older layer, mid-migration.** Colour is essentially
migrated: zero Material colour-scheme reads, four raw hex literals left. Spacing is not
migrated at all. Components are adopted shallowly: nine of about twenty fitting primitives
are used. The named duplicates are the measure of what is missing rather than of
carelessness: four independent reimplementations of the icon button, two of the initials
avatar, two sliders going around `NxSlider`, six files calling raw progress indicators, and
**six hand-rolled modal shells, because no dialog primitive exists on either side**.

---

## 7. The other subsystems

- **Notifications** are three things. A live centre grouped by source, capped and
  coalescing repeated progress events. A durable archive written only on settled events.
  And a toast stack, which is **not a widget** and is composed directly by the shell. The
  history and the do-not-disturb commands are already widgetized through the data and
  command registries. A fourth, unrelated path posts to the desktop through libnotify and
  currently drives exactly one message.
- **Background** can be a still image or time-based media, classified by asking the decoders
  rather than by extension. Stills are cached downscaled to display height; oversized video
  is transcoded once by a service scoped to the app rather than to the settings screen, so
  navigating away does not cancel it.
- **Audio** plays through one engine plus a queue, with every engine touch confined to a
  single-thread dispatcher. The system output is a labelling upgrade over libsound, never a
  gate: every failure path degrades to letting the decoder open its own anonymous line. A
  separate MPRIS bridge publishes state and answers desktop transport commands.
- **`render/` is not graphics.** It is a markdown-to-Compose renderer for pack descriptions
  plus an SVG decoder, and it is the only part of the UI written as a handler of untrusted
  input: a recursion cap, a link-scheme allowlist, a byte and pixel budget, an inlining
  depth limit, and external resources denied.
- **`client-render3d` is a CPU rasterizer with no natives.** Composition flattens the scene
  graph to triangles, rasterization runs off the frame thread through a conflated flow, and
  the last finished bitmap stays on screen. Three files in `client-ui` consume it.
- **i18n is a closed interface of 1103 members** across four locales, each implementing
  1102 overrides. A missing key is a compile error, which is the strength. The weakness is
  the other face of the same fact: adding a locale means an enum entry, a branch, and a
  recompile. There is no discovery hook, while the widget registry, the puppet server and
  the April Fools engine all have one.
- **The console** owns its buffer behind an unbounded channel with a single drainer, after
  a version that mutated a snapshot list on the collector's thread froze the shell on a
  modded-Minecraft boot flood. Its settings file has a single owner for the same class of
  reason: three surfaces used to load their own copy and clobber each other.
- **Developer surfaces are gated by build identity, not by a flag.** The debug overlay is
  inert on a release channel and toggles on F9. The puppet server is two-layered: its
  sources are in a separate source set compiled in only by a Gradle flag, and even then it
  binds only when a system property names a port. On a production build the service lookup
  finds nothing and the property is inert.

---

## 8. The seams, and what is enumerated by hand

- **`Screen` is re-enumerated in four independent places**: the router, the breadcrumb
  resolver, the nav rail's own nine-member target enum with the many-to-one mapping spelled
  out by hand, and the editor's surface availability. Only the breadcrumb resolver is
  exhaustive over the sealed class, so only it fails loudly when a fifteenth destination is
  added.
- **Context locals default to `error(...)`**, so a widget mounted outside its host crashes
  rather than degrading. This is deliberate, and the editor's stub mechanism exists to
  paper over it in edit mode.
- **Two deliberate backdoor channels** bypass callback threading: the navigation request
  flow, and the activity and selection registries, which let a component four levels deep
  inside a tab drive shell-root chrome with no prop drilling.
- **Filenames are hardcoded where a constants file already exists.** The recovery surface
  names four data files as string literals while claiming in its own documentation to
  mirror their owners, so renaming one at the owner silently breaks that reset with no
  compile error. The widget state file is a bare literal in `Main.kt` while its three
  siblings are constants.
- **The composition root's `FrameWindowScope` is load-bearing.** Every window handle local
  declares a no-op default so a widget previewed outside the shell does not crash, which
  means hoisting shell content into a second window would silently degrade rather than
  fail.
- **Logback's redaction converters are referenced by fully-qualified string** in the XML,
  with no compile-time link.

---

## 9. Where this map is thin

- **Adoption has no enforcement.** Four scanners run on pull requests and cover strings,
  comment style, motion literals and the CI suite list. Nothing notices that a primitive
  has zero readers, which is the mechanism behind every entry in
  `state-of-the-codebase.md`'s "built, not adopted". Fresh instances found on this pass:
  `NxReveal` and `NxDraggable` at zero call sites, `ChamferedRectShape` unreachable from
  the shape vocabulary, eight of nine tonal strategies unreachable from any UI, and the
  spacing scale unread in the application.
- **The same closed-set shape recurs** in four places, each next door to a working
  `ServiceLoader`: the strings interface, the data and command registries, the fourteen
  surfaces, and `Screen` itself. Discovery is used for exactly three contracts in the whole
  tree. Whether any of these should open is a product question, but they should be opened or
  closed on purpose rather than by default.
- **`state-of-the-codebase.md` has drifted** since it was written and should be re-measured
  rather than cited: the translucency helper it counts at 26 files and 46 sites was deleted
  outright, `Spacing` has moved from 41 readers to between 51 and 66, the editor from 4830
  lines to about 5.4k, the widget count from 59 to 67, and the test matrix from 2519 to 2901.
- **Nothing here is verified by running the app.** Every claim is a read of the source.

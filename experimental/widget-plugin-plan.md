# Making the UI modular: decisions

Companion to `ui-layer-map.md`. The map is the evidence -- what the UI layer is made
of, measured at `63b6f097`. This document is the other half: what we decided to do
about it, and why each decision is the one that fits what the map found.

Nothing here is a description of existing code. Where a decision leans on something
that already ships, the map's section number is given rather than the code repeated.

---

## The system, in one page

Everything below is the reasoning. This is the thing being built.

**What ships is a plugin**: a directory with a JSON manifest, its artifacts beside it,
its data in a separate directory from its code, distributed as a zip of that directory. A
user installs, enables, disables and updates plugins and never the things inside them.

**What is inside are modules, addons and widgets.** A module is a unit of functionality
with no Compose in it; the four `ModuleId`s already are the first four. An addon is a
contribution that declares `extends` instead of `requires`, so it dies with its host. A
widget is a Compose contribution placed into a slot. A widget can be an addon; depth needs
no further words.

**Three things are the floor and are never optional**: configuration storage, the widget
kernel, and `nx-ui` as the token contract. **Three things sitting directly on them are
plugins**: the settings surface, the editor, the theme. That line is drawn by one test --
if everything requires it, it is not a module.

**A contribution declares a schema, not a screen.** A `@Serializable` config class; the
host stores, versions, migrates it and generates its page. That is also the launcher's one
answer to how an editing surface persists a change, where today there are three.

**Things find each other late and by string.** Sources and commands are the read and write
channels, keyed by id rather than type, so an absent partner is a null and never a
`NoClassDefFoundError`. A contract shared by two plugins is declared by a third artifact
both depend on. Load is two-phase: everything registers, then everything resolves, which
is what lets two plugins enrich each other without a cycle.

**Dependencies are `requires` and `extends`**, acyclic in `extends`, resolved
topologically, with a version range measured against the plugin API rather than the
launcher and bounded by default.

**The format is data first.** A composition, a config schema, a theme spec and an SkSL
shader carry across both runtimes; a jar is the JVM-only escape hatch for a genuinely new
primitive. One format for both builds, because two ecosystems means the smaller one rots.

**The launcher does not police what runs, and it does disclose.** What actually registered
is measured and shown; what a plugin claims to reach is declared, checked against its own
jar, and never presented as a guarantee. There is no sandbox and it is never described as
one. Recovery is a crash-loop disable, the existing recovery surface, and a local channel
for the case where nothing draws.

**And the base UI moves onto all of it.** Not as a demonstration -- as the only way the
seam gets tested by something other than its author. The editor first, because it is
already shaped that way; then the settings surface; then the theme, once the tokens are
adopted.

**The move, measured.** 47k lines in `client-ui` across 24 packages behind a single Koin
module; 58 widget kinds of which 15 are portable and 36 files pinned by a surface context;
8 screens carrying slots against roughly twenty that do not; 42 calls to the old glass
helper and 76 literal corner radii. That is the size of it, and it is why the order in
section 14 puts the model, the seam and the layering before any of it moves.

---

## 0. "Modular" names four different splits

Code splits into modules many ways, and this document mixes four of them. Naming them
apart is the difference between one plan and four, because an item on one axis does not
advance the others.

| axis | what it separates | today |
|---|---|---|
| compile | what builds independently (Gradle) | 20 modules, `client-ui` is 47k of them |
| wiring | what is constructed and bound (Koin) | 8 modules in the launcher, **1** in the UI |
| runtime-disableable | what boot recovery can switch off (`ModuleId`) | 4 |
| loadable | what arrives from outside the build | **0** |

They are orthogonal. A Gradle module is not a runtime unit; a Koin module is not a
plugin; a `ModuleId` is not an artifact. Moving a package into its own Gradle module while
its bindings stay in one `uiModule` advances the first axis and none of the others, which
is why section 12 measures stratification by the DI split rather than by the file move.

**What this is, named honestly: an IDE-tier plugin architecture.** Not "an app with
plugins" -- the shape this document arrived at independently is the one IntelliJ and
Eclipse use: the base product is itself built out of plugins, a plugin may depend on
another plugin including optionally, each plugin gets its own classloader with the core
delegated parent-first, there is no hot reload, and version ranges are measured against
the platform API rather than the product version.

Saying so is useful in both directions. It means the prior art is real and the failure
modes are known rather than waiting to be discovered. It also means the honest objection
has to be stated: architectures of this class earn their keep from ecosystem size, and
this one has a single author. What makes it feasible here is that the surface is tiny --
about 2.4k lines of kernel -- and the promise in section 5 (additive within a major)
holds only while it stays that way. An API grown to IDE proportions would make that
promise unkeepable, and an unkeepable version promise is worse than no plugin system.

## 1. "Modularity does not work" is three separate failures

They are usually said in one breath, and they have nothing to do with each other.
Fixing one does not move the others, and only one of them is actually blocked by the
others.

**Not migrated.** Eight centre surfaces carry slots. Everything else in `screens/`
(13.4k lines) is a composable the router calls, laying itself out in code. Wardrobe
is the sharpest case: built after the slot kernel shipped, placed in the `widgets/`
package, still 649 lines of hardcoded layout with no `SurfaceId` at all (map 5, 6).

**Not injectable.** The registry is a generated `object` holding a fixed `buildMap`.
There is no loader, no classloader boundary, no owner on any registration, and no way
for code the build never saw to contribute a widget, a source or a command.

**Not themeable.** The token layer of `nx-ui` is essentially unread: 0 files consume
the spacing scale, 76 literal corner radii against 29 files that ask the style for
one, 42 raw Material buttons against 71 `NxButton` (map 7).

The dependency between them runs one way and only one way. Injection does not need
migration -- a loader can work today. But an injected widget can only land where a
slot exists, so without migration the reachable surface area is eight screens. And a
contributed theme reaches only the half of the interface that routes through `nx-ui`,
which is what "customization does not actually work" means when a user says it.

So: **the base UI has to become modular first, and injection is what it becomes
modular _for_.** Not the reverse. A loader shipped against the UI as it stands would
be a loader for a launcher that has nowhere to plug anything in.

## 2. The four words: what ships, and what is inside it

Four terms were in play (module, plugin, addon, widget) and three of them describe the
same axis, which is why the fourth would not classify. There are two axes, not one:

- **what ships** -- the unit a user installs, enables, disables and updates;
- **what is contributed** -- the things inside it that register into the host.

Only one term is needed on the first axis, and the other three all live on the second.

### Plugin -- the unit that ships

A directory or jar with a manifest: id, version, what it requires, what it
contributes, what it claims to reach. It gets a classloader, a lifecycle and an
enable/disable state. The loader knows about nothing else, and a user installs,
enables, disables and updates plugins -- never the things inside them.

`plugin` takes this slot because the other three are already spoken for and because
the kernel's own vocabulary already uses it this way: the `@Widget` KDoc documents
`"<plugin-id>.<role>"` as the id convention for contributed widgets, and
`WidgetRegistryProcessor` already warns rather than errors on an injected service
contract with no provider, on the stated grounds that a plugin-supplied provider is
the point.

`PluginId` is a value class over `String`, like `SurfaceId` / `SlotId` / `WidgetKind`.
Same reason: an id is a wire identity, persisted, addressed and looked up by the same
string.

### Module -- a unit of functionality

Frontend-agnostic: no Compose, no widgets, no assumption that anything is drawn. It
owns configuration, does work, and exposes what it does through sources, commands and
services. Other things require it.

**The four `ModuleId`s are already the first four modules.** Tray, notify, skinema,
keyring -- subsystems boot recovery can disable, persisted as stable string ids in
`SettingsData.disabledModules`, with `fromId` returning null for an id the build does
not know so an unrecognised id maps to no module rather than failing the settings
decode.

That last detail matters more than it looks: the persistence contract was written
forward-compatible with modules the build has never heard of. The enum has to open up
into an id type, but nothing about the stored format or the recovery flow changes.
"Disable a module at the next boot" already exists and already means the right thing.

### Addon -- a module extension

Not a separate mechanism. An addon is a contribution that declares `extends:
<module-id>` instead of only `requires:`, and the distinction is worth naming because
it changes two behaviours:

- it is disabled when its host module is, automatically, and never listed as a
  feature of its own;
- it is meaningless alone, so a launcher missing the host module does not offer it,
  report it as broken, or ask about it.

`requires` says "I need this to work". `extends` says "I add to this, and I do not
exist without it". One manifest field, two different lifecycles.

**Depth needs no new word.** An addon whose host is itself an addon is an addon: the
chain is one edge repeated, not a ladder of ranks. Naming a tier per level would make
adding a level a change to the vocabulary and to the manifest schema at once, and the
ecosystem this intuition comes from never needed the word either -- an addon for an
addon is called an addon there too. Depth is unbounded; what is rejected is a cycle
(section 5), and what is owed to the user is the cascade view, since a disable four
hops down is otherwise silent.

### Widget -- a UI contribution

A `@Widget` composable placed into a slot. Compose-dependent by definition, so it is
exactly the part a CLI or TUI frontend loads nothing of. Widgets typically require
modules for their data and own no logic themselves.

### A widget can be an addon, and that is not a contradiction

Kind of contribution and relationship to a module are different axes. A widget that
adds a section to the settings surface is a widget by kind and an addon by
relationship: it declares `extends`, it dies with its host, and it is not offered on a
launcher without it.

The three cases sort cleanly:

| | kind | relationship |
|---|---|---|
| the widget that draws a settings page | widget | requires the config capability |
| the optional widget that navigates to settings | widget | requires a command |
| a widget that adds rows to someone else's settings | widget | **extends** that module |

### One plugin, several artifacts

A plugin is not one jar with one kind of content. A wallpaper plugin ships a module
(the engine, frontend-agnostic) and widgets (its controls) as **separate artifacts
inside the same plugin**, so a TUI frontend loads the module and never touches the
widget artifact.

This matters from the first line of the loader. If a plugin is one artifact that both
draws and computes, every plugin transitively pulls Compose, and the alternative
frontends are dead before they are attempted. Split at the artifact level rather than
at the plugin level, because the user should install one thing to get one feature.

### The one name collision, and why it is acceptable

`addon` is already used in this codebase: `SmartyModPlanner.Plan.ignoredAddon`, where
it means a Minecraft mod jar. It is confined to `client-launcher/smrt/` -- the
SmartyCraft path, which is leaving core anyway (section 11). Two domains, one word,
in code that is scheduled to separate. Accept it now; it resolves itself on
extraction rather than needing a rename first.

## 3. The floor and the replaceable surface

"Everything is a plugin" is false, and the test for where it stops is the one that
came out of asking what a wallpaper module would depend on.

If a wallpaper plugin requires the settings plugin, and settings can be disabled, then
disabling it disables the wallpaper -- and everything else, because everything needs
somewhere to keep its configuration. A thing that everything requires is not a module.
It is the floor, and calling it optional makes "optional" a fiction.

Floor is a property, not a rank: can this be absent, yes or no. It is not the bottom
step of a ladder whose upper steps need names, so however deep the dependency graph
runs there are still exactly two answers and nothing else to name.

So the line is drawn by that test, and it falls in the same place three times:

| floor (always present) | replaceable (a plugin) |
|---|---|
| config storage: read, write, version, migrate | the settings surface: screen, rail, sections |
| the widget kernel: graph, persistence, `SlotRenderer` | the editor: chrome, palette, drag and drop, prop panels |
| `nx-ui` as the token contract | the theme that fills it |

The middle row is not aspirational. The editor is already built as a set of
composition locals whose defaults are "do nothing", and `widget-api` deliberately
carries no editor and no design tokens; a release build runs the identity path with no
branch to strip (map 1). The editor was believed to be structurally a plugin already. Measured 2026-08-18, it is
not. Six of its nine locals are genuinely inert and `widget-api` is clean of editor types,
so that half holds. The rest does not: `LocalDragController` and `LocalDropTargetRegistry`
default to `error(...)` rather than to nothing and survive only because they have zero
consumers; four of the six decorators hardcode their off-branch instead of chaining, so
they shadow any outer provider; `editor/` and `widgets/` import each other, a real cycle;
`ShellRegionWidgets`, a production shell region, injects `EditModeController` for its own
collapsed state and for Ctrl+N; and `WidgetGraphReconciler` sits in `editor/` while being
called at boot from `AppShell`. Two things must move out before the editor is extractable
at all -- the reconciler into the layout kernel, and the right-rail chord out of the
edit-mode controller. What is otherwise missing
is the manifest, not the seam.

### The base UI registers through the same seam, and `NxTheme` is the proof

A seam only one side uses is a seam nobody tests. The launcher already ran that
experiment: the service SPI has exactly one provider and one consumer in the whole
tree, both halves of the same music feature (map 5), and the one place a missing
contributor is handled as a normal state rather than a crash is the widget written by
the same person, in the same commit, as its provider.

`NxTheme` is the right proof because it is the hardest case. If a theme can be
replaced wholesale, then colour, form, motion and spacing all resolve through a seam
rather than through literals.

**And that is exactly why it cannot be done yet.** Disabling `NxTheme` today produces
an interface that is half unstyled and half unchanged: 76 hardcoded radii and 26
hardcoded colours do not care what theme is loaded. Token adoption is not polish
alongside this work -- it is the precondition for the claim being true at all. So the
decision stands and its schedule is honest: theme-as-plugin lands after the token
sweep, and the token sweep is a work item rather than a side effect.

**Replaceable and disableable are different requirements, and the target is
replaceable.** A theme plugin does not modify the interface; it answers questions the
interface asks. Replacing one set of answers with another is the feature, and it needs
no coherent no-theme state to exist. Disabling the theme entirely is a recovery posture,
and it already has a precedent: `RecoveryWindow` deliberately touches no `NxTheme`
precisely so it works when theming is what broke (map 3). Aiming at replaceable keeps
"what does the launcher look like with no theme at all" out of the critical path, where
it would add work for the smaller of the two payoffs.

**The nightmare is the sweep, not the seam, and it is wide rather than deep.** Roughly
150 to 200 call sites of mechanical substitution across `client-ui`, of a kind whose
regressions do not fail a test. So it proceeds by token class rather than by file, with
an off-screen render probe per class and a pass under every style. Spacing goes first:
it has zero current readers and a proven value match (`s14` was derived from thirty-one
files that independently arrived at `14.dp`), which makes it the safest possible first
pass and a way to prove the harness before colour depends on it.

`NxTheme` is also not the first module. It is the proof. The editor is the first one:
cheap, already shaped as a seam, and coherent in its absence.

### Partial coverage: what a theme inherits, and at what granularity

A contributed theme will not cover everything the base does. Falling back to the base
for what it omits is not a convenience -- it is the compatibility mechanism. A token
group added in a later release did not exist when a third-party theme was written, so
without inheritance every launcher release breaks every theme.

**The unit of inheritance is the group, never the field.** A palette is not a bag of
independent values, and `PaletteEngine` already carries the measurements that prove it
(map 7): the severity accents are constructed but borrow the tone and the chroma the
scheme gave `error`, so a success taken from one palette and an error from another stop
being related; `tinted()` keeps the seed's hue and chroma but the base palette's tone,
without which surface and background collapsed to one near-white and the surface ladder
fell from 12.2 L* to 6; accents are exempt from that rule because pinning an accent's
tone against a scheme-derived on-colour measured 3.87 against a 4.5 floor. The hairline
is derived from the body's own luminance. Mixing halves of these across two themes
produces unreadable pairs, and it does so silently.

Three kinds of token, three rules:

| kind | examples | on omission |
|---|---|---|
| derived | the 40 `NxColors` fields | the theme declares a **spec** (seed, dark, variant, contrast); the engine generates the rest coherently |
| independent | spacing rungs, corner radii, animation multiplier, glass intensity | inherit from the base, then validate the ladder is still ordered |
| coupled | a colour and its on-colour, body and hairline, the surface ladder | all or nothing; half of a pair may not be overridden |

The first row is the important one. **A theme supplies a spec, not a table of colours.**
Then partial coverage stops being a category of problem at all: the engine completes it,
and version skew is answered by the same mechanism.

**Exactly one theme is active.** Any number installed, one selected, as the nine bundled
presets already work. Stacking themes multiplies the incoherence above, and "which theme
am I looking at" has to have one answer.

Coverage is measured, not claimed (section 9): the host knows which groups the theme
actually supplied, and the user is shown it. Otherwise "why does this theme animate like
the default one" has no answer available to the person asking.

## 3b. The pivot: the core assembles itself, and nobody is caught

Decided 2026-08-18, and everything else in this document hangs off it. Two shapes were
on the table:

- the core is a finished application with extension points bolted on -- most of the
  current code survives, seams get added, and the result is a launcher with plugins;
- the core is what assembles itself out of modules -- `AppShell`, `AppLayout`, the
  router and `uiModule` become modules themselves, and nothing stays where it is.

**The second.** With it comes the stance that goes with it: a user who assembles
something broken is not caught. No guardrail talks them out of it, no safe default
quietly overrides them. That is the project's existing position on freedom over
guardrails, applied to structure instead of to a slider.

### Where the line actually falls, and who drew it

Not by taste. The test is "what has to work when everything else is gone", and this
project answered it once already, for a different reason -- surviving a broken boot:

- `ShellHost` owns exactly one window from the first frame to process exit;
- `ThresholdOverlay` is Tier-0 by construction: no Koin, no `NxTheme`, no widget kernel,
  with the dither shader degrading to a plain alpha rect if the driver refuses it;
- `RecoveryWindow` touches no Koin, no `NxTheme`, no widget kernel and no settings
  service, speaking through `RecoveryIo`, a raw JSON read-modify-write that preserves
  keys it does not recognise.

So the core is **the loader and the boot-config reader**, and nothing else. Config
storage, the window, the boot threshold and the recovery surface are all modules -- the
first three bootstrap, the last one ordinary and possibly absent.

Recovery splits in two, and conflating the halves is what put a surface in the core:
**catching a failure and restarting is the loader's job** (it launches the modules, so it
is the only thing that sees one die and the only thing that can count a crash loop), while
**the recovery surface -- toggles, resets, buttons -- is a module** that an installation
may simply not have. Without it the way back is editing the boot file by hand, which is
consistent with the rest of the stance and needs nothing resident to support it.

This redraws the table in section 3: the widget kernel and `nx-ui` are NOT the floor.
They are the first two modules. Only configuration storage stays, because the loader
itself needs somewhere to read which modules are enabled.

### Three tiers, because the loader cannot bootstrap itself

Pushing config storage out of the core creates a loop: the loader must read which modules
are enabled, and reading is config storage. It is cut by putting two tiers below "module":

```
core       the window, the load mechanism, a hardcoded bootstrap list. No features.
bootstrap  modules that ALWAYS load, in fixed order: config, recovery, threshold.
           Absent from the config; the config cannot disable them.
modules    everything else, discovered from the config, disableable.
```

The core holds a *contract* for config storage, not an implementation -- the same seam
shape the project already ships twice: an interface plus a no-op when nothing provides
it. With no provider the core keeps the no-op, nothing past bootstrap loads, and the user
lands in recovery.

Recovery has to be bootstrap rather than an ordinary module for the obvious reason: when
the module set is what broke, an ordinary module does not load. It must arrive before the
config is read and not depend on it. That is what makes "a broken set boots to recovery"
true instead of aspirational.

The window is a bootstrap module, and so is the threshold. The threshold is decoration
over whatever the window shows: if it fails to load, the launcher comes up without a
loading readout and is otherwise fine, which is exactly why it does not belong in a
fallback.

What this buys, beyond tidiness: **config storage becomes replaceable.** It is three
stores today (`ISettingsService` whole-blob, `ConsoleSettingsStore`, `ProfileManager`);
as a bootstrap module it collapses to one, and a different format or location can be
swapped in without touching the core.

What it costs: the bootstrap set is the part that can be broken and cannot be disabled to
recover from. So it stays small, fixed, in-repo, and versioned with the core. It is not a
place third parties contribute.

### The loader is the only thing that is not a module, so it has no recovery

Recovery is a bootstrap module too, which sets the discipline for the loader: its own
failure is the one failure nothing catches. So it does the least possible.

It finds artifacts under a fixed path, builds a classloader per module, and calls a
declared entry point. **The bootstrap list comes from the boot config too** -- there is no
loop, because the minimal reader lives in the core rather than in a module, so a few dozen
lines of JSON parsing run before anything is loaded. What is hardcoded is only the
*fallback* list, used when the boot file is missing or unparseable. Configurable by
default, with a floor that cannot be edited away -- and consistent with the rest of the
stance: replacing the recovery module or the config module is one file edit. Dependency resolution -- `requires` / `extends`, version ranges, topological
order -- belongs to ordinary modules and runs only after the config module has loaded.
None of that machinery may enter the bootstrap path, or the loader stops being primitive
and starts being something that can fail in interesting ways.

It survives any bootstrap module failing. Config throws: load recovery anyway. Recovery
throws too: the core is what is left.

**The core draws nothing at all.** An earlier draft had it paint a fill and print one
English line naming the boot config -- which is a recovery surface and an entry animation,
i.e. two features in the one component that is supposed to have none. The window is a
bootstrap module like everything else. The core loads modules and hands control to
whichever one declares itself the frontend; with nothing loaded it writes a line to stderr
and exits non-zero.

What covers the case a message was meant to cover is the **bundled default boot config**.
It is not a guess at module names: it ships with the build, so it names modules this
installation actually has. A missing file is seeded from it and written; an unreadable one
falls back to it **without overwriting the damaged file**, which stays on disk, because a
launcher that repairs itself by deleting the evidence has taken the user's only lead.

Two to three hundred lines: the loader, the boot-config reader, the bundled default.

### Contracts, branch floors, and what the split actually costs

**"Module" and "optional" are not the same thing.** The absence test draws a floor per
BRANCH, not one line across the tree. The core has its floor; the UI branch has its own,
because a widget does not exist without primitives. Primitives are therefore mandatory
within their branch and still a module -- separately versioned, separately replaceable.
Being a module means owning a contract and a version, not being switchable.

Once that is separated, `fx`, surfaces and primitives stop being awkward: they are the
trunk of the UI branch rather than disputed leaves.

**Each package carries its own contract** (decided 2026-08-18). The cost is not uniform,
and it is worth seeing where it concentrates:

| package | files | contract cost |
|---|---|---|
| `icons` | 2 | cheap: small, stable |
| `effects` | 2 | cheap |
| `flexible` | 2 | cheap |
| `surface` | 4 | cheap |
| `customization` | 5 | cheap |
| `theme` | 13 | **expensive: changes with every design pass** |
| `nx` | 33 | **expensive, and changes together with `theme`** |

The residual tension, recorded rather than resolved: `theme` and `nx` evolve together -- a
new primitive asks for a new token -- so two contracts over one joint evolution means
bumping them in pairs. Every other package is small enough that keeping it additive is
nearly free.

**And a module that draws multiplies the toolchain floor.** Per 6.11: logic is an ordinary
Kotlin project, a widget adds the Compose compiler plugin, KSP and host-matched Compose
versions, and its own resources add the full Compose Multiplatform plugin. Splitting the
UI branch multiplies that alignment by the number of modules in it.

### The rewrite, measured rather than feared

The pivot touches roughly 47k lines structurally. It rewrites far less. Per file what
changes is imports (packages moved), Koin bindings (one module becomes many), and literals
becoming theme-API calls. Drawing, state holders and layout survive: a widget that draws a
card still draws a card, it changes where it imports from and stops writing `14.dp`.

The genuine rewrites are `AppShell` + `AppLayout` (~1.7k, the composition root becomes a
consumer of the loader), `uiModule` (~240), `EditorSurfaces` (~170, its hardcoded coupling
to the router and the surface contexts), the ~200 literal sites, and whichever screens the
above-slot vocabulary reaches. Five to eight thousand lines of real rewrite; the rest is
mechanical touch.

The framing that matters: those 47k are queued to be touched anyway. Six independent
audits measured that every cluster reinvented the same primitives, that
`ServerSettingsScreen` is off the library entirely, that two package cycles exist and that
419 lines are dead. Modularisation does not add that work, it gives it a target shape. The
alternative is not leaving 47k alone; it is touching 47k without one.

**Incremental compatibility is not required.** A working release line exists for users, so
`dev` may be non-functional for a stretch: no shims, no two registries live at once, no
compatibility path carried through the move. What that does NOT relax is the verification
ladder in section 13 -- those four steps exist to find a wrong model early, not to keep
the build green. The one cost to state once: while `dev` is down, the release line is not
fed.

### Granularity: do not rename the monolith

"The shell is a module, the widget kernel is a module, `nx-ui` is a module" would be 1.4k,
2.4k and 6.1k lines respectively -- 47k cut into six blobs of eight and called modular.
That is the disease at a different scale, not a cure.

Granularity follows the **contribution**, not the package. `nx-ui` is already seven
packages with different jobs (tokens, primitives, surfaces, icons, effects, customization,
flexible). The shell is window chrome, the rail, the router and the regions.

The test that decides it is the one that drew the core: **what can be absent?** Two things
that are always present together and always change together are one module. Two things
that can be missing independently are two.

One case is worth naming because it also clears a measured blocker: **a locale is a
module.** `AppStrings` is a closed interface with three object implementations, so adding
a language means editing `client-i18n` and recompiling; a locale module contributes its
string table at runtime and the blocker dissolves.

Direction, not a task for the first pass -- but the first pass must not draw module
boundaries it will have to cut again.

### The loader reports; it does not diagnose or repair

A module that will not come up -- a missing artifact, a jar that does not open, an entry
point that throws -- is skipped and recorded. That is the whole of the loader's
involvement. Verifying checksums, reinstalling, re-downloading, explaining the failure to
a person: all of it is module management, and module management is a module.

The line is exact and it is not arbitrary. `BootState.Unreadable` covers the loader's own
input, the boot config, which is its business because nothing else can read it at that
point. Damage to somebody else's artifact is not.

One thing follows and is easy to lose: if the loader only writes to stderr, the management
module has nothing to render. So the loader produces a **load report** -- what was asked
for, what came up, what did not and why. A small piece of data, not a feature, and the
same shape as the disclosure in section 9: the host measures, a module renders.

An empty declaration is a warning, not a failure. Emptying the list is a deliberate way to
ask for a bare core, so the loader says that no module was declared for loading and exits,
because nothing claimed the frontend.

### And therefore there is more than one config

A single store cannot serve both ends. The thing that decides whether the config system
loads cannot itself be the config system -- asking for that is asking for something that
cannot exist. So the layers separate by what they answer:

| | boot config | user config |
|---|---|---|
| answers | which modules load | how the application behaves |
| read by | the core, before anything | modules, after loading |
| shape | flat, stable string ids, no schema, no migration ladder, unknown keys preserved | per-module namespace, declared schema, generated page, migrations |
| takes effect | next boot | live |
| owned by | the core's minimal reader | the config module |

The minimal reader already exists. `RecoveryIo` is exactly that -- a raw JSON
read-modify-write that preserves keys it does not recognise -- written for the recovery
surface precisely because it has to work when the settings service cannot.

One concrete move follows: `SettingsData.disabledModules` is boot config living inside
the user config, i.e. inside the very system it decides the fate of. It belongs in the
boot file, read by the minimal reader, which is also what makes the existing rule
survivable -- a settings reset must keep `disabledModules`, and once they are different
files that stops being a rule to remember.

The lifetime split is the same reason `layout.json` and `widget-state.json` are two
files: different write cadence, different failure mode, so different documents.

### The one place "nobody is caught" does not apply

The way back. Freedom to assemble something unbootable is only affordable because the
recovery surface is in the core and cannot be broken by what it recovers from. Those are
not in tension -- the guaranteed floor is what makes the absence of guardrails a choice
rather than a trap. A launcher whose module set is broken boots to recovery, not to
nothing.

### Two things this settles that were open

**The boot screen is customised by data, not by code.** The threshold runs before any
module loads, because it covers the loading. A module cannot draw it. So a spec, values
and a shader -- which is the data path from 6.9 -- or nothing.

**The unsafe flag stops being protection and becomes labelling.** If breaking things is
the user's prerogative, the flag is not what permits it. What it still buys is the
distinction between "a module extended the interface" and "a module replaced it", so that
a launcher that comes up wrong has that question answered before anything is debugged.

### What this costs

Everything above the line breaks at once, and that is the honest scope: `AppShell` at
1362 lines, `AppLayout`, the router, the single `uiModule`, and the widget kernel's own
wiring. The bounded part is that everything below the line is written, tested, and was
built by the same test that draws it.

## 3c. How a module is modified: two seams, and only two

An addon changes a host module through a declared seam. There are two kinds and no third.

**A registry.** The host declares a point; the addon contributes an entry. Themes,
locales, widgets. The addon knows nothing of the host's internals and cannot break it.

**A decorator.** The host declares a place to be wrapped; the addon wraps it. Not
"change this behaviour" but "stand around it", with ordering explicit.

The substrate for the second is already written and dormant. `nx-ui/flexible` has the SPI
(`FlexibleEvent` with `isActive` / `onSignal` / `decorate`), the registry (`FlexibleHost`),
a signal bus with an open key space, a composition local and an opt-in wrapper
`Flexible(id, kind)`, plus a documented zero-cost path when nothing is active -- which is
exactly "wrap an existing control by a stable string id". It has one implementation and
`FlexibleHostProvider` is wired nowhere, so every wrapper in the tree is a pass-through
today.

**Why nothing more forceful is provided.** Reflection into another module is not a
shortcut here: modules get separate classloaders and the JVM's strong encapsulation means
reaching in needs the host to open the package anyway -- and a host that has to cooperate
may as well declare a point, which survives a rename. Bytecode transformation fails on
placement rather than on taste: it must happen at class load, so its owner would be the
loader, and the loader is two to three hundred lines that do nothing else. Two addons
rewriting one method also have no defined resolution, and a codebase about to be
restructured across tens of thousands of lines would break every transformer written
against it.

The cost is real and is the trade being made: **a module can only be modified where a seam
was declared.** When one is missing the answer is that the host adds it, not that the
addon reaches in regardless -- the same rule as the design constitution's Rule 0, where a
missing primitive is added to the library rather than inlined into a screen.

## 4. Configuration is a contract, not a screen

A plugin does not write a settings page. It declares a `@Serializable` config class;
the host stores it, versions it, migrates it, and **generates** the page.

This is not speculative. `WidgetPropPanel` already builds its form by walking
`descriptor.propsSerializer.descriptor` -- element names, serial kinds, and the
`@SerialInfo` annotations the serialization plugin copied in. No reflection, no
per-widget editor code, and the field dispatch already covers enums, explicit choices,
colours, booleans, bounded numbers and strings, with a hidden-but-serialized escape
(map 2b). It is the most reusable thing in the editor and it generalises to plugin
config unchanged.

Two things fall out of it:

- **The launcher gets one answer to "how does an editing surface persist a change".**
  Three models ship today (map 6) and a contributed settings page would ask that
  question first. For plugins the answer is fixed by the host: declare the schema, the
  host owns the write.
- **A settings page is not a capability a plugin needs.** Contributing configuration
  and contributing UI become separate acts, so a module can be fully configurable on a
  frontend that has no widgets at all.

Config storage is a host capability (section 3), which means it is the one thing a
plugin can rely on unconditionally. Everything else it needs, it requires.

### Two things measured on the settings surface that this depends on

**Storage is three stores, not one.** `ISettingsService` for `SettingsData`,
`ConsoleSettingsStore` for the console, `ProfileManager` for per-instance profiles, plus
`SslBypassStore` read-only. `ISettingsService` itself is two methods, read the whole blob
and write the whole blob, with no per-key or per-namespace accessor -- which is exactly
what a plugin's own configuration needs. So "config storage is the floor" is a target
rather than a description: making it one capability is work inside phase 1, not an
assumption phase 1 can build on.

**The descriptor a generated page would walk already exists and nothing walks it.** Both
`SettingsData` and `ConsoleSettings` are `@Serializable` today, while `SettingsFormState`
is sixteen hand-declared fields and a hand-written `mergeInto` naming all of them. The
generation is not a new mechanism; it is the mechanism `WidgetPropPanel` already runs,
pointed at a descriptor that is sitting unused.

**And the strings are a closed vocabulary.** `SettingsCategory.label` is typed
`(AppStrings) -> String`, and `AppStrings` is an interface with one `val` per string
implemented by three objects. A contributed page therefore cannot name itself: adding a
label means editing `client-i18n` and recompiling all three locales. This blocks external
contributions independently of everything else in this section, and generating pages does
not touch it -- a plugin needs its own string source, resolved at runtime, with the host's
locale selection applied to it. That is a phase 1 item nobody had written down.

## 5. Dependencies between plugins

`requires` with a version range, `extends` for the addon relationship (section 2),
load order resolved topologically.

A missing or failed dependency disables the dependent -- with a log line and a visible
reason -- and never takes the process down. A cycle is a rejected plugin set, reported
before anything loads.

The shape is not new to the project: the pack dependency work (requires/role, a
resolver, a tree view) is the same problem in another domain. Take the concept from
there rather than inventing a second resolver with different failure semantics.

### Mutual extension, and why it is not a cycle problem

Two modules that enrich each other look like a cycle and are not one, provided the
words are kept apart.

**A cycle in `extends` is rejected, and rejecting it costs nothing.** `extends` is a
statement about lifetime: I die with my host. If A extends B and B extends A then
neither exists without the other, which means they are one unit that was split for no
reason -- and the disable cascade has no answer, since turning off A must turn off B
must turn off A.

**Mutual enrichment is not `extends`.** Two modules that add to each other while each
remains meaningful alone are expressed one of two ways, both acyclic by construction:

- **a bridge** -- a third contribution that `requires` both. Disabling either disables
  the bridge and leaves the other running, which is the behaviour wanted, with no
  special case in the resolver;
- **late binding** -- one module looks up what the other contributed at use time rather
  than at registration time, and behaves correctly when the answer is absent.

**Load is two-phase, which is what dissolves the problem structurally rather than by
rule:**

1. *register* -- every plugin declares what it contributes. Nothing inspects anything
   else. Ordering matters only here, and here there are no cross-plugin dependencies
   by construction.
2. *resolve* -- everything is registered, so anything may look up anything.

Under two phases a mutual reference is not a cycle: both register in either order and
both see each other afterwards. What stays forbidden is a plugin that *constructs*
something out of another plugin's object during registration.

None of this is new machinery. Sources and commands are already app-static, registered
once at startup, and both registries already expose a `find` that returns null; the
service registry already answers a between-mount lookup with null so the consumer
recomposes next frame instead of blocking. There is already one widget in the tree that
treats a missing contributor as a normal state rather than a crash (map 5). Late
binding is the model the kernel was built on -- the plugin loader inherits it rather
than introducing it.

### Versions: what a range is measured against

A range against the launcher version is unanswerable. An author writing `[2.4.0,)` is
claiming compatibility with every release we have not made yet, and one writing
`[2.4.0,2.5.0)` is orphaned by the next bump. Neither is a choice worth offering,
because the launcher version is the wrong axis.

**Ranges are measured against the plugin API, versioned separately from the app.**
Launcher 2.7.0 can carry API 1.x indefinitely. That decouples "we shipped a release"
from "we broke plugins", which are currently the same event by construction. The API
artifacts are published separately anyway (6.11), so a version of their own costs
nothing.

**The bound is the default; an open range is a deliberate act.** `api: 1.2` reads as
`[1.2, 2.0)`. Most ecosystems default the other way, which is why open ranges are
everywhere in them. An open upper bound stays expressible, and it is disclosed
(section 9): claiming compatibility with every future version is a measured fact, and
when something breaks the user should be able to see which plugin took that bet.

**Failure has three steps, cheapest first:**

1. **The manifest check runs before a classloader exists.** If the range does not
   include us, nothing is loaded and the message names the mismatch rather than
   printing a stack trace.
2. **Entry points resolve eagerly at load.** This catches skew the range did not: an
   open bound, or use of something outside the declared API. Eager is the point -- a
   plugin that loads cleanly and throws `NoSuchMethodError` three days later when a
   particular surface is opened is far worse than one that refuses to start.
3. **The crash loop disables it.** The recovery path already latches safe mode a crash
   sooner on `LinkageError` (map 8), and `NoSuchMethodError`, `AbstractMethodError` and
   `NoClassDefFoundError` are all `LinkageError`. The version-skew detector is already
   in place; it was built for something else.

**What we owe in return**, or the version number is decoration: additive changes within
a major, breaks only on a major bump. That is keepable here precisely because the
surface is small -- three kernel modules, about 2.4k lines. A large API cannot hold that
discipline, and then the number stops meaning anything.

### Optional dependencies, and the trap a manifest cannot fix

Required and ordered are two axes, not one. Minecraft's loaders arrived at this the
long way and both split them: whether a dependency must be present is one field,
whether it must load first is another. Fusing them is what makes every optional
integration start dictating load order, which is where the cycles come back.

An optional dependency is therefore not "no ordering" but conditional ordering: after
B, if B is present. Conditional ordering can still cycle. Two-phase load (above) makes
ordering unnecessary for nearly everything, and the rare mutual conditional order stays
an error rather than being resolved by guesswork.

**The part a manifest cannot express is the one that actually breaks.** Absence is a
classloading fact, not a manifest fact: a class body that names an absent plugin's type
fails with `NoClassDefFoundError` when it is first touched, however honestly the
manifest marked the dependency optional. With a classloader per plugin (6.7) this is
sharper here than it is there.

So the constraint is on the channel, not on the declaration:

- **Optional integration goes through string-keyed channels** -- sources and commands.
  A missing string is a null from `find`. A missing class is a crash at an
  unpredictable moment.
- **A service contract shared by two plugins is declared by a third artifact both
  depend on.** Services are keyed by contract class, so the contract cannot belong to
  either side; this is the same api-and-implementation split that mod ecosystems
  converged on for the same reason.
- **A channel payload is a host type or a serialized form,** never a type owned by one
  of the two plugins talking through it.

One consequence belongs in the UI rather than the loader: **disabling a module has to
show what goes with it.** The graph makes that derivable, and a user who turns
something off and silently loses three unrelated features has been handed a bug they
cannot diagnose.

## 6. The injection seam

### 6.1 The registry becomes composite; the generated one becomes a contributor

`WidgetRegistry` is already an interface with two methods (`all()`, `get(kind)`), and
`GeneratedWidgetRegistry` is one implementation of it. Nothing has to be redesigned:
the built-in registry becomes one contributor among several, behind a composite that
resolves a kind across them.

Precedence is fixed and not negotiable by a plugin: **built-ins win a collision.** A
plugin that could shadow `appshell.left` could ship a launcher with no navigation, and
the user's way back would be a settings file they cannot reach from an interface that
no longer draws. Overriding a built-in kind is possible only under the unsafe flag
(section 8).

### 6.2 No hot reload; the plugin set is fixed for a process

Plugins load before the shell composition and the set does not change while the
process runs. Changing it takes a restart, which is exactly how `disabledModules`
already behaves: a recovery action applied at the next boot, never a hot-toggle on the
live app.

This is a cost worth paying knowingly. It buys away an entire class of failure: a
widget kind that vanishes mid-composition, a palette listing a descriptor whose
classloader is gone, a service contract whose provider unloaded between two frames.
The kernel's snapshot-state service registry is built for providers that churn as
composables mount, not for providers whose class disappears.

The persistence side already tolerates the rest. A widget whose kind is absent from
the registry renders through the unknown-widget decorator and keeps its props and
children on disk (map 1, 2), so a session run without a plugin does not cost the user
their arrangement.

### 6.3 The prune is the trap, and it has to be closed first

There is one destructive reconciliation in the whole system: `AppShell` prunes widgets
whose kind left the registry, gated on `migratedFromSchema != null` (map 2).

With plugins, that gate is wrong. A release that bumps the schema *and* a plugin that
failed to load in the same boot means the prune deletes every one of that plugin's
widgets, permanently, from a file the user cannot undo. The failure is silent and
looks exactly like the feature working.

Decision: the prune runs only when every declared plugin loaded successfully, and
never removes a kind whose owner is a declared-but-absent plugin. This lands **before**
any loader, because the loader is what makes it reachable.

The gap is narrower than "plugins were not considered". `WidgetGraphReconciler`'s KDoc
already reasons about the loaded case -- a plugin runtime registers its kinds, so they
are known and preserved. What has no representation is the plugin that was declared and
did not load: its kinds are absent from the registry and from `defaultKinds` alike, and
`prune && kind !in defaultKinds` reaps them. The first concrete step is therefore a
third set beside those two, not a rule change: something the reconciler can consult to
say "this kind has an owner that was supposed to be here".

### 6.4 Ownership is a property of the contributor, not of the declaration

`@Widget` does not gain an owner field. A widget author should not have to repeat the
id of the jar they are writing in, and a field they can type wrong is a field they will
type wrong.

Instead `WidgetDescriptor` gains `owner: PluginId` with the built-in owner as the
default, so the interface stays source-compatible; the processor emits the built-in
owner, and a plugin's registry stamps its own on everything it contributes.

Uniqueness then needs no central allocator: the validator enforces that a
plugin-contributed kind is prefixed with its owner's id. That is the convention the
`@Widget` KDoc already describes, moved from a comment into a check. `WidgetValidator`
was deliberately written against a shape that can also be applied to
`java.lang.reflect.Method` "when widgets can arrive from outside a compilation"
(map 1) -- this is that case arriving.

### 6.5 Sources and commands get owner-scoped registration

`WidgetDataRegistry.register` and `WidgetCommandRegistry.register` both `require` the
id is free and throw otherwise. That is right for app wiring, where a duplicate is a
bug in our own code and should stop the first run. It is wrong the moment a third
party can call it: a plugin claiming a taken name would take the launcher down at
startup.

Decision: the loud path stays for built-in registration. Plugins register through an
owner-scoped facade that prefixes the key and rejects the plugin -- not the process --
on a collision. A failed plugin is a disabled plugin with a log line, never a launcher
that will not start.

### 6.6 Koin is not part of the contract

Twelve widget files reach `koinInject()` directly (map 5). That works only because
every widget compiles into the same binary as the services it names.

A plugin does not get it. Binding a third party to the app's DI graph makes every
internal rename a breaking change for code we do not control, and it hands arbitrary
in-process reach to something the user dropped in a folder. Plugins see sources,
commands, services and their own config, and nothing else.

That constraint is the reason section 7 comes before the loader rather than after it.

### 6.7 Classloading: parent-first on the kernel, child-first on everything else

A plugin's KSP-generated registry lives in the plugin's own jar, so the plugin compiles
against `widget-model` and `widget-api` and must not bundle them. If it does, its
`WidgetKind` is a different class from the host's and every lookup misses while
everything compiles and loads without a word.

Decision: one `URLClassLoader` per plugin, parent-first for the kernel packages
(`hivens.widget.model`, `hivens.widget.api`, and the Compose runtime), child-first for
the rest so two plugins can carry different versions of their own dependencies.

Discovery is a directory scan, not `ServiceLoader`. The two SPI seams that ship today
(April Fools, puppet) are both `ServiceLoader` over the app classpath (map 8) -- the
right shape for a component the build knows about and the wrong one for a jar the
build never saw.

### 6.8 What a plugin is on disk

Not a single jar, and that follows from a decision already made rather than from taste:
a plugin ships several artifacts (section 2), a module without Compose and widgets with
it, so that a frontend with no widgets loads the first and never touches the second. One
jar cannot express that.

A plugin is a **directory with a manifest**, holding its artifacts. For distribution it
is a zip of that directory: one file to hand someone, a directory once installed.

The manifest is JSON. The project serializes everything with kotlinx.serialization and
JSON already -- `layout.json`, `settings.json`, the preset envelopes, the recovery
read/modify/write -- and a second configuration dialect buys nothing.

**Code and data are separate directories.** Otherwise updating a plugin either destroys
the user's configuration or requires picking it out of the replaced tree. It also gives
"the plugin's own space" in section 9 an exact meaning rather than an approximate one:
the data directory is the plugin's, the code directory is ours to replace.

### 6.9 In what form a widget arrives: code or data

The question splits before it is answered. Arrangements -- which widgets stand where
with which props -- are already solved and shipping: `LayoutGraph` in `layout.json`, and
`PresetEnvelope` for exchange (schema version, name, timestamp, graph,
`CustomizationSettings`, `UiStyle`, atomic write). Nothing there needs a format
decision. What needs one is the widget *kind*, and there are two honest answers for two
different jobs.

**Code, in a jar, when the kind is a new primitive.** Something no arrangement of
existing kinds produces: a clock aligning its tick to the second boundary, a video
widget with a lazily dropped decoder, the console canvas. Full Compose, KSP validating
at the author's build, and the prop panel works unchanged because it is already
generated from the serializer descriptor. The price is the toolchain, the version triple
and the classloader.

**Data, when the kind is a composition of existing ones.** A clock, a progress bar and
two notes arranged into one card, named and shared as a file. No code, no toolchain, no
classloader, and no security question at all. The machinery is most of the way there:
`WidgetInstance` already carries `children: Map<SlotId, SlotContent>`, containers already
declare their slots, and `PresetEnvelope` already ships a graph and a look as one file.
The map recorded this as a near-relative of the preset rather than a new concept
(map 2b).

What turns data from an instance into a kind: a namespaced id, a display name, the
sub-graph, and **prop forwarding** -- a mapping from a composite prop to an inner
instance and field, plus a declared schema for the composite's own props. Without
forwarding a composite is a frozen snapshot; with it, it is a parameterizable component.

That schema is the same mechanism as section 4: a declared schema, a host-generated
form. One form generator, two consumers -- plugin configuration and composite props.

Two things come free. The sub-graph serializes as ordinary `SlotContent` /
`WidgetInstance`, so a composite inherits the existing migration ladder and no second
versioning system appears. And a composite naming a kind that is not installed hits the
unknown-widget decorator that already exists, so it degrades rather than fails.

**Scripting is refused.** A script language -- Kotlin scripting, Lua, JS -- is the worst
of both: the code is still arbitrary, so there is no safety gained, while a runtime
interpreter, a second language in the project and desktop warm-up costs are all added.

The scheduling consequence is worth taking: **the data path can ship before the
loader.** It needs no classloader, no published artifacts and no version alignment, it
is an extension of presets that already work, and it covers most of what gets shared in
practice.

**Data is the primary format, and code is the escape hatch** -- not the other way
round. The reason is the native build (6.10): one format has to serve both runtimes, or
the ecosystem splits in two and the smaller half rots. So the data format carries the
weight, and a jar is what a JVM-only author reaches for when the format genuinely
cannot express something.

That raises the bar on the format itself, and the cost is worth stating: composition,
prop forwarding, a config schema, event bindings and a drawing hook have to be designed
properly, because together they become the ceiling of what anyone can contribute rather
than a convenience under a code hatch. That is a larger design job than the loader.

**The drawing hook is what keeps the ceiling high.** A "new primitive" is usually new
drawing rather than new logic, and drawing is expressible as a shader: an SkSL source is
a string, Skia compiles it at runtime, and it does not care what compiled the host. The
boot threshold's dither veil is the working precedent in the tree. An extension shaped as
composition plus schema plus shader plus bindings lands squarely in this project's stated
medium -- forms, procedural composition, shaders and motion, with no artist -- and it
runs on both builds.

### 6.10 The artifact format, and the native build

On the JVM there is no real choice to make. A single `.class` cannot carry a set of
classes, resources, the generated registry and a manifest; a jar is a zip with a
manifest and that is the whole answer.

The question that matters is what happens when the launcher is built natively, which is
a recorded future direction rather than a hypothetical.

**A native image cannot load code plugins.** GraalVM native-image compiles a closed
world: the class set is fixed at build time, there is no runtime classloader in any
useful sense, and reflection is registered ahead of time. This is a property of
ahead-of-time closed-world compilation, not a limitation that careful design routes
around. The one theoretical escape -- running JVM bytecode inside the image through an
embedded interpreter -- reintroduces most of what the native build was chosen to shed.

**This does not force a choice between native and extensibility.** It selects which
KINDS of extension survive, and the rule that follows is that one format serves both
runtimes rather than two ecosystems diverging:

| extension | JVM build | native build |
|---|---|---|
| data: composition, theme spec, config, shader | yes | yes |
| logic as a native shared library (Panama, C ABI) | yes | yes |
| logic as a jar | yes | no |
| a UI widget in Compose | yes | no |

The last row is the one that genuinely cannot cross, and it is worth being precise about
why: the compiler passes a `Composer` into every composable and the function composes
into the host's tree. Those are Kotlin calls, not entry points with a flat signature, so
no ABI wrapper expresses them. A native shared library also means the author ships three
platforms at minimum, which ends casual contribution on its own.

What that costs is smaller than it looks, because a new primitive is usually new drawing
rather than new logic, and drawing goes through the shader hook in 6.9. So a native build
keeps data extensions and native logic modules, and loses arbitrary Compose code.

**Independently, the native direction is gated, and the gate is dated.** Compose does not
currently work under native-image -- an upstream bug, not a configuration problem -- so
the native build is not on the table until JDK 27, which is when it is worth re-testing.
That is the same hinge two other threads already wait on: the AWT and rendering work
being upstreamed into OpenJDK, and the native-Wayland toolkit.

The practical consequence is not "ignore native". It is: design the extension format so
it would survive the move, and build nothing for the move itself.

**The decision that costs nothing today and is expensive to retrofit:** a plugin is not
defined as "a jar of classes implementing our interfaces". It is a manifest plus
declared contributions, and the artifact format is a property of the host runtime. The
manifest names the runtime an artifact needs; a host that cannot run it refuses it with
a clear reason rather than failing at first touch.

**This is the second payoff of the data path (6.9).** A composite widget is JSON over
`SlotContent` / `WidgetInstance`, and a theme supplying a spec is data as well. Neither
cares what compiles the host. A native build therefore keeps themes and composite
widgets and loses only new primitives, instead of losing extensibility entirely.

Logic modules could in principle move out of process over IPC, the way editors host
extensions. UI cannot: a widget has to compose into the host's Compose tree, and there
is no cross-process composition.

### 6.11 What a plugin author compiles against

The failure to avoid is the one mod ecosystems are known for: a small addon that
requires half the world on the compile classpath before it draws anything.

Most of that pain there is obfuscation rather than dependencies -- compiling against a
remapped artifact needs a specific build plugin, mappings and a deobfuscation step, all
version-locked to someone else's. None of that exists here.

**The toolchain floor is three-tiered, not one**, and the kernel's own build files decide
it. `widget-model` and `widget-api` are `kotlin("jvm")`, not multiplatform, so a
consumer sees plain jars with no variant selection. But `widget-api` declares
`compose.runtime` and `compose.foundation` as `implementation`, so none of it reaches a
consumer transitively, and writing `@Composable` needs the Compose compiler plugin
regardless:

| what the author writes | what the build needs |
|---|---|
| a logic module | `kotlin("jvm")` and `widget-model`. That is all |
| a widget | plus the Compose compiler plugin, KSP, `compose-runtime` / `compose-foundation` **at the host's version**, and `widget-api` |
| a widget shipping its own icons or fonts | plus the Compose Multiplatform plugin, for `composeResources` |

`nx-ui` is the one KMP module in the set, targeting `jvm("desktop")`, so it publishes as
`nx-ui` metadata plus an `nx-ui-desktop` jar. Gradle metadata resolves that on its own; a
plain Maven consumer has to know the suffix. Its `composeResources` are generated with
`publicResClass = true` under `hivens.nx.ui.generated.resources`, so the library's fonts
and icons ARE reachable from a plugin -- it is only a plugin's own resources that pull
the heavier plugin in.

The floor for a widget is genuinely small, and measured rather than intended:
`widget-model` (~0.9k, Compose-free), `widget-api` (~1.2k), `widget-processor` (~0.3k),
plus `nx-ui` (~6.1k) to look native. **None of the four can see `client-ui`,
`client-core` or `client-launcher`, and `nx-ui` has no project dependencies at all**
(map 8). They were kept that way for other reasons; this is the reason that makes them
worth keeping that way.

**The trap is already in the tree.** `Sources.kt` lives in `client-ui` and its KDoc
states why: only that module can name the source value types, since `AutoSyncService`
is `client-launcher` and `PersistedNotification` is `client-ui`. So reading the autosync
source means depending on the trunk -- exactly the half-the-world problem, present
today.

The fix is a move, not a mirror. Copying the types would put them in two places and two
copies drift, which is the reasoning `nx-ui` already applies when it takes a `publish`
lambda rather than importing the launcher's atomic-write helper (map 7). Public channel
payloads move down into a small API artifact and `client-launcher` depends on it: trunk
onto leaf, the direction that already works here.

Two costs remain and neither is avoidable:

- **Version alignment.** Kotlin, the Compose compiler and KSP have to match the host's.
  A documented triple per launcher version is the whole mitigation.
- **The API artifacts have to be published.** Otherwise the first step for a plugin
  author is cloning the launcher, and the ecosystem's worst property is imported after
  all. This is a prerequisite for the documentation step, not a follow-up to it.

## 7. What has to move before a plugin can do anything: sources and commands

This is the largest item and the one that makes the base UI modular.

Ten surface contexts exist, and 36 of 54 widget files read one. A widget that reads a
surface context is pinned to its surface, and a plugin cannot read one at all -- the
context type lives in `client-ui`, which a plugin does not depend on.

The map already sorted the ten into three kinds (map 4). Two of the three should not
be contexts at all:

- **global app state wearing a surface's name** -- `HomeNewContext`, `LibraryContext`,
  `LeftRailContext`, `RightRailContext`, most of `ShellContext`. Navigation, session,
  app state. Nothing about them is surface-specific except which composable provides
  them.
- **global settings lifted into a screen** -- `BgSettingsContext`, `ThemePickerContext`,
  `AboutContext`'s update state and hardware readout. Process-wide data that a screen
  owns only because that is where the controls are.

Both are what `Sources` and `Commands` exist for, and that registry currently holds
three sources and three commands.

`BgSettingsContext` is the proof that this is a small change per widget rather than a
rewrite: a buffer plus one update lambda, and fifteen widgets that are about twenty
lines of pure control each. Expressed as a source and a command, all fifteen work
anywhere, unchanged (map 4).

Two smaller things fall out of the same work and should be done with it rather than
after:

- `ProfileContext.accountsRevision` is a revision counter standing in for a
  `StateFlow` that `AccountStore` does not have. Fix the store, delete the counter.
- `ServerDetailsContext` and `AboutContext` hold `MutableState` written by an async
  effect in the surface -- "surface loads, widgets observe", which is what a source
  says directly.

What stays a surface context is the third kind: a genuine scope. `ServerDetailsContext`'s
specific server, `ProfileContext`'s selected category, `AboutContext.showUpdateDialog`.
Those are correct as they are.

The test that this step is done: **a widget that reads no surface context works on any
surface.** Today 15 of 58 kinds meet that bar.

## 8. The unsafe flag

`settings.json` carries an opt-in flag (working name `widgetUnsecureMode`; `insecure`
is the standard spelling and this is a wire key that is awkward to change later, so
settle it before it ships).

Default off: a plugin may only add kinds in its own namespace and fill slots.

On: a plugin may override a built-in kind, including the non-removable shell regions
-- the five regions and `profile.signin`, pinned precisely because removing them
produces a launcher with no navigation and no way to sign in.

The flag is not a security boundary and must not be described as one. A plugin is
arbitrary JVM code running in the launcher's own process with the launcher's own
rights: the keyring, the settings file, the network, the game directory. There is no
sandbox and there is not going to be one, consistent with the project's stance on
supply-chain theatre -- signing and pinning do not make untrusted code safe, they make
it feel safe. The honest mitigation is that plugins are files the user put there
deliberately, and that the launcher can always be started without them.

What the flag actually buys is a bright line between "a plugin extended the interface"
and "a plugin replaced the interface", so that when a launcher comes up broken, which
of the two happened is answerable before anything is debugged.

### The launcher does not police what the user runs

A plugin system does not give a cheater a capability they lack. The mods directory is
writable, `InstanceProfile.jvmArgs` is free text, strict mod verification is a
client-side toggle in Settings, and another launcher is a download away. The floor is
that the machine belongs to the user.

What plugins add is **distribution**: not "can this be done" but "can a file be handed
to someone non-technical that does it in one click while looking like a wallpaper
pack". That is a social problem, and the launcher cannot answer it without becoming
the thing it was written to avoid -- this project strips a surveillance coremod and
ships an open replacement, and it cannot refuse to carry someone else's probe while
shipping its own.

Two things are legitimately ours, and neither is enforcement:

- **Launch identity is not a plugin capability.** A plugin does not touch the mimic
  version, the User-Agent, or anything else the launcher presents itself with. The
  override exists and stays where it is: set by the user, by hand, deliberately.
- **Disclosure**, which is the whole of section 9.

## 9. Disclosure: what is measured, and what is merely claimed

The requirement is to show what a plugin does, and to warn when it reaches other
modules or outside its own space. The design decision is that those are two different
kinds of statement and must never be shown in the same register.

### Measured: what actually registered

Derived by the host at load time, from what the plugin contributed. No trust involved:

- widgets, sources, commands, services, config schema;
- which modules it extends, and which it requires;
- whether it overrides a built-in kind (only possible under the unsafe flag);
- what stops working if it is disabled -- the reverse of the dependency graph.

The most important warning in the whole feature is in this list. "This plugin affects
other modules" is not something a plugin declares; it is something the host observes,
and the user can be told it as fact.

### Claimed: what it says it reaches

Declared in the manifest, and this is the half that can lie:

- the filesystem outside the plugin's own data directory;
- the network;
- spawning processes;
- credentials or authentication;
- the launch pipeline -- classpath, JVM args, the mod set, the agent.

"Outside userspace" is given a concrete meaning: the plugin's own data directory is
its space, and every one of the above is a reach beyond it, declared or not shown at
all.

### Catching the lie is cheap; enforcing the claim is not

A manifest claim is worth little on its own, so the host checks it against the jar
without running it: scan the constant pool for the obvious reaches -- file and path
APIs, `ProcessBuilder` and `Runtime.exec`, socket and HTTP classes, `System.getenv`,
reflection and `setAccessible`. A plugin declaring "UI only" while referencing
`ProcessBuilder` is reported as **inconsistent with its own manifest**.

The limits are stated rather than papered over: reflection defeats a static scan, and
a determined author routes around it. That is fine, because the target is not the
determined author. It is the disguised wallpaper pack, which now has to be
deliberately obfuscated -- and obfuscation in a plugin is itself a thing worth
reporting to the user.

The scan inspects the plugin, never the user's system. That distinction is the whole
difference between this and the coremod the project exists to remove.

## 10. Getting back in when it breaks

Turning the unsafe flag on means accepting that a launcher can be arranged into one
that cannot draw its own way out. That has to have an answer before the flag exists,
not after the first report.

Three layers, cheapest first, and two of the three already ship:

1. **Automatic.** `runShellWithRecovery` already restarts the shell on a crash with
   two crash windows and latches safe mode early on a `LinkageError` -- which is what a
   plugin compiled against a kernel version that moved produces. A plugin loaded at the
   time of a repeated crash gets disabled the same way a module does, at the next boot.
2. **The recovery surface.** `RecoveryWindow` already toggles modules, touches no Koin,
   no NxTheme and no widget kernel, and speaks through a raw JSON read/modify/write that
   preserves keys it does not know (map 3). Plugins list there beside the modules. The
   existing rule that a settings reset keeps `disabledModules` generalises unchanged: a
   reset must not re-enable the plugin that was disabled to make the launcher boot.
3. **The channel.** A local control channel for the case where nothing renders at all.

The channel is deliberately not puppet turned back on. Puppet's real implementation
lives in a source dir that joins the compilation only under `-PauraPuppetPort=N`, so a
production jar carries no implementation and no system property can enable one (map 8).
That boundary is worth keeping exactly as it is.

The recovery channel is a different, narrower thing: list plugins, enable, disable,
read the last boot log. No UI actuation, no widget addressing, no arbitrary calls --
the operations `RecoveryWindow` already offers, reachable when the window cannot be
drawn.

Open: whether it listens by default or only after a failed boot. Listening only after
a failed boot is the smaller surface and covers the case it exists for; listening
always is what makes it usable for development.

## 11. The first real consumers

A seam with no outside user is untested (section 3). Two candidates exist that are not
demos.

### The SmartyCraft path, extracted rather than deleted

The raw SmartyCraft server path is deprecated and scheduled to leave core. It is real
code with launch logic, a sync service, an agent, settings of its own and UI attached
to it -- exactly the mixture a plugin has to be able to express.

Making it the first plugin turns a deletion into a move, and it is the honest test of
the model: if the SC path cannot be expressed as a plugin, the model is not strong
enough for anything a third party would write either. Better to find that out on code
we own and are removing anyway.

Timing is not decided here. The extraction is a direction; when it happens relative to
the removal schedule is a separate call.

### Community authentication providers

`client-auth` is already an SPI and was the first module extracted from the trunk, so
dynamically loaded providers are the natural second consumer.

Their contract is narrower than a general module's, deliberately: a provider receives
a request for a session and returns a result. It does not get the credential store. A
plugin handed the keyring is the worst class of plugin that could be designed, and the
narrower contract costs a provider nothing it actually needs.

## 12. Stratification: `client-ui` has one layer where the launcher has eight

A module that can be disabled has to be separable first, and in the UI nothing is. The
measure is not the line count, it is the dependency graph:

`client-launcher` declares eight named Koin modules -- network, auth, cache, mirror,
runtime, launch pipeline, update, app. `client-ui` declares **one**, `uiModule` in
`Main.kt`, over roughly 47k lines and 24 packages. The trunk is already stratified by
dependency; the UI is a single flat namespace where every binding sees every other.

That is the concrete reason "a module you can turn off" does not exist above the four
`ModuleId`s: there is no seam to turn off along. It is also why the plugin loader has
nothing to register into -- a composite registry needs layers that already know what
they own.

| package | lines | | package | lines |
|---|---|---|---|---|
| `screens` | 13425 | | `identity` | 595 |
| `widgets` | 9603 | | `threshold` | 550 |
| `components` | 4887 | | `render` | 475 |
| `editor` | 4759 | | `debug` | 419 |
| root files | 3780 | | `audio` | 392 |
| `notifications` | 1956 | | `puppet` | 362 |
| `activity` | 1338 | | `diag` | 329 |
| `background` | 1196 | | `logic` | 196 |
| `utils` | 1018 | | `platform` | 159 |
| `chrome` | 852 | | `system` | 122 |
| `bootstrap` | 702 | | `theme` | 51 |
| `layout` | 627 | | rest | ~120 |

Six leaves already left the trunk (`client-i18n`, `client-render3d`, `client-easter`,
`client-media`, `client-tray`, `nx-ui`), so the pattern is proven; what remains inside is
the half that carries state.

**Stratification is measured by the DI split, not by the file move.** Moving a package
into a Gradle module while every binding stays in one `uiModule` buys compile-time
tidiness and no separability. The test that this step is done: each layer owns its own
Koin module, and dropping one leaves a launcher that still starts.

Order inside the step follows what already worked -- leaves before trunk, and the two
packages that hold the most state (`screens`, `widgets`) last, because everything else
has to stop depending on them first.

### The library still hands out two answers, and that is the entry condition

Measured duplication against what the library already offers:

| rebuilt by hand | while this exists |
|---|---|
| three drag tracks (`VideoPlayer`, `MusicPlayerWidget`, `PlaybackMiniControlWidget`), two of them near-identical | `NxSlider` |
| a circular icon button in 10 files, 18 sites | `NxIconButton`, `NxKebabButton` |
| `glassSurfaceAlpha` in 29 files | `NxSurface`, `NxCard`, `FrostSurface` |
| 76 literal corner radii | `LocalStyle.cardCorner` / `buttonCorner` |

Not counted, because the name cannot tell a domain component from a rebuilt primitive:
the 19 local `*Card*`, 31 `*Row*` and 17 `*Section*` composables. `Dimens` versus
`Spacing` is not duplication either -- the split is deliberate and documented.

The structural cause is sharper than "the screens did not migrate". `glassSurfaceAlpha`
is public and lives in `customization/GlassHelpers.kt`, beside `surface/NxSurface`. **The
library exports both the old answer and the new one**, so new code keeps being written
the old way, correctly, against a supported API.

Closing the old door looks like the forcing move, and the call sites say it is not. Of
the 42 live calls, roughly 25 are a plane -- `.background(glassSurfaceAlpha(0.45f))`
under a card, a panel, a row -- and those are the `NxSurface(level)` sweep. The other
fifteen need a **colour**, not a plane:

- `VerticalDivider(color = glassSurfaceAlpha(0.6f))`;
- `disabledContainerColor = glassSurfaceAlpha(0.4f)` on a Material button;
- `if (selected) primary else glassSurfaceAlpha(0.5f)`, in four separate files;
- a `Text` colour, a 22dp filler strip, a tab's inactive fill.

`NxSurface` cannot serve any of them: it draws a plane, it does not return a `Color`.
Making the helper internal would leave those fifteen with nothing to move to.

So the reason 29 files still call it is not inertia. **The library shipped the drawing
half and never the naming half**, and all fifteen are asking the same question -- give me
the colour of a plane at depth N -- which only the old helper answers.

Two further calls are not about depth at all: the left rail's `props.glassAlpha` is a
user-set per-region value rather than a rung, and the chrome wedge at 0.35 is deliberately
locked to `FrostTier.Clear`'s own fill, which the helper's KDoc records as having left a
visible patch at the join when the two were split.

The real order is three steps, not one: add the colour role beside the surface primitive
(`NxTone(level): Color` next to `NxSurface(level)`), move planes to one and colours to the
other, and only then close `glassSurfaceAlpha`. Closing it first is a break with no
replacement; closing it last is a compiler-checked end to the second answer.

**This is the entry condition for phase 2, not tidying.** Phase 2 publishes the library
as a contract to people outside the repo. A plugin author who opens the tree for an
example finds three ways to draw a track and ten ways to draw a round button, and the
promise of one format is broken before anyone uses it. The library has to be the single
answer before it is published as one.

## 13. How the model gets falsified

Four steps, and the order matters more than the content: the two cheap ones have to
happen before the loader exists, and the two expensive ones are the only ones that can
actually disprove anything.

1. **Extraction in-tree, with no loader.** Take something that already ships and make
   it register through the plugin-shaped seam while still compiled into the same
   binary. If it cannot be expressed, the model is wrong and the finding cost one
   refactor rather than a loader. The editor is the first candidate: it is already a
   set of composition locals defaulting to no-op (section 3).
2. **Turn it off and see.** For each candidate: does the launcher boot and do something
   coherent without it? That is what modular means, and the mechanism already exists in
   `disabledModules` plus the recovery surface. A seam that crashes when its
   contribution is absent is decorative.
3. **Out of tree.** A jar built outside this repository, against published artifacts,
   under its own classloader. Only here do parent-first delegation (6.7), the
   Kotlin/Compose/KSP version triple and the payload-type trap (6.11) actually appear.
   An in-tree "plugin" shares the classloader and hides all three.
4. **A real consumer.** The SmartyCraft extraction (section 11): launch logic, a sync
   service, an agent, its own settings and its own UI. The mixture, not a demonstration.

The common failure is building the loader first and discovering at step 3 that the
model did not survive contact.

Some of this is testable before any of it exists, because it is pure functions over the
graph and the registry: the prune does not fire while a declared plugin is absent (6.3),
the validator rejects a kind whose prefix does not match its owner (6.4), the composite
resolves a collision to the built-in (6.1), a cycle in `extends` is rejected (section 5).
None of those needs a jar.

## 14. The work, in order

Rewritten after the pivot: the earlier version sequenced seams onto a finished app, which
is not what is being built. Dependencies are stated per item; anything without a stated
dependency can start now.

### A. Finish the core -- `:client-boot`

Done: the boot config and its reader, nine tests. `BootState` tells a first run from
damage; neither case has anywhere to go yet.

- **A1. The bundled default boot config.** A resource in `:client-boot`, the way
  `default-layout.json` is one in `widget-model`. Closes both open cases: `Absent` seeds
  from it and writes; `Unreadable` falls back to it and leaves the damaged file alone.
  *No dependency.*
- **A2. The module manifest.** Id, version, entry point, `requires` / `extends`, declared
  extension points. Parsing only -- no class loading, so it is testable without artifacts.
  *No dependency.*
- **A3. The module contract.** What a module is in code: an entry-point interface and a
  lifecycle. One open decision inside it -- whether the entry point is named by a string
  in the manifest or resolved through `ServiceLoader` inside the artifact. *Depends on A2.*
- **A4. The loader.** Directory scan, one classloader per module with parent-first on the
  kernel packages, the bootstrap set before the rest, dependency resolution for ordinary
  modules only, a load report, a crash count. *Depends on A1-A3.*
- **A5. Handover.** The core gives control to whichever module claims the frontend; with
  none, a warning that nothing was declared, and exit. *Depends on A4.*

### B. The first bootstrap modules -- proof the core works

- **B1. Configuration storage as a module.** Where the three current stores
  (`ISettingsService` whole-blob, `ConsoleSettingsStore`, `ProfileManager`) begin
  collapsing into one namespaced capability. *Depends on A3.*
- **B2. The window.** `ShellHost`'s single window, moved out of the shell. *Depends on A3.*
- **B3. The threshold.** `ThresholdOverlay` moved out; decoration over the window, absent
  without consequence. *Depends on B2.*

### C. The three blockers the audit found -- these gate everything after

- **C1. A registry that composes across modules.** KSP runs only in `client-ui` and
  `GeneratedWidgetRegistry` is one object in one fixed package, so a second module carrying
  widgets does not build at all today. This is on the compile axis and blocks the whole of
  D. **Investigate first**: if it is hard, the order below changes.
- **C2. puppet stops being `internal`.** `PuppetClick` / `Field` / `Toggle` / `Screen` are
  `internal` and used by nearly every screen, and `internal` does not cross a module
  boundary -- so this blocks every extraction in D and E regardless of anything else.
- **C3. Strings a module can supply.** `AppStrings` is a closed interface with three object
  implementations; a module cannot name itself. Resolution: a runtime string source, with
  a locale as a module.

### D. Move the base onto the core

Each of these is a module that must be able to be absent. *All depend on C1 and C2.*

- **D1. The widget kernel.** Graph, persistence, `SlotRenderer`.
- **D2. `nx-ui`, split by contract** -- cheap ones first (`icons`, `effects`, `flexible`,
  `surface`, `customization`), then `theme` and `nx`, which evolve together.
- **D3. The shell and the router.**
- **D4. The editor.** Blocked on two moves out of it first: `WidgetGraphReconciler` into
  the layout kernel (it is called at boot, not by the editor), and the right-rail chord out
  of `EditModeController`. Then the four non-chaining decorator defaults and the two
  `error()` locals.

### E. Extension points

- **E1. Points in the manifest.** A module declares named points; `extends` targets a
  point rather than a module; the version hangs on the point. *Depends on A2.*
- **E2. The theme point, and `Theme` shipped empty.** Celestia, Brut and the nine presets
  become addons. *Depends on E1, D2.*
- **E3. Wire `FlexibleHostProvider`.** The decorator substrate exists and is a
  pass-through today. *Depends on D2.*

### F. Management and disclosure

- **F1. The module manager**, consuming the load report: what was asked for, what came up,
  what did not and why. Also the owner of everything about damaged module artifacts, which
  the loader only reports. *Depends on A4.*
- **F2. Disclosure** -- measured separately from claimed, and the constant-pool scan that
  flags a manifest contradicting its own jar. *Depends on F1.*
- **F3. The recovery surface as a module**, replacing the current `RecoveryWindow`.
  *Depends on F1.*

### G. Migration, last

The slot vocabulary above the slot (dialog, window, tab, rail, floating layer) as its own
design pass, then the screens, then token adoption travelling with them. *Depends on D.*

### Independent, and cheap -- can go at any time

- **The prune gate.** `WidgetGraphReconciler` deletes widgets of kinds absent from the
  registry on a schema bump, with no representation for a module that was declared and did
  not load. A data-loss fix that stands on its own merits.
- **The NUL byte in `NotificationStack.kt`**, which makes the file invisible to every
  grep. One character.
- **Closing what the audit found already fixed**: #460 and #458 entirely, half of #433,
  #446, and the stale counts in #439.

## 15. What is deliberately not in scope

- **Hot reload.** Section 6.2.
- **A sandbox or permission model.** Sections 8 and 9.
- **Any remote or catalogued plugin source.** A plugin is a file the user placed.

## 16. Migration of the unmigrated screens

Not scheduled here, and that is the decision rather than an omission.

The map's finding is that a surface is cheap only when a screen is a shell around one
slot, and the moment a screen has an overlay, a modal, a tab bar or a rail, the kernel
offers nothing and the author writes Compose (map 6). Wardrobe went that way *after*
the kernel shipped, in the kernel's own package.

So migrating the remaining screens is gated on the slot vocabulary (order of work,
item 3), not on effort or priority. Until a dialog, a window, a tab, a rail and a
floating layer exist as things the graph can hold, a plugin cannot contribute a screen
either -- only widgets into surfaces that already exist. Converting them under the current contract would produce more of what
`LibraryScreen` already is: a surface with 280 lines of hardcoded UI stacked above its
slots because the graph has no way to hold a floating button, a context menu or a
dialog.

The one screen worth reconsidering on its own is Wardrobe, because it is a top-level
destination that migration 4 -> 5 inserted into every existing user's nav rail, and it
is the only such destination the editor cannot open.

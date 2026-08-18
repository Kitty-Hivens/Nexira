# The UI layer, as it actually is

Written from the code at `63b6f097`. The purpose is the modular work ahead: before
anything can be contributed from outside, what the launcher's own UI is made of has
to be written down, including the parts that do not fit the story the kernel tells
about itself.

Scope, measured: ~46k lines in `client-ui`, ~2.4k across the three widget-kernel
modules, ~6.1k in `nx-ui`, and another ~10k across the four UI leaf modules
(`client-i18n`, `client-easter`, `client-render3d`, `client-media`, `client-tray`).
Section 8 carries the module table and the exact figures.

Every section here was written from sources read end to end, not skimmed. The three
exceptions -- all of them value tables rather than structure -- are listed at the end
of section 8.

---

## 1. The slot kernel

Three Gradle modules, ~2.4k lines, and the only part of the UI with a stated
contract.

| module | what it holds | dependencies |
|---|---|---|
| `widget-model` | the persisted model + the `@Widget` annotation + prop metadata | none (deliberately Compose-free) |
| `widget-api` | the renderer, the registries, the composition locals | Compose runtime + `widget-model` |
| `widget-processor` | the KSP processor that generates the registry | KSP + `widget-model` |

`widget-model` staying Compose-free is load-bearing, not hygiene: the CLI entry
point and the launcher read layouts without a Compose runtime, and the annotation
is applied to composables whose `@Composable`-ness is checked by the processor
rather than by the annotation's own classpath.

### The persisted shape

```
LayoutGraph
  surfaces: Map<SurfaceId, SurfaceLayout>
    slots: Map<SlotId, SlotContent>
      orientation: Column | Row | Grid | Canvas | CubeGrid | Unknown
      gridColumns: Int                       (Grid / CubeGrid)
      widgets: List<WidgetInstance>
        kind, instanceId, props: JsonObject
        children: Map<SlotId, SlotContent>   (container widgets only)
        weight / canvas / cell               (placement, see below)
        chrome: WidgetChrome?                (per-instance backing)
```

`SurfaceId`, `SlotId` and `WidgetKind` are value classes over `String`, so an id is
a wire identity everywhere -- persisted, addressed and looked up by the same string.

Addressing is `SlotPath(surface, rootSlot, nested: List<NestedSegment>)`. The first
hop is structurally unlike the rest (the graph keys top-level slots by surface but
nested slots by a parent widget's `instanceId`), which is why `rootSlot` is a field
rather than the head of a uniform list.

`SlotOrientation` is persisted through `LenientEnumSerializer`: an orientation a
newer build wrote folds to `Unknown` and renders as Column, instead of coercing to
a plausible-but-wrong neighbour. The sentinel is never written intentionally.

### Placement, and why three fields coexist

Every instance carries `weight`, `canvas` and `cell` at once, though at most one
means anything in the slot it sits in. `FlowPlacement` states the precedence in one
place (it used to exist twice, once per flow branch, inside a composable where
nothing could test it):

| slot | honoured | ignored |
|---|---|---|
| Row / Column | `weight`, else `canvas` width/height as an upper BOUND | `canvas` x/y/z, `cell` |
| Grid | nothing -- cells are uniform | all three |
| Canvas | `canvas` offset, exact size, z | `weight`, `cell` |
| CubeGrid | `cell` address and span | `weight`, `canvas` |

The ignored fields are kept rather than cleared, so flipping a slot's orientation
and back does not cost the user their arrangement. In a flow slot a resize is a
maximum, not a fixed extent -- content that wraps keeps its natural size, so
dragging a handle past the content cannot inflate the box with empty space.

CubeGrid behaves like an Android launcher grid: `placeInCubeGrid` snaps to the
nearest free anchor, `resizeInCubeGrid` shrinks the requested span to the largest
free rectangle at the fixed anchor, and other widgets NEVER move. No compaction,
gaps allowed.

### Transforms

Every mutation is a pure `LayoutGraph.() -> LayoutGraph` in `widget-model`: insert,
remove, reorder, move (with a cycle guard so a container cannot be dropped into its
own subtree), props, chrome, orientation, grid columns, weight, canvas offset /
size / z, cube cell / span, and `resetSurface`.

Two contracts hold across all of them:

- a missing surface, slot or instance is a no-op returning `this` -- editor
  mutations race with disk reloads, and a transform on a vanished slot must not
  throw;
- an unchanged result returns the same reference, so a no-op allocates nothing.

`resetSurface` carries the non-obvious part: restoring a surface first strips the
restored `instanceId`s from OTHER surfaces, because a cross-surface move can leak
an id and the tree-wide uniqueness check would otherwise reject the reset and trap
the user.

### The registry is generated, and what the build refuses

`@Widget(id, displayName, removable, slots, propsClass)` on a top-level composable
taking exactly one `WidgetInstance`. `WidgetValidator` fails the build on: a
non-composable, a nested declaration, a wrong parameter list or type, `inline` /
`suspend` / an extension receiver, a blank id, a blank / whitespace / duplicate
slot id, a props class that is not `@Serializable`, and a props class with any
property lacking a default (the registry needs a zero-arg baseline).

Across symbols the processor fails on a duplicate id -- the generated registry is a
`buildMap`, so a shared id would silently drop a widget -- and WARNS when a widget
`@InjectService`s a contract no widget in the build provides. The warning is not an
error on purpose: a plugin-supplied provider is the point of the SPI, so a build
carrying only the consumer is legitimate; what is not legitimate is nobody noticing.

The rules live in `WidgetValidator` rather than inline in the processor because the
same set has to be applied to `java.lang.reflect.Method` when widgets can arrive
from outside a compilation.

### Four different ways a widget gets data

This is the part that matters most for the modular work, because only three of the
four survive being moved.

| channel | shape | lifetime | portable? |
|---|---|---|---|
| props | `@Serializable` class, `instance.rememberProps<T>()` | persisted in the layout graph | yes |
| widget state | `instance.rememberWidgetState { default }` | persisted per `instanceId`, outside the graph | yes |
| sources / commands | `rememberSource(SourceKey)` / `rememberCommand(CommandKey)` | app-static, registered once at startup | yes |
| services | `provideService` / `useService<T>()` | registered while the provider composable is mounted | only while the provider is on screen |

Props and widget state are deliberately separate: props are the editor's static
config, state is what the widget itself mutates at runtime (a note's text, a
checklist), and a layout undo must not revert the latter. Different write cadence
too -- per keystroke against per edit.

Sources and commands are a matched pair (read / write), string-keyed rather than
type-keyed, because two sources can share a Kotlin type and a declarative rule
references a name rather than a class. Both are plain maps: a missing or duplicate
id is a wiring bug that fails loudly. Both are Compose-free at the model level so a
future headless consumer can read `StateFlow.value` and fire a command without a
recomposer.

Services are the odd one out. `WidgetServiceRegistry` is a snapshot-state map keyed
by contract class then by provider `instanceId`; lookups return null so a consumer
in the between-mount window recomposes next frame instead of blocking, and `first()`
picks the minimum `instanceId` so two providers of one contract resolve
deterministically across restarts. Registration is split on purpose --
`DisposableEffect` owns the lifetime, `SideEffect` refreshes the bound instance on
every successful composition -- so a provider that builds its impl inline does not
flicker null between unregister and register.

The limit is stated nowhere in the KDoc, which instead says the scope is global
"one per launcher process" and that cross-surface consumption is by design: a
provider registers from inside its composable body, so a surface that is not on
screen has no provider at all. Cross-surface only works when the provider lives in
a shell surface that every screen mounts.

### Composition locals, and which are seams

`Locals.kt` declares fourteen. Grouped by what they are for:

- **wiring** -- `LocalLayoutGraph`, `LocalWidgetRegistry`, `LocalWidgetServiceRegistry`,
  `LocalWidgetDataRegistry`, `LocalWidgetCommandRegistry`, `LocalWidgetStateHost`.
  Static; provided once at the composition root from Koin singletons.
- **decoration seams** -- `LocalWidgetDecorator` (identity in production, chrome +
  drag handles in the editor), `LocalEmptySlotDecorator` (nothing / "drop here"),
  `LocalUnknownWidgetDecorator` (nothing / an "unsupported widget" placeholder),
  `LocalSlotChromeModifier` (identity Modifier / editor slot affordances),
  `LocalWidgetChromeRenderer` (identity / the glass backing, and this one is
  PRODUCTION styling, not editor-only).
- **measurement published downward** -- `LocalSlotPath`, `LocalCanvasSlotSizeDp`,
  `LocalCubeGeometry`, `LocalSlotBoundsReporter`, `LocalSlotMotionMs`.

The decoration seams are why `widget-api` carries no editor and no design tokens:
every editor-specific behaviour is a local whose default is "do nothing", and the
implementations live in `client-ui`. A release build runs the identity path with no
branch to strip.

Two are dynamic (`compositionLocalOf`) rather than static, deliberately: canvas size
and cube geometry update on every measure, and a static local would invalidate the
whole canvas subtree instead of the chrome that reads them.

### What the renderer does with all this

`SlotRenderer(surface, slot)` at a surface root, `SlotRenderer(parent, slot)` inside
a container widget -- the second extends `LocalSlotPath` so drop targets on "slot
`body` of container X" and "slot `body` of container Y" do not collide.

The slot owns its intra-slot layout: the renderer reads the orientation and lays
the widgets out itself. A widget whose kind is absent from the registry renders
through the unknown-widget decorator and keeps its props and children on disk --
non-destructive, because the kind may come back when a plugin loads.

Each widget's content is wrapped in a per-instance `movableContentOf`, so the
editor's decorator swap MOVES the subtree instead of disposing it and the widget
keeps its `remember` / `LaunchedEffect` state across an edit-mode toggle.

One limit is documented in place: Compose forbids `try`/`catch` around a composable
invocation and offers no per-subtree error boundary, so a single widget's failure
cannot be isolated in the renderer. Recovery is the shell remount at the composition
root, not a per-widget catch.

---

## 2. Persistence: the layout graph and widget state

Two files, two stores, deliberately not one.

| file | store | keyed by | write cadence |
|---|---|---|---|
| `layout.json` | `LayoutGraphRepository` | surface / slot / instance tree | debounced 200ms |
| `widget-state.json` | `WidgetStateStore` | `instanceId` | debounced 200ms, 64 KB cap per entry |

They are split because the lifecycles differ: a layout undo must not revert a
note's text, and state is written per keystroke while layout is written per edit.
State follows a moved widget (the `instanceId` survives `moveWidget`) and dies with
a removed one.

### The layout store

Envelope is `{ "schema_version": N, "graph": { ... } }`, written atomically
(`AtomicFiles`), and a malformed file falls back to the bundled default rather than
taking the launcher down.

`update` is read-modify-write under a mutex with three guards:

- an unchanged result writes nothing (the transforms preserve identity);
- a tree-wide duplicate-`instanceId` sweep rejects the mutation with a warn and
  keeps the previous graph, because one duplicate id corrupts every
  instanceId-keyed traversal in the editor;
- the sweep is skipped for geometry-only transforms (`validate = false`) -- offset,
  size, z, weight fire per drag frame and cannot introduce an id.

A file whose `schema_version` is NEWER than this build understands loads
best-effort, records itself in `NewerBuildData`, and is never written back, so an
older binary cannot downgrade and discard what it cannot represent. The shell turns
that record into the sticky "changes made now will not be kept" notice.

### Migrations, and what each one really did

`LayoutReconcile.CURRENT_SCHEMA` is 7. The ladder runs from the stored version up,
one step at a time, and a version below 1 throws rather than spinning through two
billion no-op iterations.

| step | what |
|---|---|
| 1 -> 2 | identity (`children` gained a default) |
| 2 -> 3 | identity (`chrome` gained a default) |
| 3 -> 4 | the nav rail unified onto one `nav.entry` kind -- rewrites the retired monolith into six entries and converts the per-item kinds |
| 4 -> 5 | inserts the Wardrobe nav entry after Profile, idempotent, skipped on a rail with no Profile entry |
| 5 -> 6 | the shell gained a top bar: the three regions relocate into a new `appshell.body` sub-surface and `regions` becomes a Column of [top, body] |
| 6 -> 7 | the new home's default swapped quicklaunch for the hero card -- applied ONLY when the slot still holds the exact four bundled v6 ids in bundled order |

Two rules are visible across them. A migration that reshapes a slot has to do it
explicitly, because the reconciler only ADDS -- it never reshapes what the user
has. And a migration that would overwrite a customised surface checks first: v7
compares the instance ids against the untouched default and leaves anything else
byte for byte.

`migrateNavToEntries` writes props as raw strings (`{"target": "Home"}`) because
the launcher module cannot see the `NavTarget` enum, which lives in the UI module.
A rename of that enum silently breaks the migration; a test pins the serial names.

### Reconcile, and the one destructive step

`LayoutReconcile.reconcile` = migrate -> sweep -> merge missing surfaces -> merge
missing slots -> sweep again. Both merges are purely additive:

- a surface the user edited keeps its persisted form;
- a surface dropped from the default in a later release stays in the user's file,
  since it may hold data that cannot be recreated;
- a slot the default declares on a surface the user already has is seeded, because
  `SlotRenderer` finds nothing at a new slot id and the user would get a blank pane
  with no way back.

The only destructive reconciliation -- pruning widgets whose kind left the registry
-- runs in `AppShell` and is gated on `migratedFromSchema != null`, i.e. only when
a schema bump actually happened. Outside that, an unknown kind is preserved on
disk, which is what makes an unloaded plugin's widgets survive a session without it.

### The two shutdown hooks

`LayoutGraphFlushHook` and `WidgetStateFlushHook` both flush their debounced write
on JVM shutdown, and both build every lambda in the constructor rather than at
shutdown time. The comment says why, and it is worth keeping: a lambda's class
loads when its first instance is created, so a hook that first touches its own
generated classes while the process is exiting cannot run at all if the image it
was compiled from is gone -- a self-update that replaced the running binary, or a
rebuild over a live process. That is exactly the case the flush exists for.

Orphan collection (`WidgetStateGc`) is reactive off the layout graph rather than
imperative at the editor's remove/reset calls, because instances also vanish BELOW
the editor: schema migrations, the unknown-kind prune, the duplicate-id fallback.
One collector retaining state for the currently live ids covers all of them.

## 2b. The editor: host, mutations, drag and drop

Read so far: `EditModeController`, `EditMode`, `EditorSurfaceHost`, `dnd/DragAndDrop`,
`EditorSurfaces`, `StubContexts`. The chrome, prop panels, palette and presets are
listed at the end of this section as still unread.

### One mutation entry point

`EditModeController` is a facade: every editor operation resolves to exactly one
`LayoutGraphRepository.update { transform }`, fire-and-forget on the app scope. Two
details are load-bearing:

- mutations dispatch on `Dispatchers.Default.limitedParallelism(1)`, not on the
  shared IO scope. Per-frame canvas writes would otherwise grab the repo mutex out
  of order and an older drag frame's offset could clobber a newer one;
- adding a container pre-seeds `children` with an empty `SlotContent` for every
  slot the descriptor declares. Without it the model's nested mutation
  identity-returns on a drop into the container, and the container looks alive
  (its placeholder registers bounds) while nothing persists. The pre-seed is here
  rather than in the model because the model deliberately refuses undeclared slots.

Geometry writes (`setWidgetOffset` / `setWidgetSize` / `setWidgetZ`) pass
`validate = false`; everything else keeps the uniqueness sweep.

### How edit mode is entered, and why through a signal

Edit mode is per-surface state inside `EditorSurfaceHost`, but the chord is handled
at Window scope in `AppShell` -- `Modifier.onKeyEvent` on the host box only fires
when a descendant holds focus, and the side rails steal it. So the singleton
controller carries a tick (`editToggleSignal`), the host observes it through
`snapshotFlow`, and the same shape is reused for the right-rail toggle (Ctrl+N).

The observer seeds `seen` from the current tick before collecting, so navigating
into a fresh host does not toggle on mount.

There is a second way in, and it is the discoverable one: right-clicking the
background while NOT editing opens a one-item context menu ("Edit layout", hinting
Ctrl+E). It runs on `PointerEventPass.Final`, so a press any widget claimed belongs
to that widget.

Escape exits in stages: an open slot menu closes first, then the slot selection
drops, then edit mode ends.

### What the host provides, and to whom

The host wraps the WHOLE shell row (rails included), so editor decorators reach
rail widgets, and keeps its chrome overlays inset past the rails so they stay over
the centre pane. It provides eight locals: edit state, drag controller, drop
registry, the three decorators, the slot chrome modifier, the slot motion duration,
the slot bounds reporter -- plus `*EditorSurfaces.stubs` spread as a vararg.

Each decorator has the same shape: real behaviour only when editing AND not
previewing, otherwise CHAIN TO THE PARENT rather than to identity. That chaining is
the fix for a real bug -- the host wraps the whole tree, so a hardcoded identity
here would shadow the debug overlay's instrumentation everywhere.

The widget decorator additionally checks `address.surface != selected` and renders
plain, so only the surface being edited grows chrome.

`EditorSurfaces` is the registry that made the surface set open: id, icon, two
names, whether it has surface-level settings, the stand-in context, and which
screen mounts it. Six disjoint spots used to answer those questions separately, and
a surface added to five of them looked fine and behaved wrong in the sixth. The
stub list is DERIVED from the registry, so a surface that declares a context gets
it provided by existing.

### Drag and drop

`DragController` holds one nullable `ActiveDrag` (payload, pointer, pickup offset,
size, ghost composable). Payload is either an existing widget (source path, index,
instance) or a palette kind.

`DropTargetRegistry` keys everything by full `SlotPath`, so "slot `body` of
container X" and the same slot id on the right rail never collide. Hit-testing is
two passes:

1. every registered rect -- widget rects AND empty-slot placeholder rects -- competes
   by area, smallest wins, ties broken by nesting depth. Both kinds compete in ONE
   pass on purpose: a nested empty slot must be able to beat its enclosing
   container's own widget rect, or the user can never drop into it. The depth
   tie-break exists because `SnapshotStateMap` iteration order is unspecified and a
   flickering hit-test reads as the editor jumping;
2. a vertical-span fallback with 12px tolerance for gap drops, consulted only when
   pass 1 finds nothing.

Insertion index is main-axis midpoint comparison, with a row-major variant for
Grid slots.

The drag source modifier attaches to the HANDLE, not the widget, so dragging cannot
start from an interactive control inside it. During a drag the OS cursor is hidden
by attaching a transparent AWT cursor to the full-screen ghost overlay --
`pointerHoverIcon` does not intercept events, so the drag handler keeps receiving
updates. The ghost translates by pointer-minus-overlay-origin, because
`graphicsLayer` translation is relative to the overlay's own untranslated position
and the host sits past the left rail.

### Presets go through the same pipeline as a disk load

Loading a preset runs `LayoutReconcile.reconcile` (migrate + merge + uniqueness)
AND then `WidgetGraphReconciler.reconcile` with the registry, because the
structural pass knows nothing about descriptors: a preset saved before a container
declared a child slot would otherwise arrive without it, and that slot would draw
its placeholder, highlight on hover and swallow every drop until the next restart.

A preset that reconciles to a duplicate id is refused with a log rather than
written into live state.

### The prop editor is generated from the serializer

This is the most reusable thing in the editor, and the one that matters most for
outside contribution: `WidgetPropPanel` builds its form by walking
`descriptor.propsSerializer.descriptor` -- element names, serial kinds, and the
`@SerialInfo` annotations the serialization plugin copied in. No reflection, no
per-widget editor code.

`PropFieldRow` dispatches in this order: enum kind -> `@PropChoice` -> `@PropColor`
-> boolean -> Int/Float WITH a `@PropRange` (a slider needs bounds) -> string field.
`@PropHidden` drops the field from the form while keeping it serialized.

The value shown is the descriptor's encoded default baseline overlaid with the
instance's stored props, so every key is present and no field is ever null. Writing
a field re-emits the whole merged object, so "reset to default" is an empty object.

Below the typed section is a universal Backing block -- glass percent, corner,
uniform padding and four per-side overrides -- available on EVERY widget including
propless ones, since `WidgetChrome` lives on the instance rather than in props.

A surface can also carry settings of its own (`SurfacePropertiesPanel`), currently
only the left rail's selection style; those write through `CustomizationSettings`,
which is what the rail reads at runtime, so the change is live and outlives edit
mode. It was moved out of global Appearance deliberately: the highlight belongs to
the rail, and in the rail's own panel it cannot orphan when the rail is rearranged.

### Chrome, selection, and who wins a click

`EditableWidgetChrome` wraps each widget on the edited surface. Notable decisions:

- the whole body is the drag surface, not a separate handle, and the gesture
  consumes the press -- while editing you arrange a widget, you do not operate it;
- the resting outline is drawn INSIDE the widget's bounds via `drawWithContent`,
  because the earlier padded-border approach added ~12dp per widget and shifted the
  whole surface down on entering edit mode;
- right-click is detected with raw events rather than the drag gesture, because
  `awaitFirstDown` only fires for the primary button and a bare right-click used to
  fall through to the slot;
- the gesture is keyed on instance, path AND orientation, since which branch it
  takes IS the orientation -- a slot flipped from Column to CubeGrid under a running
  gesture left it reordering a grid.

The precedence between widget and slot is explicit rather than a race: the widget
handles presses on the Main pass, the slot's chrome modifier handles them on the
FINAL pass and acts only on a press nothing consumed.

`CanvasGeometry` keeps the arithmetic Compose-free and unit-testable: window point
to slot-local dp, drag offset with a clamp that always leaves a 24dp grab margin
on-canvas, resize with a 48dp floor, and the cube-grid cell/span rounding.

### The palette, and the ghost problem

The palette lists only `removable` descriptors. Non-removable ones (the shell
regions, the auth panel) are surface-essential: the default layout pins exactly one
instance and the chrome hides their remove button, so exposing them would let a
user drop duplicates they could never remove.

The drag ghost for a PALETTE item is a labelled chip, not the real widget, and the
comment says why: a composable that throws cannot be caught mid-composition, so
rendering a surface-context-dependent widget during a palette drag would crash
every frame.

The ghost for an EXISTING widget does render the real content -- by capturing
`currentCompositionLocalContext` at the chrome and restoring it inside the ghost,
because the ghost is invoked at the host level, outside the surface composable's
provider chain. That capture is the same problem the whole per-surface context
model has, solved locally for one case.

### Presets are already an interchange format

`PresetEnvelope` = schema version + name + timestamp + `LayoutGraph` +
`CustomizationSettings` + `UiStyle`, one file per preset, atomic write. The
filename is derived from the name but is NOT the name: everything outside
`[A-Za-z0-9_-]` collapses to an underscore, so two Cyrillic names of equal length
mapped to the same file and the second save replaced the first. A 4-byte digest of
the original name now keeps them apart, the display name is read from the envelope,
and a legacy pre-digest path is still resolved for reading.

Worth noting for what comes next: this envelope is already "a layout plus a look,
in one shareable file". A theme or layout contribution that arrives as data rather
than code would be a near-relative of it, not a new concept.

## 3. The shell: one window, five regions

### The window outlives everything

`ShellHost` owns exactly ONE window from the first frame to process exit. Boot runs
BEHIND it: while the boot thread works, `ThresholdOverlay` covers the canvas; when
the outcome flips to Ready the shell mounts UNDER the still-opaque overlay (which
masks its expensive first composition) and the overlay reveals it. Content switches,
the window never remaps -- which is the whole point on XWayland, where recreating a
window is a visible flash.

Two values must be known before Koin exists, because changing them later recreates
the AWT peer: `undecorated` and the locale. `SettingsPeek` reads them straight out
of `settings.json` with defaults that MUST mirror `SettingsData`'s, and
`WindowChromeHooks` late-binds the close and key handlers once the shell mounts.

The window is created at the work-area size rather than a nominal 1100x720, and the
comment records the measurement: Compose draws one frame before showing the window,
sized to what was asked for, so a tiled compositor gave ~1.9 seconds of white around
a correctly drawn corner. No window-manager branch -- a floating desktop maximises
right after showing, a tiling one assigns the frame itself, and the work area is
closest to both.

### Everything about window management is measured, not assumed

`WindowMaximizer` never fakes its own state: `maximized` is recomputed only from
what the system did -- the WM's `extendedState` and the frame actually filling the
work area -- and maximise/restore command AWT directly rather than going through
`WindowState.placement`, whose "apply only on change" caching swallows a request
when Compose's observed placement has drifted. `supported` asks the toolkit, and
the caller hides the button when the WM cannot do it.

`IS_TILING_WM` (env probe over a token list) gates three separate behaviours:
custom chrome is off there (an undecorated AWT window ignores the WM's fullscreen
state), the drag-to-move area stands down, and the synthetic resize grips stand
down. The rule is consistent: where the compositor owns geometry, the launcher does
not fight it.

Window drag anchors against the ABSOLUTE screen cursor rather than the Compose-local
delta, because moving the window shifts the frame the next delta is measured in and
the window oscillates.

### The router, and what is not a widget yet

`AppLayout` builds `centerBody` -- a `when` over `Screen` -- and hands it to the
shell surface through `ShellContext`. Navigation is deliberately NOT in the layout
graph: the screen router lives in code, and the graph carries only where the regions
sit.

`NavBackStack` is a real stack: top-level destinations (the seven nav-rail targets)
RESET it, everything else pushes, and a forward stack supports browser-style
forward. `popTo` moves the popped entries onto the forward stack so a breadcrumb
click is reversible.

### The regions are widgets, and they are not removable

`appshell.root/regions` is a Column of [top, body]; `appshell.body/content` is a Row
of [left, center, right]. Five region widgets, all `removable = false`, each with
its own props class -- and the prop classes differ ON PURPOSE: the left rail has no
`glassAlphaPct` (it renders an `NxSurface` whose matte is `frostTier`), the right
panel has neither that nor `showDivider`, because "the prop panel shows only what
works". That is a design rule worth keeping when props start arriving from outside.

The centre region hosts `appshell.overlay/bottom` -- a floating lane inside the
content column, so anything docked there is bounded by the centre pane and never
rides over the rails.

The right panel is the most behavioural: a horizontal swipe anywhere on it drags
its width and snaps on release, it auto-collapses below a 980dp window, Ctrl+N
toggles it through the same controller-tick bridge the editor uses, and in edit mode
all of that is replaced by static prop-driven behaviour. `RightPanel` is held in a
`movableContentOf` so the edit-mode branch swap MOVES it instead of disposing and
reloading the news feed on every Ctrl+E.

The top bar is chrome and content at once: the caption buttons are chrome (left on
macOS, right elsewhere, hidden by default on tiling WMs), while `appshell.topbar`
carries three widget slots (left / center / right) and a drag lane that becomes a
widget drop-lane while editing.

### Recovery is deliberately outside all of it

`RecoveryWindow` touches no Koin, no NxTheme, no widget kernel and no settings
service -- exactly the things a recovery boot distrusts -- and speaks through
`RecoveryIo`, a raw JSON read/modify/write that preserves keys it does not touch.
It offers module toggles (the four `ModuleId`s) and three resets, then relaunches,
because cached-at-boot settings only take effect in a fresh process.

Two details worth carrying into the module work: a settings reset KEEPS
`disabledModules`, or the reset would re-enable the very module that was disabled to
make the launcher boot; and `RecoveryEntry` resolves four independent entry signals
(env var, `--recovery`, a one-shot marker file, and Shift held at launch probed
through libX11 via Panama).

## 4. Surfaces and their contexts

Ten surface contexts, each a `staticCompositionLocalOf { error(...) }` provided by
one surface composable and read by that surface's widgets. This is the layer that
does not survive a widget being moved, so it is worth having in full.

| context | provided by | carries |
|---|---|---|
| `LocalLeftRailContext` | `AppSidebar` | current screen, auth flag, navigate, logout |
| `LocalRightRailContext` | `RightPanel` | app state, login, logout, ssl-bypass flag |
| `LocalShellContext` | `AppLayout` | the above plus `centerBody`, the breadcrumb trail and its five nav callbacks |
| `LocalHomeClassicContext` | `DashboardScreen` | session, selected server, four callbacks |
| `LocalHomeNewContext` | `NewHomeScreen` | app state, navigate, session-updated |
| `LocalLibraryContext` | `LibraryScreen` | app state, navigate |
| `LocalProfileContext` | `ProfileSurface` | session, selected category, accounts revision, login, logout |
| `LocalAboutContext` | `AboutSurface` | update state, dialog flag, trigger, and pre-measured RAM / swap / CPU / display / renderer |
| `LocalBgSettingsContext` | `BgSettingsSurface` | the live settings buffer + one commit lambda |
| `LocalServerDetailsContext` | `ServerDetailsSurface` | the server, its assets dir, description and banner holders |
| `LocalThemePickerContext` | `ThemePickerSurface` | preset list, pending selection, apply, back |

Sorted by what they actually contain, they are three different things:

1. **Global app state wearing a surface's name** -- `HomeNewContext`,
   `LibraryContext`, `LeftRailContext`, `RightRailContext`, and most of
   `ShellContext`. Navigation, session, app state: nothing about them is
   surface-specific except which composable happens to provide them.
2. **Global settings lifted into a screen** -- `BgSettingsContext` (the wallpaper
   settings buffer), `ThemePickerContext` (the preset list),
   `AboutContext.updateState` plus the hardware readout. The data is process-wide;
   the screen owns it only because it is where the controls live.
3. **A genuine scope, or genuinely local UI state** -- `ServerDetailsContext` (a
   specific server, its assets dir, its two async holders), `ProfileContext`'s
   selected category and accounts revision, `AboutContext.showUpdateDialog`.

Only the third kind actually needs to be a surface context. The first two are what
`Sources`/`Commands` already exist for -- and that registry currently holds three
sources and three commands against 52 files on contexts.

### What the contexts are compensating for

Two of them carry a revision counter rather than data:
`ProfileContext.accountsRevision` exists because `AccountStore` has no reactive
seam, and sibling slots (the nav's face picker and the account section) cannot
otherwise see each other's sign-outs. That is a store missing a `StateFlow`, solved
at the surface level.

`ServerDetailsContext` and `AboutContext` both hold `MutableState` written by an
async effect in the surface -- an ad-hoc "surface loads, widgets observe" pattern
that a source would express directly.

### The stub wall

`EditorSurfaces.stubs` provides a no-op instance of every one of these BELOW the
active surface, so a widget dragged onto a foreign surface renders instead of
throwing. The comment is explicit that this is a safety net until
"widget capability metadata + palette filtering" narrows the palette per surface.

Two ways the launcher already works around the same limit are worth recording,
because they are the honest measure of the problem:

- the drag ghost captures `currentCompositionLocalContext` and restores it, or a
  widget rendered at host level would throw on its surface context;
- the palette refuses to render the real widget at all and shows a labelled chip.

### Where the pattern is at its best

`BgSettingsContext` is the cleanest of the ten: a `MutableState` buffer plus one
`update: (BackgroundSettings.() -> BackgroundSettings) -> Unit`, so fifteen widgets
(`bg.enable.toggle`, the seven effect sliders, position, scale, loop, tint, picker,
reset) are each about twenty lines of pure control. If those settings lived in a
source and a command, every one of those widgets would work anywhere, unchanged.

The Appearance studio is also the one screen that already made the "preview" leap:
it has no preview pane, because the live shell behind the panels IS the preview.

## 5. Widgets, by family

58 registered kinds across 54 files. Grouped by what they belong to rather than by
where the file sits, because two families live in a package named after neither --
and one widget does not live in `widgets/` at all: `appshell.activity.pill` is
declared in `activity/ActivityPillWidget.kt`, beside the registry it renders.

| family | kinds | what they are |
|---|---|---|
| shell | 8 | the five regions, `nav.entry`, the breadcrumb, the compact news panel |
| background settings | 15 | one control each: enable, seven effect sliders, position x/y, scale, loop, tint, picker, reset |
| sample / generic | 9 | clock, music, playback mini, video, progress, launch tile, spacer, and the two containers |
| about | 6 | logo, system card, links card, update panel, credits, check-again |
| profile | 4 | nav, account section, skin section, sign-in |
| server details | 4 | title, tag bar, description, banner |
| home (new) | 4 | welcome, hero, quicklaunch, recent |
| library | 2 | header, body |
| notes | 2 | scratchpad, checklist |
| theme picker | 2 | grid, preview |
| notifications | 1 | history panel |
| home (classic) | 1 | the whole legacy dashboard, wrapped as one widget |
| activity | 1 | the floating pill that narrates what the launcher is doing |

### The four data channels, counted

The kernel offers props, widget state, sources/commands and services. What the
widgets in this build actually use:

| channel | widget files using it |
|---|---|
| props | 26 |
| surface context (`Local*Context.current`) | 36 |
| direct `koinInject()` | 12 |
| sources / commands | 2 (`notifications.history`, `home.new.progress`) |
| widget state | 2 (`notes.scratch`, `checklist`) |
| services | 1 provider, 1 consumer (the music pair, and nothing else) |

Surface context is the dominant channel by a wide margin, and it is the one channel
that does not survive the widget being moved. Direct `koinInject` is second, and it
is not a channel at all: it is the widget reaching around the kernel into the app's
DI graph, which works today only because every widget is compiled into the same
binary as the services it names.

The service SPI has exactly one provider and one consumer in the whole tree, and
they are the same feature: `home.new.music` provides `MusicPlayerService`,
`home.new.playback.mini` injects it and renders a muted placeholder when nothing
provides it. That placeholder is the only place in the UI where a missing
contributor is handled as a normal state rather than as a crash.

### The families are two different things

**Surface-bound panels** (about, profile, server details, theme picker, background
settings, library, both homes) are controls for one screen's data. They read that
screen's context, and out of it they are either meaningless or dead. Their
widget-ness buys arrangement inside their own surface and nothing else; the palette
will happily drop `bg.fx.blur` onto the top bar, where the stub context keeps it
from throwing and it does nothing.

**Placeable widgets** (clock, notes, checklist, music, playback mini, video, spacer,
the containers, the notification history) work anywhere. Every one of them takes its
data from props, widget state, a source, a service, or a Koin singleton with no
surface in it. This is the set a third party would actually be adding to, and it is
15 of 58.

The dividing line is exactly the context question from section 4. Nothing else
about the two groups differs: same annotation, same registry, same editor.

### Where the polish is, and where it is not

The generic widgets are the ones that got the design attention: the clock aligns its
tick to the second boundary and formats in the launcher's locale rather than the
machine's, the two volume bars share a gesture model with a `finally` so a cancelled
drag cannot leave the thumb enlarged, the notification history folds consecutive
identical entries and swipes them away, the video widget is click-gated so a placed
widget does not download on mount and drops the inline decoder while fullscreen.

Only three widgets participate in canvas scaling (`AdaptiveWidget`: clock, notes,
checklist). Every other widget renders at its natural size regardless of the
footprint it was given, so resizing most widgets on a canvas slot changes the box
and not the content.

Six kinds are `removable = false`: the five shell regions and `profile.signin`. They
are pinned by the default layout and hidden from the palette, so the arrangement
cannot be edited into a launcher with no navigation and no way to sign in.

### Two widgets are not widgets

`home.classic.content` wraps the entire legacy dashboard -- server grid, sync strip,
launch panel, roster fetch -- in a single `@Widget`. The comment says why: the
regions share too much local state to split, and the classic dashboard is slated for
removal, so it was widgetized as one block to exercise the slot machinery without
paying a refactor cost on code that is going away. It is a widget in registration
only; nothing about it is arrangeable.

`about.checkAgain` is declared inside `AboutSurface.kt` rather than in a file of its
own, which is worth noting only because it means the file list and the kind list do
not line up.

### The surface that never arrived

`widgets/wardrobe/WardrobeSurface.kt` is 649 lines in the widgets package with no
`@Widget` in it at all. Wardrobe is:

- a top-level navigation destination (`Screen.Wardrobe`, its own nav-rail entry,
  inserted into every existing user's rail by schema migration 4 -> 5);
- a breadcrumb entry;
- mounted by `AppLayout` as a plain composable call;
- absent from `EditorSurfaces`, absent from the layout graph, with no `SurfaceId`,
  no slots and no context.

So the newest full screen in the launcher is the one piece of it the widget system
does not touch. Pressing Ctrl+E on Wardrobe opens the editor on the shell surfaces
only, because `availableFor` finds no centre surface for that screen. Everything
inside it -- the pose chips, the skin grid, the cape section, the 3D preview -- is
hardcoded layout.

The same is true of every screen in section 6, but Wardrobe is the sharpest case: it
was built after the slot kernel shipped, in the widgets package, and still came out
as a monolith. That is the honest measure of how much friction the current surface
contract carries.

`EditorSurfaces` registers eight centre surfaces and six shell surfaces. The centre
list is what `mountedOn` can answer for: home classic, home new, library, about,
background settings, profile, server details, theme picker. Nothing else in the
router has a surface.

## 6. Screens that are not surfaces

`screens/` is 13.4k lines, the largest package in the module, and eight of its
screens are the ones section 5 counted as surfaces. Everything else is ordinary
Compose: a composable the router calls, laying itself out in code.

| screen | lines | surface? |
|---|---|---|
| Console (window + canvas + model) | ~2.9k | no |
| Pack detail (hero, tabs, settings window, versions) | ~2.5k | no |
| Content tab (scan, filters, mod browser) | ~2.0k | no |
| Settings (orchestrator + six sections) | ~1.4k | no |
| Browse + catalogue detail | ~1.0k | no |
| Server settings (state + screen) | ~0.9k | no |
| Files / worlds panes | ~0.8k | no |
| Wardrobe | 0.6k | no |
| Version picker | 0.5k | no |
| Migration | 0.3k | no |
| Dashboard / new home / library bridges | 0.5k | yes |

### What a bridge screen actually is

The three screens that DO mount a surface are thin by design:

```kotlin
CompositionLocalProvider(LocalHomeNewContext provides ctx) {
    SlotRenderer(SurfaceId("home.new"), SlotId("main"), ...)
}
```

`DashboardScreen` is 56 lines and `NewHomeScreen` is 60. `LibraryScreen` is 341,
and the difference is instructive: the extra 280 lines are a floating action
button, a context menu and a create-pack dialog, none of which are in the layout
graph. Even the screens that were migrated kept a layer of hardcoded UI above the
slots, because the slot kernel has no notion of an overlay, a FAB or a modal.

`NewHomeScreen` carries a comment worth keeping: the surface deliberately does NOT
wrap its slot in a `verticalScroll`, because an infinite-height constraint breaks
any Lazy-list widget the user might drop in. A stack of fixed-height widgets that
overflows the pane simply overflows, and the per-surface reset is the way out.

### Two more slot systems, neither of them the slot system

Settings and the pack-settings window both implement the same shape: an enum of
categories carrying an icon and an i18n accessor, a fixed-width rail, and a `when`
over the selection. `SettingsCategory` has six entries; `PackSettingsCategory` has
five and adds a `mirrorOnly` flag so a section can be absent for a local pack.

Neither is a `LayoutGraph`. Neither can be rearranged, extended or contributed to.
They are, structurally, exactly what the widget kernel does -- addressable regions
with content selected at runtime -- reimplemented twice with an enum in place of a
persisted graph. A third party wanting to add a settings page has nowhere to put
it.

### Three different ways to edit persisted state

| screen | model |
|---|---|
| Settings | `SettingsFormState`: one `mutableStateOf` per field, `mergeInto(current)` overlays them onto a freshly-read snapshot, every control calls `save()` |
| Pack settings window | the instance record IS the form; each control hands `pack.copy(...)` to `save`, which shows it as an overlay and persists after a 250ms settle, with a dispose hook on the app scope for the unwritten tail |
| Server settings | `ServerSettingsState`: a `@Stable` holder that loads the profile, owns the fields, and rebuilds it through the pure `assembleProfile` |

All three are defensible in isolation. Together they mean that "how does an editing
surface persist a change" has no answer in this codebase, which is the first
question a contributed settings page would ask.

The pack-settings window is the most carefully reasoned of the three, and its
comments are worth carrying into any general answer: a fully-controlled field
rendered straight off a durable record drops characters between the keystroke and
the record catching up; an edit that outlives the window has to be written on an
app scope rather than the composition's; and an edit something ELSE persists
(optional content goes through the launcher) needs a different flag than one the
window writes itself.

### The state-holder pattern is the real screen architecture

`PackDetailState`, `ContentTabState`, `ServerSettingsState`, `ModBrowserState`:
each is a `@Stable` class holding the screen's mutable state and its IO, built by a
`rememberXxxState(...)` that pulls its dependencies from Koin. Their KDoc says why
in almost identical words -- a composable that owns IO cannot be tested without a
composition, and a click lambda that runs a download swallows its own failure.

This is a good pattern, consistently applied, and it is completely disjoint from
the widget kernel. A widget cannot obtain a holder, and a holder cannot be
addressed by a slot. The two halves of the UI have separate answers to "where does
state live", and neither knows about the other.

### Following the record, not a copy of it

A rule the newer screens converge on: read the repository's flow rather than the
value the navigation entry carried. `PackDetailState.observe()` collects
`repo.observe()` for the screen's whole life; `ContentTabState.adopt` takes a
rewritten record and only re-reads what the rewrite changed; `rememberServerResolution`
resolves a server id against the cache first, then the live roster, and refuses to
read an empty roster as "the server is gone"; `ServerSettingsState` is keyed on the
server id rather than the roster entry so a re-resolve does not reload the form
over unsaved edits.

Every one of these fixes the same class of bug -- a screen showing a snapshot taken
when it opened -- and every one of them is implemented per screen. Widgets have the
same problem and no equivalent (their surface contexts ARE copies taken at
provide time).

### Puppet: a second command registry nobody calls that

`PuppetScreen` / `PuppetClick` / `PuppetField` / `PuppetToggle` are scattered
across effectively every screen, giving each interactive control a stable string
id (`settings.autoSyncAllPacks`, `packVersions.switch.<version>`,
`serverSettings.<assetDir>.resetClient`). The registry mirrors what is currently
composed, and an in-process HTTP driver actuates it.

That is a name-addressed action registry over the entire UI, and it is older and
far more complete than `Commands` (which holds three keys). The widget kernel's
command channel and the puppet channel are solving adjacent problems in two
unrelated systems: puppet ids are declared inline beside the control, command keys
are declared centrally and dispatched through a registry.

Widgets barely participate -- the wardrobe and the home hero declare puppet hooks;
the other 56 widgets declare none.

### Windows, and the three kinds of them

- `ConsoleWindow` is a real AWT window (`Window(...)`) with its own `NxTheme` call
  inside it, because it lives outside the shell's composition entirely.
- `PackSettingsWindow` and `VersionPickerWindow` are in-composition overlays: a
  scrim, an Esc handler, a card sized as a FRACTION of the app window with no dp
  cap. Both comment that this is deliberate -- a Popup would not inherit the theme
  and could outlive its host.
- `NewLocalPackDialog` uses `Popup` with a motion-driven unfold.

Three window idioms, and the middle one is the current house style.

### The console is a rendering engine living in a screen package

`console/` is ~1k lines of infrastructure with no console in it: `LogScrollState`
(pixel-primary scrolling with an explicit tail-follow intent), `HeightIndex` with a
Fenwick-tree implementation for variable-height wrapped lines, `LineLayoutCache`
(an LRU of `TextLayoutResult` keyed by the log entry itself), `LogSelection`
(document-coordinate selection, because virtualization means `SelectionContainer`
cannot span un-composed lines), and `LogCanvas`, which draws only the lines the
viewport covers.

It is the most reusable code in the UI layer, it is `internal` to a screen package,
and nothing else can use it.

`ConsoleWindow.kt` also still carries the whole previous rendering pipeline --
`ConsoleDoc`, `DocSpan`, `ConsoleRender`, `buildConsoleDoc`, `styleDoc` -- which the
live path replaced with the per-line `LineModels` pass. Its only remaining callers
are `ConsoleRenderTest` and `ConsolePerfBench`. That is a fossil surface: code kept
alive by its own tests.

### What the screens tell us about the modular work

Three things, and they are the substance of section 5's Wardrobe case:

1. A surface is only cheap when the screen is a shell around one slot. The moment a
   screen has an overlay, a modal, a tab bar or a rail, the kernel offers nothing
   and the author writes Compose.
2. The kernel has no vocabulary for anything above a slot: no dialog, no window, no
   tab, no rail, no floating action. Every screen invents its own.
3. The good patterns (state holders, following the record, one write per edit) are
   screen-side and unavailable to widgets; the good primitives (slots, props,
   sources, the generated prop editor) are widget-side and unavailable to screens.

## 7. The library: `nx-ui`, and the components that are not in it

Two layers claim to be shared UI. Only one of them is a library.

| | `nx-ui` | `client-ui/components` |
|---|---|---|
| lines | ~6.1k + 1.4k tests | 4.9k, no tests |
| project dependencies | none | client-core, client-launcher, client-auth, client-update, media |
| what it names | levels, roles, tones, motion roles | servers, packs, mods, sessions, updates |
| how a caller reaches it | `NxSurface(level = ...)`, `NxButton(style = ...)` | by calling the composable |

### `nx-ui` is a leaf on purpose, and pays for it explicitly

The module has no project dependencies at all, and two places show what that costs
and how it is paid:

- `ThemeManager` and `CustomizationManager` take a `publish: (Path, String) -> Unit`
  lambda instead of importing the launcher's atomic-write helper. The KDoc says why:
  copying the tmp-fsync-rename sequence in would put a durability primitive in two
  places, and two copies drift.
- `LocalBackdropPainter` is a `typealias` plus a composition local. `nx-ui` knows a
  surface can have a blurred backdrop; it does not know what a wallpaper is.
  `client-ui` provides the real painter.

That is the same seam shape the widget kernel uses (`widget-api` declares decorator
locals, `client-ui` fills them). Two modules, one pattern, and it is the pattern any
contributed module would have to fit.

### Three independent axes

1. **Palette** (`NxColors`, 40 fields): the fixed Dark/Light presets, nine
   `ThemePresets` (Cyberpunk, Vaporwave, Matrix, Blood Rain, Lotus Dark...), a
   user accent override, and a full Material You generator.
2. **Form and motion** (`StyleSpec`): corners, borders, glass-vs-flat, elevation,
   an animation multiplier, plus two component skins (`SwitchStyleSpec`,
   `BadgeStyleSpec`). Two instances ship: `CelestiaStyle` and `BrutStyle`.
3. **Customization** (`CustomizationSettings`): density scale, glass intensity,
   accent override, and the nav-rail selection style.

The split is stated as the point: a palette and a geometry are chosen
independently, so Celestia Dark can be rendered with Brut's square edges.

### The palette engine is real colour science, carefully fenced

`PaletteEngine` maps a `PaletteSpec` (seed, dark, one of nine tonal variants,
contrast level, optional second seed for two-tone) through materialkolor onto
`NxColors`. Two decisions in it are worth carrying into any future palette work:

- **Severity accents are built, not mapped.** The spec has `error` and nothing for
  success, warning or progress, so those three are constructed at fixed hues but
  borrow the tone AND the chroma the scheme gave error -- so they follow dark/light
  and contrast level, and stay as loud as the scheme is, no louder.
- **`tinted()` keeps the seed's hue and chroma but the base palette's TONE.** The
  comment records what happened without it: on a light ground the scheme puts
  `surface` and `background` at the same near-white tone, which collapsed the panel
  into the page and pulled the surface ladder from 12.2 L* down to 6. Accents are
  deliberately exempt, because changing them is the point of seeding, and pinning
  an accent's tone while its on-colour kept the scheme's put the pair at 3.87
  against a 4.5 floor.

`PaletteSeed` extracts a seed from a wallpaper bitmap or a decoded video frame, with
the subsample budget and the one-read-for-two-values note that keeps a 4K image from
OOMing.

### The surface system is the strongest thing in the library

`NxSurface(level)` takes a depth, not a colour and not an alpha. Under it,
`FrostSurface` composites an ordered layer list: `Backdrop` (blurred wallpaper) ->
`Fill` (glass coat, scales with the intensity knob) / `Body` (opaque tonal floor,
independent of the knob) -> `Wash` -> edge atoms -> `Texture` / `StateOverlay`.

The rules it encodes, each with a measurement behind it:

- a plane must survive its glass coming off (light theme, no wallpaper, intensity
  0), so `Body` is the floor and glass is a coat over it, never instead of it;
- there is no good alpha for a light surface over a wallpaper, so a light plane is
  opaque and a requested depth picks a rung of the tonal ladder instead
  (`lightPlaneFor`);
- the hairline is derived from the body's own luminance (`bevelHairline`), so it
  reads as a bevelled edge of the same material rather than a drawn frame;
- an opaque body skips the blur entirely, because sampling and blurring a wallpaper
  slice that is then fully covered is work thrown away;
- the presets deliberately dropped the inner bevel and the accent wash: both were
  fills getting lighter and darker in pure white and black, drawn as straight bands
  that miss a rounded corner, and the wash tinted every heavy plane with the primary
  so nested planes multiplied it.

### Motion is a vocabulary, not durations

Eight named roles (`tap`, `fade`, `colorShift`, `panelSlide`, `reveal`, `emphasis`,
`drift`, `sweep`) plus `ownRhythm` for a decorative period that genuinely belongs to
the effect. Every one resolves through `StyleSpec.animationDurationMs`, so
`animationMultiplier = 0` reaches all of them. `Motion.isStill` exists because a
loop cannot express stillness by collapsing its duration -- a 1ms `infiniteRepeatable`
restarts every frame, turning stillness into a strobe.

The KDoc names the disease it cured: the previous set was 90, 110, 120, 160, 170,
180, 200, 220, 250, 260, 300, 380, 500, 700, 950 -- rungs no eye could separate and
no rule to choose between -- and Brut declared `animationMultiplier = 0.0f` while
most of the interface kept animating at whatever each site had hardcoded.

### How much of the app actually uses the library

Measured across `client-ui`:

| | count |
|---|---|
| `NxButton` call sites | 71 |
| raw Material `Button` / `OutlinedButton` / `TextButton` call sites | 42 |
| files using `NxSurface` / `NxCard` | 26 |
| files using `glassSurfaceAlpha` | 29 |
| files reading `LocalStyle.current` | 29 |
| literal `RoundedCornerShape(<number>)` | 76 |
| hardcoded `Color(0x...)` literals | 26 |
| files reading `Motion.*` | 15 |
| files reading `Spacing.s*` | **0** |

The spacing scale is the sharpest case. It is documented in detail, its rungs were
derived from what the codebase already does, and `s14` is described as the inner
padding of every card, the inset of every row and the gap in every grid across
thirty-one files that arrived at it independently. Outside `nx-ui` itself (36 uses)
nothing reads it. Every one of those thirty-one files still writes `14.dp`.

The shape story is the same at smaller scale: 76 literal corner radii against 29
files that ask the style for one.

So the library is roughly half-adopted, and adopted unevenly: the button and the
surface have real uptake, the tokens beneath them mostly do not. A contributed
"theme module" would therefore reach the half that routes through `nx-ui` and miss
the half that does not -- which is exactly what "customization does not actually
work" describes from the outside.

### `client-ui/components` is where the domain and the design meet, with no rule

Twenty-four files. Some are thin, correct mappers -- `SourceBadge` and `ChannelChip`
map a domain enum onto `NxSourceBadge` / `NxMetaChip` and own nothing but the label
table. Others are 400-700 line screens-in-a-box: `JvmArgsBuilderDialog` (a seven-tab
JVM flag editor with a live preview), `SquareServerCard`, `UpdateDialog`,
`VideoPlayer`.

Three generations of style sit side by side here:

- `ConfirmCodeDialog` and `VideoMedia` are current: `NxSurface`, `NxButton`,
  `NxProgressBar`, tokens from the style.
- `RamSelector` and `JvmArgsBuilderDialog` are middle-generation: Material
  `OutlinedTextField` and `Slider` with hand-passed colours, own chips.
- `SquareServerCard` and `UpdateDialog` are older still: Material `Button` with
  explicit `ButtonDefaults.buttonColors`, own gradients, own scrims, `Color(0xFFEF4444)`
  inline for a favourite heart.

`SquareServerCard` also carries the April Fools chaos tracker inline -- acquire,
keep-onClick-in-sync, release, plus a pointer-blocking modifier for when the card
has been "escaped" by the event engine. That is the `Flexible` event layer reaching
into a component's body rather than wrapping it, and it is the only place that
happens.

### The event layer is a second plugin system nobody calls one

`nx-ui/flexible` has: an SPI (`FlexibleEvent` with `isActive`, `onSignal`,
`decorate`), a registry resolved at the app root (`FlexibleHost`), a signal bus with
an open key space (`FlexibleSignal.Named`), a composition-local seam
(`LocalFlexible`), an opt-in wrapper (`Flexible(id, kind)`), and a documented
zero-cost path when nothing is active.

It is, structurally, everything the widget kernel is missing at the shell level: a
way for something outside the base UI to wrap, replace or animate an existing
control, keyed by a stable id, with a lifecycle and a bus. It has exactly one
implementation (April Fools), and the ids it addresses are the same strings the
puppet layer uses -- which the KDoc states as deliberate: "one name addresses a
widget for both automation and events".

Three registries now address the same UI by string id: puppet (automation), flexible
(events), and the widget kernel (`Sources` / `Commands`). None of them knows about
the others.

## 8. The rest of the UI layer, and what it is made of

### The modules

The UI is already seven Gradle modules, not one, and the split is not arbitrary:

| module | lines | depends on the project? |
|---|---|---|
| `widget-model` | ~0.9k | nothing (Compose-free) |
| `widget-api` | ~1.2k | Compose + `widget-model` |
| `widget-processor` | ~0.3k | KSP + `widget-model` |
| `nx-ui` | ~6.1k + 1.4k tests | nothing |
| `client-i18n` | ~5.9k | nothing |
| `client-render3d` | ~1.7k + 1.2k tests | `nx-ui` (for `LocalStyle`) |
| `client-easter` | ~1.9k | Compose only |
| `client-media` | ~0.6k | `client-core` (transfer engine) |
| `client-tray` | ~0.2k | nothing (libtray) |
| `client-ui` | ~46k | everything |

Nine leaves and one trunk. `client-ui` is the only module that knows the launcher
exists, and it is ten times the size of the largest leaf.

Two leaves already carry a full SPI seam of their own:

- `client-easter` -- `AprilFoolsLifecycle` resolved by `ServiceLoader`, with a
  `NoOpAprilFools` that answers every method with the identity value. The KDoc is
  explicit that dropping the `META-INF/services` file is all it takes to ship a
  launcher with no seasonal behaviour, and that this state happened by accident
  once, which is why `AprilFoolsLoaderTest` exists.
- `client-ui/puppet` -- `PuppetServerLifecycle`, same shape, with the real Ktor
  implementation living in a source dir that only joins the compilation under
  `-PauraPuppetPort=N`. That one is a security boundary: production jars carry no
  implementation, so the control surface cannot be enabled by a system property.

So the launcher already has two working "an outside thing plugs in here" seams. Both
are SPI + a no-op fallback + a composition local. Neither is the widget kernel.

### The subsystems, briefly

- **Boot** (`Main`, `bootstrap`, `threshold`): the window is created before Koin so
  the threshold can render; boot runs on a daemon thread behind it. `ThresholdOverlay`
  is Tier-0 by construction -- no Koin, no NxTheme, no widget kernel -- with a
  Bayer-dither veil as a runtime SkSL shader that degrades to a plain alpha rect when
  the driver refuses to compile it.
- **Recovery** (`diag/UiRecoverySignal`, `Main.runShellWithRecovery`, `RecoverySurface`):
  `application {}` runs inside a restart loop with two crash windows (fast and long),
  a `LinkageError` shortcut that latches safe mode one crash sooner, and a
  window-exception handler that stashes render-thread crashes the loop would otherwise
  never see.
- **Notifications** (`notifications`, ~1.9k): a live stack (`NotificationCenter`,
  coalescing progress runs, capped groups) plus a durable archive
  (`NotificationArchiveStore`, disk-backed, progress ticks kept out of the write path)
  plus three drivers that bridge launch / install / pack-update into it. The drivers
  each keep a `lastPhase` map because every source republishes its whole map on any
  change.
- **Activity** (`activity`, ~1.3k): the newer surface -- one floating pill that
  narrates the launcher's own work, plus a selection registry the current view
  publishes into. It is a widget (`appshell.activity.pill`), it reads a `client-core`
  registry, and it is the one place where "the launcher is doing something" and "the
  user has selected something" share an object.
- **Background** (`background`, ~1.2k): the wallpaper. Static images decode through
  Skia with a display-height disk cache; video and animated images play through
  Skinema with a one-time transcode to display height. `BackdropState` publishes the
  recipe so a frosted surface can redraw a blurred slice of the same image with the
  same transform.
- **Console** (`utils/GameConsoleService`, ~0.5k): fully decoupled from the UI
  thread -- every mutation is a channel enqueue, one drainer owns the buffer and the
  session file, and the UI reads a coalesced immutable snapshot. A modded start
  floods 5000+ lines in two seconds; this is the shape that survives it.
- **Identity** (`identity`, ~0.6k): the skin library (content-hash dedup, index
  quarantine-and-rebuild on corruption), the skin manager (LRU + disk cache,
  path-traversal-safe cache names), the clan-role probe that fails open, and the
  default-skin extractor that reads Mojang's textures out of the user's own
  provisioned client jar rather than bundling them.
- **Debug** (`debug`, ~0.4k): the F9 overlay. Gated on `ReleaseChannel.classify` --
  a release build cannot turn it on -- and its decorators mount only while a facet
  needs them, so a dev build with the overlay off runs the release tree exactly.

### What was read, and what was not

Everything in this document was read end to end at `63b6f097`, with three
deliberate exceptions, all of them data rather than structure:

- `client-i18n`'s three locale objects (`EnglishStrings`, `RussianStrings`,
  `GermanStrings`, ~1.4k lines each) -- the `AppStrings` interface, `AppLocale`,
  `LocaleProvider` and `Plurals` were read; the value tables were not.
- `client-media/YtDlpService` (332 lines).
- Test sources, except where a test is the only remaining caller of production code
  (noted in section 6).

### What this map says about the work ahead

Four things, in the order they block each other:

1. **The kernel's reach is the surface contract, and the surface contract is a
   composition local.** 36 of 54 widget files read one; a widget that reads one is
   pinned to its surface. Nothing else about the kernel limits portability.
2. **The launcher already knows how to let something plug in.** Two SPI seams ship
   today (April Fools, puppet), both with a no-op fallback and a composition local,
   and `nx-ui` already pays the leaf-module cost explicitly (`publish` lambdas, the
   backdrop-painter typealias). The pattern for a third-party contribution is not
   missing -- it is unused by the widget kernel.
3. **The design system is half-adopted, and the unadopted half is the tokens.** 0
   files read the spacing scale, 76 literal corner radii against 29 style readers.
   A contributed theme reaches what routes through `nx-ui` and nothing else, which
   is what "customization does not actually work" looks like from outside.
4. **Above a slot there is no vocabulary at all** -- no dialog, no window, no tab,
   no rail, no floating layer. That is why Wardrobe came out as a monolith, why
   Settings and the pack-settings window each grew their own rail-and-`when`, and
   why the three window idioms exist. Any answer to "how does a third party add a
   screen" has to name these before it can name a plugin format.

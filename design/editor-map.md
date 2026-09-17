# The editor: what it is, what was decided, what is open

Written 2026-09-17, after a design session that ran from "collect everything about the
editor" to a landed change. Figures are measured from the tree at `8ef625b1`, not
carried forward from an earlier document. Where a number is quoted from somewhere else
it says so.

This exists because decisions in this area kept evaporating: `experimental/ui-layer-map.md`
was measured in August and every count in it had drifted by a month, and comments in the
code went on describing an auto-packer that had been replaced. A number here is true on
the date at the top and nowhere else. Open the code.

---

## 1. The decisions

### A surface is not a widget

A surface is a **point of unfolding**: it is entered and left, and entering it loads its
content from nothing. A widget is a placed node with identity, props and a position, and
it lives as long as its parent's composition.

`SurfaceLayout.slots` and `WidgetInstance.children` are both `Map<SlotId, SlotContent>`,
and that near-identity is what makes the question worth asking at all. It is a coincidence
of capability, not of kind: both hold slots, and only one has a lifecycle boundary.

**A single tree was considered and rejected.** The asymmetry in `SlotPath` (the first hop
by name, the rest by `instanceId`) is not a debt. It records two different relationships:
a name addresses a mount point, an id addresses containment. `mutate` and `mutateNested`
being two functions follows from that and is correct.

A surface **can** be a component of the widget system without being a widget: the system
owns it, enumerates it, and could let a pack contribute one.

### How something opens is a property of the opening

Not of the surface. The same pack-settings surface should be able to open as a window on a
wide display and as a full screen on a narrow one, and a surface that declared "I am a
modal" could never do that. The descriptor carries a **default**; whatever opens it may
override.

Two modes, and the proof they are both needed is already in the tree: `EditorSurfaceHost`
needed one for its panels and another for its pill, and settled it with a hardcoded 65/265
inset plus a comment explaining why the pill cannot use it.

- **over the window**
- **over the centre** (what `appshell.overlay` already picked, with its reason written down:
  nothing docked there should ride over the rails)

A separate axis, by owner:

- a **lane** belongs to the shell, is mounted always, survives navigation
- a **modal** belongs to the back stack, is entered and left with its entry

Floating things get a back-stack entry. This was already solved once by hand:
`Screen.PackDetail.openSettings` is overlay state smuggled into a destination's parameters
so that Back returns into the settings the user left. A general model stops that being
per-case.

The transition belongs in the same place as the mode, for the same reason: a transition is
presentation.

### Cycles are caught by a mount stack, not by an edge in the model

A widget does not **contain** a surface, it **opens** one. The relationship is a mount, so
the check is a stack and not a graph walk: a set of currently mounted `SurfaceId` in a
composition local, and a mount that finds itself already in the set refuses and draws a
placeholder. Composition is depth first, so it refuses before the stack overflows.

This gets the semantics right for free. Two libraries side by side are different branches
and are allowed. A library inside itself is one path and is refused.

Today the only thing preventing the loop is that the five shell regions are
`removable = false` and so absent from the palette. The existing guard in
`LayoutGraph.moveWidget` cannot see it: it checks `to.nested` for the moved id, and a
cross-surface edge is not in the model at all, it only exists inside a composable body.
`UiRecoverySignal.isStructural` classifies `StackOverflowError` as an ordinary crash, so
the launcher would retry the recursion twice before latching safe mode.

### A surface descriptor is four fields

Three of them already exist inside `EditorSurfaceSpec`, hardcoded:

- id (already the map key)
- name and icon for the picker
- routed or mounted (today `mountedOn != null`)
- the context it provides (today `stub`)

So "surfaces as data" is not a new entity, it is moving `EditorSurfaceSpec` out of an
object and into a file. The hard blocker is five lines: `availableFor` builds its
candidates from two compiled-in lists and only then intersects with the graph, so a
surface that exists in the graph and not in the registry is never offered, with no error
and no log line.

### Cardinality replaces `removable`

`removable = false` is a crude proxy for two different things. Removal already works
(the chrome offers a force-remove with a confirm), so the flag is a speed bump, not a
lock. What actually does not work is getting the widget back, because the palette filters
by the same flag.

The axes are **how many instances may exist** and **can it be restored**. A `One`/`Many`
cardinality in the descriptor answers the first; showing everything in the palette, greyed
when a `One` is already placed, answers the second. `removable` then has no job.

A shell region is `One` because it **hosts a mount point**, not because it is precious.

### Modularity is not being taken

The parked plan in `experimental/` is a different project from this one. It delivers "the
app assembles itself from modules": a locale as a module, the theme as an addon, swappable
config storage, the shell and router as modules. It does not deliver "a widget can make a
screen". The overlap with what this area wants is close to nothing.

Evidence that it was never the prerequisite it claimed: the module loader shipped anyway.
`widget-loader`, `CompositeWidgetRegistry`, a worked example and a public docs page exist,
while C1 (KSP runs only in `client-ui`), the Koin stratification and the three-tier core
did not happen.

The analogy the user reached for is microservices, and it holds on every point: separately
deployable units, an isolation boundary (a classloader instead of a process), versioned
contracts, string-keyed channels rather than the neighbour's types, a third artifact for a
shared contract, discovery. Which means it carries the cost profile too. Microservices pay
off at organisational scale, buying the ability to deploy without talking to each other.
The trigger is therefore not the size of the code, it is **a contributor who cannot be
reached at the moment of the edit**. That has fired exactly once, for widget packs, and
the boundary is already drawn there.

If it is ever wanted, the cheap path is to repeat the pattern that already works, one
contribution type at a time: an interface, a `ServiceLoader`, a composite where order is
precedence. `AppStrings` being a closed interface is the same shape of problem and takes
the same shape of answer.

### The editor is the test of the vocabulary

It is the only thing in the tree that already needs both opening modes, is scoped to one
surface out of fourteen, and raises the question of whether a mode survives navigation.
The vocabulary can be tried on it **with its content still hardcoded**, which is the only
cheap place to find out whether the model is right, and it does not need C1 or C2 to do it.

A rule that falls out immediately: edit mode should survive navigation exactly when the
surface being edited survives it. Today it is dropped unconditionally
(`remember(availableSurfaces)`), which is wrong for a shell surface present on every screen.

### Notifications are three things

- a **store**, already exposed as `Sources.Notifications` with two commands. Nothing to do.
- a **history**, already a widget, already reading through `rememberSource`. The example.
- a **toast stack**, which is not a widget at all: it is called straight from
  `AppShell.kt` and is not in the graph.

The stack is a **lane**: shell owned, survives navigation, does not block. It becomes one
placeable widget in the lane slot that draws N cards from the source. Removing it then
means "no in-app toasts", not "no notifications", because the archive still records them
and libnotify is a separate path.

### The gap a notification card actually found

Not "a surface over a surface". A card is not a point of unfolding: a surface holds a
fixed named set of slots and a card is one of N from a stream, and N simultaneous toasts
would be N mounts of one name, which the mount guard would have to special-case. When a
model needs an exception for the common path, the classification is wrong.

The real hole is that **there is no such thing as an item template**, and it is four places
wide: pack cards in `library.body`, rows in `notifications.history`, rows in the palette,
the server grid. Solving it inside the toast stack would produce a fifth mechanism.

What a card *can* be is the **trigger** for an unfolding: the update toast opens the update
panel. That needs nothing new, it is a command and an ordinary mount.

The likely eventual shape for an item: a widget rendered outside the graph, with props
bound from the item's data. The cost to name honestly is that such a widget has no
`instanceId` and therefore no persistent state, because `widget-state.json` is keyed by
one and its collector retains only ids reachable from the graph.

### Animation

**The graph describes states, not transitions.** It says where things are and is silent on
how they got there. So animation is three separate places and they must not be confused:

1. **inside a widget** -- the widget's own business, through the `Motion` roles. Works today.
2. **of the structure** -- a widget added, removed, reflowed. Today `LocalSlotMotionMs`, which
   only the editor provides. Becomes real when a pack can add a widget at runtime.
3. **of the unfolding** -- a screen change, a modal opening, a lane sliding in. This one
   belongs with the opening mode, as another field beside it.

An outstanding debt that touches this: there is no "motion off" setting at all, while four
design-system files describe behaviour for when it is off. That is #465 and #644, with #551
its only visible instance.

The rule engine is **not** this and must not be folded into it.

### Five slot orientations were two ideas

Column and Row differ by one axis, and could not share a body only because
`Modifier.weight` is scope typed, which is a fact about Compose and not about layout. Grid
is the same flow with a line length and equal cells. Canvas and the cube grid are the same
placement with and without quantisation.

Two is the floor, not laziness. A flow **derives** a position from the sequence; a placement
**stores** it. Expressing a flow as placement is possible and means writing every sibling's
position into the graph on every insert, which is the auto-packer that was already tried
and removed.

---

## 2. What was built

Five commits, `44a94dd8` through `8ef625b1`.

### The model

```
SlotContent(widgets, flow: FlowSpec?, grid: Int)
  FlowSpec(direction, wrap, uniform)      // null flow = placement mode
WidgetInstance(..., placement: Placement?)
  Placement(anchor, x, y, width, height, z, weight)
```

`grid` is the **unit** the placement is measured in: 0 is one dp, N is one cell of an
N-column lattice whose cell size derives from the measured width. A cell address therefore
survives a window resize, which an absolute dp offset does not.

The `anchor` is what fixes something that had shipped: every free placement counted from
the top left, so an arrangement made wide was clipped narrow and one made narrow left dead
margin wide.

Nothing on the wire is an enum any more. `direction` and `anchor` are strings parsed with a
fallback, the way `SurfaceSpec.fill` and `shape.kind` already were. `SlotOrientation` was
the format's only exception to the rule it stated about itself, and it is why a sentinel, a
lenient codec and a write-back test existed.

### The migration is two ladders

This is the part worth remembering. `Migrations` takes a decoded `LayoutGraph`, so it can
change what is **inside** one and never its **shape**: the shared Json has
`ignoreUnknownKeys`, so a field the model no longer declares is gone before that ladder
runs. A structural migration written there would have compiled, run, reported success, and
returned every Row, Grid and Canvas in every saved file as a plain column.

So `JsonMigrations` runs **before the decoder**, on the raw object, keyed on the same
`schema_version`, on both paths that read a graph from outside the build: the layout file
and a preset.

Schema is `10`, and `LAYOUT_SCHEMA` now lives in `widget-model` beside the format it
versions. `DefaultLayout.load` refuses a bundled resource stamped with anything else, which
is what let the resource sit two versions behind unnoticed.

### Verification that exists

- `JsonMigrationsTest` -- each of the five old shapes, malformed input, a widget carrying all
  three old fields, nesting, plus two fixtures: the previous release's actual bundle and a
  sampler carrying one of every shape including two a hand edit produces.
- `LayoutUpgradeTest` -- a schema 9 file on disk through the real `LayoutGraphRepository`
  path, and what the write-back leaves behind.
- `SlotRendererGeometryTest` -- 14 off-screen renders read as coordinates: anchors, the
  inward offset from an end corner, layer over list order, cell addresses, spans, clamps.
- `PlacementClampTest` -- the clamp as a property (what stays inside is never less than the
  margin) rather than a number per anchor.

Two lessons from writing those, both worth keeping:

- A test comparing the migrated bundle to the shipped bundle proves the two **agree**, not
  that either is right, because a separate pass produced the shipped one. The invariant
  test derives its expectations from the old bytes at run time instead.
- A render probe that does not pump the frame clock asserts nothing: `entryAlpha` stays near
  zero, the element is not drawn at all, and the assertion passes on an empty screen. Two
  probes were green for that reason before a red-check caught them.

---

## 3. Measured, 2026-09-17

| | |
|---|---|
| widget kinds compiled in | 67 (66 under `ui/widgets`, 1 in `ui/activity`), plus 1 in `examples/` |
| kinds pinned to one surface | 42 of 67, counted through the helpers as well as the widget bodies |
| portable kinds | 25 of 67. An earlier count said 41, and it was wrong: it read each widget file for a context local and missed the 15 background widgets, which reach `LocalBgSettingsContext` through `BgSlider` and `BgPicker` rather than naming it |
| surfaces | 14, all in the bundled default: 8 centre, 6 shell |
| kinds placed on a fresh install | 47 of 67. 20 exist only in the palette |
| destinations assembled from widgets | 7 of 14 |
| layout schema | 10 |
| editor package | about 5.4k lines across 22 files |
| widget kernel | about 3k lines of main across 4 modules |
| raw `.dp` literals in `editor/` | 237 across 34 distinct values, 0 spacing-token readers |
| tests in the CI matrix | 2901 across 23 module suites |

---

## 4. Open

**Not done, in the order they were agreed.**

1. **The prune gate.** `AppShell` prunes widgets whose kind left the registry, gated on a
   schema bump. Its own comment calls it a trap armed the moment the registry has a second
   source. The loader shipped, so the second source exists now. A release that bumps the
   schema while a pack fails to load deletes that pack's widgets permanently, silently, and
   it looks exactly like the feature working.
2. **The mount guard.** Cheap, and the safety net for everything after it.
3. **Seeding on cross-slot move.** Partly done: `moveWidget` seeds a placement now. The
   palette drop into a lattice still does not carry the release point into a cell.
4. **The surface descriptor into data.** This is "a widget can make a screen".
5. **Cardinality in the widget descriptor.** Unblocks the gallery, retires `removable`.
6. **The gallery.** Grouping by id prefix needs no new metadata, the ids are already
   namespaced. Previews are possible for the 41 portable kinds today, and a build-time bake
   through the existing render probes covers nearly all of them. Android itself never
   snapshots a live widget: it ships a picture or a separate preview layout.
7. **A stack-like arrangement and z in a flow.** May not need a new orientation at all, given
   the anchor: see the open question below.
8. **The cell grid properly**, after widgets can answer a footprint.
9. **Tombstones in the reconciler and a rewritten reset.** `mergeMissingSlots` is safe only
   because the editor cannot create or delete a slot, and it says so. The moment a user can,
   the merge starts resurrecting what they deleted. `resetSurface` with no bundled default
   **deletes** a surface, and `resetAll` removes every user-made one.

**Independent of the order:** the surface level is editable today only by typing `raised`
into a free-text field, and the same is true of `shape.kind` and `border.color`. Three
fields where a closed vocabulary is hiding behind free text.

**Deliberately not changed during a structural refactor**, both still open questions:

- the lattice snaps to the nearest free cell and never moves a neighbour. Android reflows.
  The May decision that set this was not deliberate and the collision did not work anyway,
  so Pixel-style reflow is open.
- a flow sizes its cross axis by what the child asks for, so a wrapped line does not equalise
  height.

**The prerequisite everything Android-shaped waits on:** 3 of 67 widgets respond to the size
they are given through the `AdaptiveWidget` contract (the clock, the notes pad, the
checklist), plus 11 of the 12 players doing it by hand with their own `BoxWithConstraints`,
which steps a discrete control ladder by width and never touches type size. `AdaptiveWidget`
computes a scale only when both axes are bounded, so inside a flow slot it is exactly 1 and
the mechanism may as well not be there.
A perfect cell grid would place boxes whose contents do not change. The
footprint contract should be locked on the clock first (3x3 default, then 4x5, content
adapts), which is a decision recorded in May and never acted on.

**What the editor looks like, which is separate from whether it works:**

- the palette is a debug view: 67 entries, flat alphabetical, each showing its raw kind id,
  running off the bottom of the screen
- the pill is eleven chips of equal weight with a text hint crammed at the end, though
  Reset and Preview are different classes of action
- four border languages on screen at once: the dashed empty slot, the widget outline, the
  selected-slot accent and the window frame

---

## 5. Notes that cost something to learn

**Do not build while the launcher runs.** It rewrites `build/classes` under the live JVM and
produces `NoClassDefFoundError` that is indistinguishable from a regression. It happened
twice in two days; the second time it took down the shell, then the recovery loop, then
safe mode, so three layers of a crash-recovery system looked broken when none of them was.
The lock at `~/.local/share/nexira/.lock.pid` answers in one second.

**`BoxWithConstraints`, not a size read back through state**, wherever geometry positions
something. A width that arrives after the first layout means the first frame draws every
widget at the origin at full size. In the app that is one wrong frame; in a screenshot or a
probe it is the whole answer. The clamp that keeps a dragged widget reachable is the
exception, because nobody is dragging on the frame a slot appears.

**A clamp has to know which corner the offset counts from.** Written for the top left it is
correct for one anchor in nine, and lets a centred one travel a whole slot width out of
reach. A real layout file recorded `bottomCenter` at an offset of 2105 on a slot around two
thousand wide.

**Clamp the drawing, never the record.** The lattice and free placement both do this now, so
lowering a column count or narrowing a window is reversible and the arrangement comes back
when there is room for it again.

# Visual work: where, and what exactly

Collected from three audits of our own surfaces and two readings of the Modrinth
launcher's source. Ordered inside each section by what a person actually notices.

Organised by the five kinds of problem, because that is what these turned out to
be. Not by principle, and not by token discipline: a spacing scale that is a
plain alias for a dp literal changes nothing that renders, and counting literals
produced volume rather than findings.

Every item names a file and a line so it can be picked up without re-deriving it.

---

## 1. Components that claim more than they do

- **The editor's cube-slot resize does not exist.** `EditableWidgetChrome.kt:441`
  says "a right-drag in a cube slot resizes by cells". The secondary-button
  handler at `:267-276` only opens the context menu, and `cubeResizeSpan`
  (`CanvasGeometry.kt:102-117`) has no production call site -- only
  `CanvasGeometryTest`. Either build the gesture or delete the helper and the
  comment.
- **The edit-mode vignette draws no tint.** `EditorSurfaceHost.kt:489-491`
  describes "a soft inner primary tint"; `:977-981` draws a 1.5dp square-cornered
  border at 35% and nothing else.
- **Settings promises a saved banner it never shows.** `SettingsScreen.kt:22-24`
  names one as part of this composable's job. No such banner exists in the file.
- **The Launch plane promises a heap control it does not draw.**
  `AdvancedSection.kt:56-59` says the plane is "what a launch is handed (heap,
  JVM args, the version string)". It holds two toggles and the mimic-version
  pair. `RamSelector` is per-instance only.
- **`NxDraggable` has zero call sites.** `NxDraggable.kt:37` already implements
  the grab / grabbing cursor state machine, and its own KDoc says "no screen sets
  the cursor by hand". Four hand-rolled drag gestures bypass it:
  `EditableWidgetChrome.kt:283-367`, `DragAndDrop.kt:232-258`,
  `WidgetPalettePanel.kt:125-130`, `SurfacePropertiesPanel.kt:108-113`.

## 2. Bugs that are visual

Items struck through were fixed on 2026-08-19; they are kept because the reasoning
is what stops them coming back.


- **The depth ladder inverts between themes.** `NxCard` defaults to
  `SurfaceContainer` and `NxSection` to `SurfaceContainerHigh`
  (`NxSurface.kt:36-37`). On dark that puts the card at `0xFF222222` inside a
  plane at `0xFF2A2A2A` -- a recess. On light it is `0xFFE4E6EC` inside
  `0xFFDADCE4` -- a lift. Same composable, opposite reading. Most visible in
  Console, where four rule cards sit inside a section (`ConsoleSection.kt:176`,
  `:198`, `:226`, `:254`).
- **White on a user-settable accent.** `NxSwitch.kt:73` always paints the thumb
  `Color.White`. Against the moon-blue accent that is about 2.1:1; an unchecked
  switch on the light palette is about 1.5:1. Settings alone draws sixteen of
  them. Same class of problem in the drag ghost (`PaletteItem.kt:180-214`) and
  `NxButton.kt:78`.
- DONE. **One scroll position shared by six settings categories.**
  `SettingsScreen.kt:85` remembers the state against the Column, which survives a
  category change. Scroll to the bottom of Console, click Advanced, land
  part-way down it.
- DONE. **The last section is clipped flush.** The scroll Column
  (`SettingsScreen.kt:81-86`) has no bottom padding and no arrangement, so the
  final plane's bevel hairline is cut mid-stroke on every category.
- DONE. **Editor panels can be dragged off-screen and not brought back.** The offsets
  are unclamped and unpersisted `remember`s (`WidgetPalettePanel.kt:73`,
  `SurfacePropertiesPanel.kt:90`); the only recovery is toggling the panel shut
  and open.
- DONE. **`Instant.parse` runs unguarded inside composition.**
  `NetworkSection.kt:61`. A malformed stored timestamp throws out of the render
  pass; there is no fallback text and no `runCatching`.
- DONE. **Diagnostics fails silently.** `DiagnosticsSection.kt:153-160` and `:192-197`
  swallow failure with `runCatching { }.getOrNull()` and draw nothing. The button
  re-enables and the user sees a control that visibly did nothing.

## 3. Rudiments

- Back arrows drawn inside a surface while the window frame already carries one
  (`TopBarBreadcrumbWidget.kt:40`). Removed from the theme picker; still present
  in `ServerSettingsScreen.kt:98` and `ServerDetailsSurface.kt:118`, where they
  are place-based and acceptable.
- DONE (the comment now says the gesture does not exist). `cubeResizeSpan` -- see section 1.
- Two zip readers in one tree: `ZipUtils` on commons-compress,
  `MrpackInstaller` on `java.util.zip`. The latter cannot take a channel, so it
  has to move if partial archive reads ever land.

## 4. Motion that is missing where it is wanted

- **Lists do not animate at all.** No item placement animation anywhere.
- **The right panel hard-swaps.** So does Modrinth's, by a `:empty` CSS selector
  and nothing else -- but `AnimatedContent` costs us nothing, so this is a place
  to beat them for free.
- **Every settings reveal is a bare `if`.** `AdvancedSection.kt:105`, `:164`,
  `ConsoleSection.kt:108`, `:126`, `:143`, `AppearanceSection.kt:181`,
  `DiagnosticsSection.kt:111`. Content pops in and shoves the plane down.
  `Motion.reveal` (`Motion.kt:67`) is the named role for exactly this and has no
  caller in the package.
- **Nothing highlights on drag-over.** Empty slots take no drag state
  (`EmptySlotPlaceholder.kt:49-53`) and breathe on a 1600ms loop whether or not
  they are the target; slot chrome strokes only when already selected
  (`SlotLayoutChrome.kt:94-106`); canvas and cube slots get no indicator at all
  (`EditableWidgetChrome.kt:427`).
- PARTLY DONE (the offset is fixed; the 80dp height still under-reports a tall
  canvas, which needs the renderer to report the slot itself). **An empty canvas slot's drop target is only its top 80dp**, and offset 6dp
  low because the padding is applied before `onGloballyPositioned`
  (`EmptySlotPlaceholder.kt:71-77`). Dropping lower hit-tests to null and is
  discarded in silence (`PaletteItem.kt:103`).

## 5. Styles have more freedom than the components survive

- **Brut's headline difference never arrives.** `cardBorder = 1.dp`
  (`StyleSpec.kt:162`) is read by exactly one composable in the tree,
  `NotificationCard.kt:114`. `cardSurface = Flat` (`:163`) is read only by an
  AprilFools flag. Settings consumes neither, so under Brut it is not flat and
  has no hard borders.
- **Settings stays a glass page under Brut.** `SettingsScreen.kt:71-74` keeps
  `glass = true`, so a wallpaper blur is still requested.
- **Three editor panels animate under a style that says not to.**
  `WidgetPalettePanel.kt:95-96`, `WidgetPropPanel.kt:103-104`,
  `SurfacePropertiesPanel.kt:82-83` use raw `spring()`, which is not
  duration-based and never reads `animationDurationMs`. Under Brut
  (`animationMultiplier = 0`) they still slide and fade. The pill and the
  vignette route through `Motion` correctly, so the editor's motion is half
  tokenised and the visible half is the wrong one.
- **A panel with no edge.** Under Brut `panelElevation` is 0, and the editor
  hand-rolls `shadow + clip + background` instead of `NxSurface(Floating)` --
  which would give it a luminance-derived bevel hairline that survives elevation
  going to zero. `NxContextMenu` and `NxPopoverPanel` already go through
  `NxSurface` and stay legible; the editor panels are the outliers.
- **The context menu and the plane it opens over share a tonal role.** Both
  resolve to `SurfaceContainerHigh` (`NxSurface.kt:37`, `NxContextMenu.kt:111`),
  no cast shadow is requested, and separation is one hairline at 8% delta.
- Hardcoded palette outside the theme: `SunOrange` / `MoonBlue`
  (`AppearanceSection.kt:210-211`), and a `Color.White` border in
  `CustomizationHelpers.kt:106` that is invisible on a light palette.

---

## Worth taking from the Modrinth launcher

Costed for a Compose Desktop port. Their design is strong and their logic is
not; these are the parts that survive the toolkit change.

**Free, and worth it**

- **A dialog that opens from the control that summoned it.** Seed the entrance
  offset one-sixteenth of the way from centre toward the press position, then
  animate to centre over ~200ms. One `Animatable<Offset>`.
- **A scrim tinted by intent** -- one hue for ordinary, one for warning, one for
  destructive -- instead of flat black. Does most of the work a backdrop blur
  would, at no cost.
- **A four-pixel intent threshold** before a click outside counts as a dismissal,
  so dragging the window does not close the dialog.
- **Never blank a populated list.** Set the loading state only when there is
  nothing on screen, and version-guard responses so a slow reply cannot overwrite
  a fast one. Buys more perceived speed than any animation here.
- **Prefetch on hover.** Half a second resting on a row starts fetching what the
  click will need.
- **An always-visible dashed drop tile** that says what it accepts at rest and
  colours when a compatible file is over it. Their content list has an invisible
  whole-window target with no feedback at all; their skins page has the tile.
  Copy the tile.
- **Optimistic rows.** Push a placeholder into the list before the download
  starts, then let the dependency plan add its own rows as they resolve. Answers
  "what is it doing" better than a progress bar.
- **A form that does not wait.** Close it, navigate to the thing being built, and
  make the work a background job with named phases and a progress unit that
  changes per phase -- bytes while downloading, a count while processing, a
  percentage otherwise. This is an information model, not a rendering trick, so
  it ports unchanged.
- **Rubber-band selection highlight**: animate the leading and trailing edge with
  different delays so the pill stretches across and then contracts, rather than
  sliding as a rectangle.
- **Icons that inspect themselves**: probe the four corners for transparency and
  pad if all are clear; drop to nearest-neighbour below 32px so 16x16 mod icons
  stay crisp; make the corner radius proportional to size; tint a missing icon by
  hashing its id so every unknown thing has its own stable colour.

**Real work, decide case by case**

- Middle-truncating a filename so the identifying tail survives. Compose has no
  middle-ellipsis mode; measure and splice.
- Animating a backdrop blur. Their own code treats it as costly and puts it
  behind a user setting. Take the tinted scrim, leave the blur.

**Not worth copying**

- One row shape for every content type. A resource pack row says nothing about
  being textures: no preview, no thumbnail, no different card. Diverge here.
- One flag bag holding preferences, panel collapse memory, one-shot dismissals
  and unfinished-feature gates as peers. Nothing distinguishes the kinds, so
  "reset preferences" would also forget which warnings were dismissed.

---

## 6. What a wide window does to the layout

Measured at 1920 and 2560 logical pixels, which is what the maintainer's screen
actually reports: nothing in the repo sets a UI scale, so on this desktop 2560
physical is 2560 logical.

**The geometry that causes all of it.** The shell frame is a Row of three: a
65dp rail, a weighted centre, a 265dp rail. Both rails are fixed, so the centre
is the only child that grows and **every pixel a wider window gains lands
there** -- 1586dp of content at 1920, 2226dp at 2560.

**There is no breakpoint above 1100dp anywhere in the app.** Every width
comparison in the tree is a floor protecting the 960dp minimum window, and the
widest branch of each is already entered by about 1100dp. The 2K layout is not a
layout for a wide screen; it is the same layout with one column stretched.

What that produces, as proportions rather than opinions:

| | 1920 | 2560 |
|---|---|---|
| pack row (Library, Browse) | 1538x132, 11.7:1 | 2178x132, **16.5:1** |
| Play button, classic Home | 30:1 | **43:1** |
| Browse search field | 40:1 | **52:1** |
| pack name field, settings | 1399dp | 1690dp |
| notes field, settings | | 1690x72, **23:1** |

The pack row draws the pack's banner behind it with `ContentScale.Crop`, so a
16:9 image inside a 16.5:1 frame is a horizontal slice about six percent of its
own height. The card stops showing which pack it is.

Two more, both of which use less space the more they are given: the recent-packs
row on the new Home is five tiles of a fixed 180dp, so 940dp of a 2178dp row,
and the tile count is a stored prop rather than anything derived from the
measured width. And the free canvas positions widgets by absolute offset with no
clamp at render, so an arrangement made at 2560 is clipped at 1920 and one made
at 1920 leaves 640dp of dead margin at 2560. That has shipped.

**The correction worth keeping.** Line length is not the problem on these
surfaces -- there is almost no body copy, and what exists is either capped or
single-line with an ellipsis. The defect is object proportion: things whose
height is a constant while their width tracks the window. The one real
line-length case is the changelog in the version window, at roughly 155
characters a line at FHD and 250 at 2K.

**What already does it right**, and is the pattern to copy: the server grid uses
`GridCells.Adaptive(minSize = 200.dp)` and answers a wider window with more
columns -- seven at FHD, ten at 2K -- instead of wider cells. It is the only
surface in the set that uses the width rather than being stretched by it.

**And the reason none of this was caught.** The render tests already capture
these screens at 2560x1440. Their only assertion is that more than ten percent of
the frame is not the backdrop, so they cannot fail on a layout that has come
apart -- they answer "did it draw", never "did it draw well". Every finding in
this section was found by reading or by looking at a PNG by hand.

### The order I would take these in

1. A content-width ceiling in `nx-ui`, applied at the roots of the new Home,
   Library and Browse. One change bounds the Play button, the hero, the welcome
   banner, the search field and everything anyone writes with `fillMaxWidth`
   tomorrow, and turns the surplus into symmetric margin over the wallpaper --
   which is what the transparent centre region exists to show. Landed for Browse
   on 2026-08-19 as `Dimens.contentMaxWidth`; the other two are a line each.
2. A real adaptive grid for Library and Browse, after the server grid. This is
   also what fixes the banner crop, since a card near 3:1 shows artwork.
3. A width ceiling on the pack settings panel, whose KDoc currently records
   "no dp caps" as a decision.

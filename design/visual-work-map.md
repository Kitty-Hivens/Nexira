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
- **One scroll position shared by six settings categories.**
  `SettingsScreen.kt:85` remembers the state against the Column, which survives a
  category change. Scroll to the bottom of Console, click Advanced, land
  part-way down it.
- **The last section is clipped flush.** The scroll Column
  (`SettingsScreen.kt:81-86`) has no bottom padding and no arrangement, so the
  final plane's bevel hairline is cut mid-stroke on every category.
- **Editor panels can be dragged off-screen and not brought back.** The offsets
  are unclamped and unpersisted `remember`s (`WidgetPalettePanel.kt:73`,
  `SurfacePropertiesPanel.kt:90`); the only recovery is toggling the panel shut
  and open.
- **`Instant.parse` runs unguarded inside composition.**
  `NetworkSection.kt:61`. A malformed stored timestamp throws out of the render
  pass; there is no fallback text and no `runCatching`.
- **Diagnostics fails silently.** `DiagnosticsSection.kt:153-160` and `:192-197`
  swallow failure with `runCatching { }.getOrNull()` and draw nothing. The button
  re-enables and the user sees a control that visibly did nothing.

## 3. Rudiments

- Back arrows drawn inside a surface while the window frame already carries one
  (`TopBarBreadcrumbWidget.kt:40`). Removed from the theme picker; still present
  in `ServerSettingsScreen.kt:98` and `ServerDetailsSurface.kt:118`, where they
  are place-based and acceptable.
- `cubeResizeSpan` -- see section 1.
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
- **An empty canvas slot's drop target is only its top 80dp**, and offset 6dp
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

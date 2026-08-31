# Appearance: how it works

Read this before adding anything that changes how the launcher looks.

Appearance is not one system. It is seven stores and six layers that compose at draw
time, and most of the confusion around it comes from not knowing which layer a given
question belongs to. The recurring failure is proposing a mechanism that already
exists one layer up or down: a "themes should carry patterns" proposal is really a
question about the page ground, and a "widgets need a look" proposal is `SurfaceSpec`,
which shipped.

Every claim here carries a file:line. Check it rather than trusting it; several of the
KDoc comments in this area describe behaviour that is no longer true, and they are
called out below.

## 1. The stores

All under the platform data directory. `configPath` in these constructors is that same
directory, not a separate config root.

| File | Owner | Carries |
|---|---|---|
| `settings.json` | `SettingsService` | `isDarkTheme`, `uiStyle`, `themeMode`, `paletteFromWallpaper`, `themeFromWallpaper`, `useCustomChrome`, `homeView`, `locale` |
| `themes.json` | `ThemeManager` (`ThemeManager.kt:185`) | one whole `CustomTheme` record, not a preset name |
| `customization.json` | `CustomizationManager` (`CustomizationManager.kt:20`) | `surfaceBlur`, nav-selection style/accent/icons/hover, `accentOverride`, `densityScale` |
| `background.json` | `BackgroundManager` (`BackgroundManager.kt:14`) | the wallpaper and its 16 treatment fields |
| `layout-graph.json` | `LayoutGraphRepository` | widget placement AND each widget's `SurfaceSpec` |
| `console.json` | `ConsoleSettingsStore` (`ConsoleSettings.kt:147`) | console font, wrap, gutter, timestamps, severity colours, highlight and filter rules, empty-state art |
| `widget-state.json` | `WidgetStateStore` (`Main.kt:219`) | per-widget runtime state |

Presets under `presets/` bundle three of these at once: `PresetEnvelope` is
`{ graph, customization, uiStyle }` (`PresetEnvelope.kt:16-23`). It is the only artifact
that crosses store boundaries.

## 2. The layers, outermost first

| Layer | Installed at | Scope |
|---|---|---|
| `CustomizationSettings` | `AppShell.kt:909` | whole tree, via `LocalCustomization` |
| density scale | `AppShell.kt:893-898` | multiplies every dp below it, including blur sigma |
| `LayoutGraph` | `AppShell.kt:911` | which widgets exist and where |
| `NxColors` + `StyleSpec` | `NxTheme.kt:309-310` | the palette and the form tokens |
| per-widget `SurfaceSpec` | `SlotRenderer.kt:332-336` | one widget's plane |
| `NxSurface` | `NxSurface.kt:157-206` | the draw itself |

`LocalStyle` is provided in exactly one production place, `NxTheme.kt:310`. The comment
at `AppShell.kt:417-421` describing per-editor style overrides refers to a layer that
does not exist.

## 3. What decides one painted plane

### The palette a rung name resolves to

`resolveBasePalette` (`NxTheme.kt:199-203`):

    val effective = seed.takeIf { fromWallpaper } ?: themeSeed ?: return fixed

Wallpaper seed (only when `paletteFromWallpaper`) beats the preset's seed beats the
fixed palette. Seeding moves hue and chroma only: `tinted()` re-imposes the fixed
palette's own tone on `background`, `surface`, `surfaceVariant` and every
`surfaceContainer*` (`PaletteEngine.kt:68-72`, `:80-86`), so a seed can never move a
plane's lightness. Pinned by `ThemeGroundTest.kt:69-83`.

Then, in order: the preset's literals overwrite `primary`, `secondary`, `success`,
`error` (`NxTheme.kt:229-233`); `accentOverride` overwrites `primary` again
(`NxTheme.kt:243`); the tonal expansion recomputes `tertiary` and every `*Container`
from the resolved accents (`NxTheme.kt:251-261`).

A `CustomTheme` has eight fields. Four reach a pixel. `primary` reaches it twice --
once as the base-palette seed, once as the literal accent. `background`, `surface` and
`accent` reach nothing; they exist for the theme picker's preview and are acknowledged
as preview-only at `ThemeManager.kt:18-20`, except `accent`, which is not acknowledged
anywhere.

### Which spec applies to a widget

    fun WidgetDescriptor.resolveSurface(instance: WidgetInstance): SurfaceSpec? =
        instance.surface ?: defaultSurface

`WidgetRegistry.kt:83-84`. This is a whole-record swap, not a field merge: an instance
spec that names only `opacity` loses the declaration's fill, padding and everything
else. The declaration comes from `@Widget(surface = "<json>")`, decoded at class-init
by the KSP processor and validated at build time (`WidgetValidator.kt:191-199`).

The bundled `default-layout.json` carries no per-instance surface records, so out of the
box every plane comes from its widget's declaration.

### Per property

Fill colour: a literal hex in `SurfaceSpec.fill` wins absolutely; else a rung name maps
to a level and the level to a palette field (`WidgetSurface.kt:75-84`,
`NxSurface.kt:65-70`); else `Raised`. A blank, a typo and an unparseable hex are the
same thing -- `parseFill` is total and degrades everything to inherit
(`SurfaceSpec.kt:64-81`).

Opacity: the named value, else `bodyFloor(dark)` -- 0.92 dark, 1.0 light
(`NxSurface.kt:86`). `dark` here is the resolved body colour's luminance, not the theme
flag (`NxSurface.kt:160`).

Blur radius: the named value including a named zero, else `style.surfaceBlur`
(`NxSurface.kt:165`). Then see section 4; three separate things can take it away.

Shape: `kind` when it names one of circle, pill, rect, star, polygon
(`WidgetSurface.kt:116-130`); otherwise per-corner radii with `style.cardCorner` as the
fallback, and `smoothing > 0` selects `SmoothedRectShape`.

Border: width from the spec defaulting to 0 for widgets (`WidgetSurface.kt:63`) and to
1 for library call sites (`NxSurface.kt:134`); colour from the spec, else a bevel
derived from the body's own luminance (`SurfaceTone.kt:16-18`).

Shadow: the spec's value, else 0. No style token feeds it automatically; `panelElevation`
arrives only where a call site names it.

Padding: per side, `side ?: all ?: 0f`. It is an OUTER inset -- the plane is inset and
its corners hug the widget (`WidgetSurface.kt:36-38`). There is no theme fallback.

### Draw order

`NxSurface.kt:175-205`, one layout node: shadow, then the hairline stroked OUTSIDE the
clip and above the content, then the clip, then backdrop blur, body, state tint, then
the content inside the clip. The hairline is outside the clip deliberately: a stroke is
centred on the outline, so clipping it leaves half a line that corner antialiasing then
eats (`NxSurface.kt:107-109`).

## 4. Where a lower layer silently wins

These are the ones that produce "the control does nothing" reports.

A literal fill's alpha channel is discarded. `SurfaceSpec` accepts 8-digit hex and keeps
the alpha byte; `NxSurface.kt:161` then replaces it with the named opacity or the floor.
`dark` at `:160` reads `luminance()`, which ignores alpha, so a nearly transparent white
is classified light and repainted fully opaque.

A named blur radius is dropped when the body is opaque -- `NxSurface.kt:166` passes 0
whenever `body.alpha >= 1f`. On the light theme `bodyFloor` is 1.0, so an unconfigured
plane there can never blur, while the editor's blur slider still reads 18.

The global blur switch beats everything: `NxSurface.kt:263` returns null before any Skia
object is built, whatever the spec, the style or the call site asked for.

Any ancestor with alpha below 1 empties the backdrop filter, because alpha isolates the
surface into its own layer and the filter then finds it empty (`NxSurface.kt:246-252`).
Live instances: the screen swap's fade, a widget being dragged in edit mode, any
`AnimatedVisibility` that fades.

Border width 0 nullifies border colour and border opacity (`NxSurface.kt:167`), and the
widget default IS 0, so a spec naming only a border colour draws nothing.

Every rung name in `border.color` collapses to `outline` (`WidgetSurface.kt:92`), unlike
in `fill` where the four rungs are distinct.

A shape `kind` discards the corner record entirely, and the editor shows the corner
sliders anyway.

An all-default spec normalises back to null (`LayoutGraph.kt:180`), which resurrects the
declaration. Walking every slider back to its default does not produce a bare widget.

`resolveSurface`'s KDoc says a widget wanting no plane at all names an opacity of zero.
That turns the body invisible but leaves the blur switched on, since `body.alpha < 1f`
is then true, and leaves the hairline and the padding. There is currently no way to
express "no plane" on a widget whose declaration carries one.

## 5. Authorable, and by whom

Through a UI control: the theme preset; dark/light and its source; palette from
wallpaper; UI style; the global blur switch; every wallpaper field; nav-selection style,
accent, outline icons and hover; every `SurfaceSpec` field per widget, through the
editor's property panel; widget placement, slot orientation, grid columns, canvas
offsets, size and z; the shell regions' opacity, blur, width, collapse and divider;
console font, wrap, gutter, timestamps, buffer, severity colours, highlight and filter
rules and empty-state art.

Only by hand-editing JSON: `accentOverride` and `densityScale` in `customization.json`
(both are read at render, neither has a writer anywhere in the tree);
`BackgroundSettings.hardwareDecode`; `SlotOrientation.CubeGrid`, which is fully
implemented and deliberately hidden from the menu (`SlotLayoutChrome.kt:229-233`); a
`CustomTheme` with hexes of your own, since the picker only selects among the nine in
`ThemePresets`.

Only in code: any `StyleSpec` value other than the two shipped presets -- `AppShell.kt:413-416`
is an exhaustive `when` over a two-valued enum and `StyleSpec` is not serialisable; the
whole `PaletteSpec` axis, because the single production entry point `seededNxColors`
hard-codes variant, contrast and seed count (`PaletteEngine.kt:102-103`); every surface
not drawn through the widget kernel, which is most dialogs, sections and the shell
chrome's inner planes.

This asymmetry is the shape of the whole system, and it is why a theme reads as a
colouring: the axis a person can author carries no form, and the axis that carries form
cannot be authored.

## 6. Declared, not connected

- `StyleSpec.cardSurface` says it decides glass versus flat opaque. `NxSurface` never
  reads it; its only consumer is the April Fools skin. Under Brut a plane is flat
  because `BrutStyle.surfaceBlur` is 0, not because of this field.
- `StyleSpec.cardBorder` reaches one component, `NotificationCard`. `NxSurface`
  hard-codes 1.
- `CustomTheme.background`, `.surface`, `.accent` -- see section 3.
- `WidgetInstance.weight` is read at render but no control writes it; only the bundled
  default layout carries a non-zero one.
- Recovery's "Reset customization" deletes `themes.json`, `background.json`,
  `console.json` and `widget-state.json` (`RecoveryIo.kt:26`). It does not delete
  `customization.json`, so the blur switch, the nav-selection settings, `accentOverride`
  and `densityScale` survive the reset advertised as clearing them.
- The standalone console window calls `NxTheme` without a palette seed
  (`ConsoleWindow.kt:288-292`), so with wallpaper seeding on the shell is tinted and the
  console is not.
- The console's "changes apply next time the console is opened" note is wrong: the
  palette is remembered on the colour fields off a shared flow and updates live.

## 7. Where a new idea belongs

Given an appearance idea, the layer follows from two questions: what does it change, and
who authors it.

- One widget's plane -- fill, opacity, blur, shape, border, shadow, inset: `SurfaceSpec`.
  It exists, it is authorable, it persists per instance. Do not add a parallel mechanism
  for a subset of it.
- The relationship between planes -- corners, elevation, whether the interface blurs at
  all, how still it is, what a switch or a badge looks like: `StyleSpec`. Note that
  authoring here does not exist yet, and that opening it as raw numbers is not the same
  as opening it.
- A colour role: the palette. Adding a role means adding it to `NxColors` and to the
  generator, not to `CustomTheme`, which is a preset format rather than a palette.
- The page ground: it has no owner. It is one `Modifier.background(NxTheme.colors.background)`
  at `AppShell.kt:1293`. Anything about what sits behind every plane lands here, and
  there is nothing to reuse yet.
- The wallpaper and its treatment: `BackgroundSettings`, which already carries sixteen
  fields including blur, tint, saturation and vignette.

The trap in all five: `NxColors` fields are `Color`, `NxSurface` draws with `drawRect`,
and `Brush` does not appear anywhere in the surface or theme packages. Anything that is
not a flat colour is currently inexpressible everywhere except the wallpaper, and that
is a property of the drawing code rather than of the theme format.

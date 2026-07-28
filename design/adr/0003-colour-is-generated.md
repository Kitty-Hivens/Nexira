---
title: ADR 0003 -- Colour is generated, a theme is form
description: The launcher stops shipping palettes and starts generating them. Colour becomes a seed, a tonal variant and a contrast level resolved by the colour science; what the user picks as a "theme" moves onto the form axis. The preset system, the theme-picker screen and the fixed palettes are deleted rather than migrated.
---

## Status

**On hold -- 2026-07-28.** Proposed and then pulled back the same day. The engine
stays; wiring it into the running app does not, because doing so broke the interface
in two ways worth recording before anyone tries again:

- Raising the surfaces' chroma turned the glass coat into a colour filter. A frosted
  surface is a translucent layer over a blurred copy of the wallpaper, so it already
  carries the wallpaper's colour; tinting the surface role applies that colour a
  second time, and because surfaces nest, each nested one applies it again.
- The science's own surface ladder is tighter than this interface carries. It spans
  about 8 L* from the ground to the topmost plane where the hand-written palettes
  spanned 12, and every plane is then covered by a coat that compresses the
  difference further, so neighbouring levels stopped reading as separate. The
  contrast level does not help: it moves on-colours and accents and leaves the
  ladder identical at every setting.

Both were addressed and the result still read worse than what it replaced, so the
feature is unstable rather than incremental. What ships is the generator and its
tests; what does not is any of it deciding what the running app looks like.

Supersedes the preset system introduced with the theme picker; extends nothing.

Depends on nothing outstanding. The generating half landed already: `PaletteSpec` and `generatedNxColors` resolve a seed, a variant, a contrast level and an optional second seed against the 2025 colour spec, with eleven property tests across all nine variants and both themes.

## Context

Established by reading and measuring the tree.

**Two systems currently decide colour, and they fight.** The engine generates a tonal scheme from a wallpaper seed; `CustomTheme` then copies five hex values over the result (`primary`, `secondary`, `success`, `error`). A warm preset over a cool wallpaper therefore lands warm accents on cool surfaces, which reads as a bug and is in fact both systems working as designed.

**The generator is barely used.** Until this week it built exactly one scheme -- tonal spot, standard contrast, the 2021 spec -- from one line. The library it already depends on ships nine tonal variants, two colour-spec versions, a contrast level that was passed as a hardcoded zero, and a constructor taking explicit tonal palettes, which is what two-tone theming needs.

**A fresh install never runs it at all.** `BackgroundSettings` starts disabled with no image and no wallpaper ships, so there is no seed, and the fixed palette applies. Measured in HCT chroma, that palette's background, surface and top plane are 0.7, 0.9 and 1.0 -- neutral grey by construction. The same palette generated from a seed measures 5 to 8 on the default variant and 28 to 34 on the most colourful one. The default panel tiers compound it: the rails are a 35% fill of the surface role and nothing else, which reads as glass over a wallpaper and as near-black on near-black without one.

**The palette is read through one type.** `NxColors` has 46 fields and is read by 138 files. Sixty-four of the sixty-six colour literals in the design-system module are the two fixed palettes themselves. Everything downstream is therefore insulated from how the palette is produced, as long as that type keeps its shape.

**Severity is not part of the colour spec.** Material defines `error` and nothing for success, warning or progress. Those three were fixed literals kept out of generation, so they did not follow the contrast level or the scheme.

**The surfaces that express colour predate the engine.** The theme-picker screen is 473 lines built when there was no colour generation and no custom background; its entire job is choosing one of ten hex sets. The palette state travels from the shell root down four signatures to reach the appearance studio, so each new colour knob costs four edits.

## Decision

**Colour is always generated. A seed, a tonal variant and a contrast level produce the palette; nothing overwrites the result afterwards. What a user picks as a "theme" is form -- shape, surface treatment and motion -- and lives on the style axis. The preset system and the surface built for it are deleted, not carried forward.**

### Colour

The palette is a function of four inputs: a seed, its source, a tonal variant, and a contrast level, with an optional second seed driving the supporting accents for two-tone schemes. The 2025 colour spec is the default and is not a user-facing choice.

**The seed has three sources**, expressed as an enum rather than the current boolean, which cannot represent three states:

- the wallpaper, extracted from the backdrop as today;
- a colour the user chose;
- the brand seed, which is also the fallback when the other two are absent.

That last one is what makes generation unconditional. A fresh install with no wallpaper generates from the brand seed instead of falling back to a hand-written palette, so the engine is never bypassed and there is no second code path to keep in sync.

**Severity accents are derived, not fixed.** Success, warning and progress are built from fixed hues, borrowing both the tone and the chroma the scheme gave `error`. The tone is what makes them follow dark and light and the contrast level without a second solver. The chroma is borrowed rather than fixed because the 2025 spec scales the error role with how expressive the scheme is, so a fixed value would leave a success louder than the error beside it wherever the scheme is quiet. Only the hues stay constant, because severity is a learned code and not a decorative choice.

Worth recording, since it is not obvious: the monochrome variant is not a desaturation filter. It greys its own accents and leaves the error palette alone, measured at chroma 29 against 0.7 on its surfaces. Anything reasoning about "the palette with colour removed" has to account for that.

**The two fixed palettes stop being palettes.** What survives of them is the brand record the generator preserves: source colours, the decorative ramp, glass. The forty-odd generated fields go.

**Contrast sits behind a disclosure.** It is a real parameter and some people want it, so it ships -- but behind a switch that reveals the advanced rendering controls, rather than in the first row a new user meets. The default is standard.

**The second seed ships in the first version.** It works in the engine, and a control held back for later is a control that never arrives.

### Form

The style axis (`StyleSpec`) is what a theme means from here: corners, borders, surface treatment, elevation, motion, and the shape of switches and badges. It is currently two variants where the second is defined almost entirely by subtraction, which is the real work this ADR does not do. Naming it is the point: the colour half is a library call, the form half is design.

### State

The palette lives in one injected holder exposed as a state flow, read by the theme at the composition root and written by the appearance surface directly. It does not travel down a parameter chain. The pattern is already in the tree: navigation requests from outside the composition go through a singleton mediator for exactly this reason.

### Surfaces

The theme-picker screen is deleted, along with its route and the rows pointing at it. Source, seed, variant and contrast are controls in the appearance studio, beside the wallpaper and the dark/light axis they interact with. A separate screen existed because ten preset cards needed room; with the presets gone there is nothing to fill it.

The controls are a user feature, not a diagnostic. Browsing what a scheme does to the live interface is customization, which is what this launcher is for. Instrumentation -- contrast ratios, chroma readouts, token dumps -- belongs on the developer overlay and is not part of this.

## What we deliberately do not build

**Migration of the ten presets.** Nine variants over any seed is strictly more than ten fixed hex sets, and nothing about a preset survives translation except its accent. A user's saved theme contributes its primary as a custom seed; the shipped presets are dropped.

**A palette editor.** Editing individual roles re-creates the two-systems problem this ADR exists to remove. The user chooses inputs; the science resolves roles.

**Per-surface colour overrides.** Same reason.

## Open questions

These change the implementation and are not settled here.

1. **Where the variant enum is declared.** Settings live in a module that does not know the design system, so either the enum descends into the core module and the design system maps it onto the library's schemes, or settings store a name and the mapping lives at the boundary. The first keeps one type and lets the compiler check exhaustiveness; the second keeps module boundaries untouched.

2. **The default variant.** Measured on the current brand hue, the conservative variant leaves surfaces near-neutral and only the accent carries colour, which is the state this ADR calls grey. The most colourful variant tints the surfaces themselves; without a wallpaper the whole window becomes a coloured field, which may be more than wanted. A middle variant keeps the accent faithful at roughly half the surface tint.

3. **Whether a variant change plays the reveal.** The dark/light flip carries a circular reveal because the palette swaps in one recomposition and per-token animation froze the interface. A variant change is the same kind of swap. An alternative worth weighing is animating colour inside the surface primitive instead, where each surface tweens its own resolved colour and no composition-local invalidation occurs.

## Open questions raised while writing this

Recorded rather than answered. They are design work, not configuration.

**What "two-tone" means.** The second seed currently feeds the secondary and tertiary palettes, so it splits the accents among themselves and leaves the surfaces alone. A different and arguably more striking reading is that it should feed the neutrals, so the surfaces come from one hue and every accent from another -- a warm background under cool components. The library's explicit-palette constructor supports either. Deciding which one the control means has to happen before it is exposed, or the knob will mean one thing and be wanted for the other.

**What the second and third accents are for.** Measured: `primary` has 198 call sites, `secondary` and `tertiary` have zero. Three accent families are generated, carried in the type, and never spent. Picking a more colourful variant does not fix this -- it produces more colour that nothing renders. Each family needs a rule that includes its negation ("this and nowhere else"), or a second colour only doubles the noise.

**How often the accent is allowed to appear.** 198 uses of one accent is not a palette, it is a wash: when everything is emphasised nothing is. The first move on colour is probably subtraction, not addition, and that is a design pass rather than a code change.

**How planes are allowed to separate.** The surface test asserts a perceptual step in lightness between adjacent planes. A palette whose planes are identical in hue and one step apart in lightness passes it. If planes are meant to differ by colour, the rule has to say so.

## Consequences

The channel-colour inconsistency resolves as a side effect: channel colours become derived roles of the active scheme, so one channel is one colour everywhere by construction rather than by coincidence across three separate mappings.

`paletteFromWallpaper`, added as a boolean this week, is replaced by the source enum before it ships in that shape. The existing migration of the legacy wallpaper flag into the theme-mode enum is the precedent to follow: a stored non-default is an explicit choice and outranks the legacy flag.

The fixed palettes shrink to a brand record, the preset system and its screen are deleted, and the derivation step that recomputes the third accent and the container fills goes with them, since the scheme provides both. That derivation is also why a second seed currently cannot reach the screen.

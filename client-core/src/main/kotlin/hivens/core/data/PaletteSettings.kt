package hivens.core.data

import kotlinx.serialization.Serializable

/**
 * Where the colour the palette is generated from comes from.
 *
 * There is no "off": the palette is always generated, and this only decides the
 * seed. That is what keeps a fresh install -- which ships no wallpaper -- on the
 * same code path as a configured one, instead of falling back to a hand-written
 * palette nobody maintains.
 *
 * - [Wallpaper] -- the dominant colour of the background image. Falls through to
 *   [Brand] while no wallpaper is set or none has been decoded yet.
 * - [Custom] -- a colour the user picked, held in [SettingsData.paletteCustomSeed].
 * - [Brand] -- the shipped accent. Also the fallback for the other two.
 */
@Serializable
enum class PaletteSource { Wallpaper, Custom, Brand }

/**
 * Which tonal strategy turns the seed into a scheme. Mirrors the design system's
 * own enum rather than sharing it: the design system is a leaf module that depends
 * on nothing in-tree, so the persisted choice cannot name its type. The mapping
 * between the two is exhaustive and lives at the one boundary that sees both.
 *
 * Ordered from the most conservative to the least: [TonalSpot] keeps colour in the
 * accent alone, [Vibrant] and [FruitSalad] carry it into the surfaces, [Expressive]
 * and [Rainbow] move the accent off the seed hue on purpose, and [Neutral] with
 * [Monochrome] drain it.
 */
@Serializable
enum class PaletteVariant {
    TonalSpot,
    Vibrant,
    Expressive,
    Rainbow,
    FruitSalad,
    Content,
    Fidelity,
    Neutral,
    Monochrome,
}

/**
 * The seed source a session starts on. Migrates the pre-source opt-out the same
 * way [resolveInitialThemeMode] migrates the theme flag: a settings file written
 * before [SettingsData.paletteSource] existed decodes with the field's default, so
 * the legacy boolean only speaks when it was turned OFF -- which used to mean "do
 * not derive from the wallpaper" and now means the brand seed. A stored non-default
 * source is an explicit choice and outranks the legacy flag.
 */
fun resolveInitialPaletteSource(s: SettingsData): PaletteSource =
    if (s.paletteSource == PaletteSource.Wallpaper && !s.paletteFromWallpaper) PaletteSource.Brand
    else s.paletteSource

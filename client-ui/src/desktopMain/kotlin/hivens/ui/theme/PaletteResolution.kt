package hivens.ui.theme

import hivens.core.data.PaletteSource
import hivens.core.data.SettingsData
import hivens.core.data.resolveInitialPaletteSource
import hivens.core.data.PaletteVariant as StoredVariant
import hivens.ui.theme.PaletteVariant as SchemeVariant

/**
 * Turns persisted settings into the spec the colour engine resolves.
 *
 * The two variant enums are mirrors: the design system is a leaf module that
 * depends on nothing in-tree, so the persisted choice cannot name its type. This
 * is the one boundary that sees both, and the mapping is exhaustive so adding a
 * strategy on either side fails to compile until the other follows.
 */
fun paletteSpecFor(settings: SettingsData, dark: Boolean, wallpaperSeedArgb: Int?): PaletteSpec =
    PaletteSpec(
        seedArgb = seedFor(settings, wallpaperSeedArgb),
        dark = dark,
        variant = settings.paletteVariant.toScheme(),
        contrastLevel = settings.paletteContrast.toDouble().coerceIn(-1.0, 1.0),
        secondarySeedArgb = settings.paletteSecondarySeed?.let { parseHexColorOrNull(it)?.toArgb() },
    )

/**
 * The seed the chosen source yields, falling through to the brand colour whenever
 * that source has nothing to offer -- no wallpaper decoded yet, or a custom colour
 * that will not parse. Generation is unconditional, so this never returns null and
 * there is no second path for "no seed".
 */
private fun seedFor(settings: SettingsData, wallpaperSeedArgb: Int?): Int =
    when (resolveInitialPaletteSource(settings)) {
        PaletteSource.Wallpaper -> wallpaperSeedArgb ?: BRAND_SEED_ARGB
        PaletteSource.Custom -> settings.paletteCustomSeed?.let { parseHexColorOrNull(it)?.toArgb() } ?: BRAND_SEED_ARGB
        PaletteSource.Brand -> BRAND_SEED_ARGB
    }

private fun StoredVariant.toScheme(): SchemeVariant = when (this) {
    StoredVariant.TonalSpot -> SchemeVariant.TonalSpot
    StoredVariant.Vibrant -> SchemeVariant.Vibrant
    StoredVariant.Expressive -> SchemeVariant.Expressive
    StoredVariant.Rainbow -> SchemeVariant.Rainbow
    StoredVariant.FruitSalad -> SchemeVariant.FruitSalad
    StoredVariant.Content -> SchemeVariant.Content
    StoredVariant.Fidelity -> SchemeVariant.Fidelity
    StoredVariant.Neutral -> SchemeVariant.Neutral
    StoredVariant.Monochrome -> SchemeVariant.Monochrome
}

private fun androidx.compose.ui.graphics.Color.toArgb(): Int =
    (value shr 32).toInt()

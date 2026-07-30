package hivens.ui.theme

import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.hct.Hct
import com.materialkolor.palettes.TonalPalette
import com.materialkolor.scheme.DynamicScheme
import com.materialkolor.scheme.SchemeContent
import com.materialkolor.scheme.SchemeExpressive
import com.materialkolor.scheme.SchemeFidelity
import com.materialkolor.scheme.SchemeFruitSalad
import com.materialkolor.scheme.SchemeMonochrome
import com.materialkolor.scheme.SchemeNeutral
import com.materialkolor.scheme.SchemeRainbow
import com.materialkolor.scheme.SchemeTonalSpot
import com.materialkolor.scheme.SchemeVibrant
import com.materialkolor.scheme.Variant

/**
 * How a seed colour is turned into a scheme. Each entry is one of the tonal
 * strategies the colour science ships: they differ in how far the accents travel
 * from the seed hue and how much chroma reaches the surfaces, from [Monochrome]
 * (no chroma at all) to [FruitSalad] (accents deliberately off-hue).
 *
 * Declared here rather than re-exporting the library's enum so the rest of the UI
 * names a variant without importing the colour science, and so a variant can be
 * persisted by name without pinning a dependency's ordinal.
 */
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
 * Everything that decides a generated palette, in one value.
 *
 * [secondarySeedArgb] drives the secondary and tertiary palettes from a colour of
 * their own -- two-tone theming, where the surfaces follow one colour and the
 * supporting accents another. Null means the single-seed case, where every palette
 * comes from [seedArgb].
 *
 * [contrastLevel] runs -1.0 (reduced) through 0.0 (standard) to 1.0 (maximum) and
 * is passed to the colour science rather than applied afterwards, so on-colours are
 * re-solved against their backgrounds instead of being lightened blindly.
 */
data class PaletteSpec(
    val seedArgb: Int,
    val dark: Boolean,
    val variant: PaletteVariant = PaletteVariant.TonalSpot,
    val contrastLevel: Double = 0.0,
    val secondarySeedArgb: Int? = null,
    val specVersion: ColorSpec.SpecVersion = ColorSpec.SpecVersion.SPEC_2025,
)

/** Builds the colour-science scheme this spec describes. */
internal fun PaletteSpec.toScheme(): DynamicScheme {
    val hct = Hct.fromInt(seedArgb)
    val base = when (variant) {
        PaletteVariant.TonalSpot -> SchemeTonalSpot(hct, dark, contrastLevel, specVersion, PLATFORM)
        PaletteVariant.Vibrant -> SchemeVibrant(hct, dark, contrastLevel, specVersion, PLATFORM)
        PaletteVariant.Expressive -> SchemeExpressive(hct, dark, contrastLevel, specVersion, PLATFORM)
        PaletteVariant.Rainbow -> SchemeRainbow(hct, dark, contrastLevel, specVersion, PLATFORM)
        PaletteVariant.FruitSalad -> SchemeFruitSalad(hct, dark, contrastLevel, specVersion, PLATFORM)
        PaletteVariant.Content -> SchemeContent(hct, dark, contrastLevel, specVersion, PLATFORM)
        PaletteVariant.Fidelity -> SchemeFidelity(hct, dark, contrastLevel, specVersion, PLATFORM)
        PaletteVariant.Neutral -> SchemeNeutral(hct, dark, contrastLevel, specVersion, PLATFORM)
        PaletteVariant.Monochrome -> SchemeMonochrome(hct, dark, contrastLevel, specVersion, PLATFORM)
    }
    val second = secondarySeedArgb ?: return base

    // Two-tone: keep the chosen variant's own primary, neutral and error work, and
    // swap only the two palettes the second seed is meant to own. Rebuilt through
    // the explicit-palette constructor rather than by re-running the variant on the
    // second seed, which would also move the surfaces.
    val secondHct = Hct.fromInt(second)
    return DynamicScheme(
        base.sourceColorHct,
        base.variant,
        base.isDark,
        base.contrastLevel,
        base.platform,
        base.specVersion,
        base.primaryPalette,
        TonalPalette.fromHct(secondHct),
        TonalPalette.fromHueAndChroma(secondHct.hue, secondHct.chroma / 2.0),
        base.neutralPalette,
        base.neutralVariantPalette,
        base.errorPalette,
    )
}

/** Every scheme here is built for a desktop window, never a watch face. */
private val PLATFORM = DynamicScheme.Platform.PHONE

internal fun Variant.asPaletteVariant(): PaletteVariant = when (this) {
    Variant.TONAL_SPOT -> PaletteVariant.TonalSpot
    Variant.VIBRANT -> PaletteVariant.Vibrant
    Variant.EXPRESSIVE -> PaletteVariant.Expressive
    Variant.RAINBOW -> PaletteVariant.Rainbow
    Variant.FRUIT_SALAD -> PaletteVariant.FruitSalad
    Variant.CONTENT -> PaletteVariant.Content
    Variant.FIDELITY -> PaletteVariant.Fidelity
    Variant.NEUTRAL -> PaletteVariant.Neutral
    Variant.MONOCHROME -> PaletteVariant.Monochrome
}

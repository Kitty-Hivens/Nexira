package hivens.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import hivens.core.api.dto.smrt.SmrtSource
import hivens.core.data.PackOrigin

// Two kinds of colour that are not the theme's.
//
// A source's brand colour belongs to the source: Modrinth is green whatever theme is
// active, which is how a reader knows it at a glance. It only changes with the mode,
// to a deeper shade on a light ground, so a white label on the badge still reads.
//
// Decorative variety (an avatar per name, a pixel-art banner per pack) has no meaning
// beyond "these differ", so it is asked of the theme's distributor, which hands out
// the theme's own colours first and makes more in its character when it runs out.

private data class BrandPair(val dark: Color, val light: Color)

private val Smartycraft = BrandPair(Color(0xFF8B5CF6), Color(0xFF6D28D9))
private val Mirror = BrandPair(Color(0xFF3B82F6), Color(0xFF2563EB))
private val Modrinth = BrandPair(Color(0xFF22C55E), Color(0xFF16A34A))
private val CurseForge = BrandPair(Color(0xFFF16436), Color(0xFFC2410C))

/**
 * GitHub's own palette is monochrome, so the badge is too. The dark shade is the one
 * a white label survives on: the near-white end of their scale put white on
 * near-white at 1.59:1.
 */
private val Github = BrandPair(Color(0xFF6E7781), Color(0xFF24292F))
private val Local = BrandPair(Color(0xFF9CA3AF), Color(0xFF64748B))

@Composable @ReadOnlyComposable
private fun BrandPair.now(): Color = if (NxTheme.isDark) dark else light

/** The brand colour of a pack's origin. */
@Composable @ReadOnlyComposable
fun originColor(origin: PackOrigin): Color = when (origin) {
    PackOrigin.Smartycraft -> Smartycraft.now()
    PackOrigin.Mirror      -> Mirror.now()
    PackOrigin.Modrinth    -> Modrinth.now()
    PackOrigin.Local,
    PackOrigin.Unknown     -> Local.now()
}

/** The brand colour of a mirror source type; follows [originColor]'s mapping. */
@Composable @ReadOnlyComposable
fun sourceColor(source: SmrtSource): Color = when (source) {
    is SmrtSource.Modrinth   -> Modrinth.now()
    is SmrtSource.SmrtCache  -> Mirror.now()
    is SmrtSource.SmrtStatic -> Local.now()
    is SmrtSource.CurseForge -> CurseForge.now()
    is SmrtSource.Github     -> Github.now()
    is SmrtSource.Unknown    -> Local.now()
}

/** Card hero gradient for an origin: the brand colour shaded toward black. */
@Composable @ReadOnlyComposable
fun originGradient(origin: PackOrigin): Brush {
    val base = originColor(origin)
    return Brush.linearGradient(listOf(base, lerp(base, Color.Black, 0.35f)))
}

/** A stable colour for a name, from the theme's spread of distinct colours. */
@Composable @ReadOnlyComposable
fun decorativeColor(name: String): Color = decorativePair(name).first

/**
 * A stable two-colour pair for a name: two neighbours in the theme's spread, so an
 * avatar keeps its two-hue gradient and stays in the theme. Callers build the brush
 * with their own alphas.
 */
@Composable @ReadOnlyComposable
fun decorativePair(name: String): Pair<Color, Color> {
    val spread = NxTheme.colours.distinct(DECORATIVE_SPREAD)
    val i = Math.floorMod(name.hashCode(), spread.size)
    return spread[i] to spread[(i + 1) % spread.size]
}

private const val DECORATIVE_SPREAD = 8

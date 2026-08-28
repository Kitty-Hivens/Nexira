package hivens.ui.customization

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import hivens.ui.theme.DarkColorPalette
import hivens.ui.theme.LightColorPalette
import hivens.ui.theme.LocalNxColors
import hivens.ui.theme.NxColors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The depth a call site asks for has to reach the screen.
 *
 * On light it could not: `glassSurfaceAlpha` returned `surface` for every value, so
 * the eight distinct depths in use across forty-odd call sites all came out as one
 * pixel -- a card, the page under it and a panel nested inside it were the same
 * colour. These pin that a larger request now lands on a deeper rung, and that the
 * rungs it lands on are ladder colours rather than shades invented in between.
 */
class GlassDepthTest {

    /** Every value the repository actually passes, smallest first. */
    private val requested = listOf(0.35f, 0.40f, 0.45f, 0.50f, 0.55f, 0.60f, 0.65f, 0.85f)

    @Test
    fun `a deeper request never comes back lighter on the light palette`() {
        val planes = requested.map { it to resolve(it, LightColorPalette) }
        for (i in 0 until planes.size - 1) {
            val (a, ca) = planes[i]
            val (b, cb) = planes[i + 1]
            assertTrue(
                lstar(cb) <= lstar(ca) + 0.01,
                "$b came back lighter than $a (${"%.2f".format(lstar(cb))} vs ${"%.2f".format(lstar(ca))} L*)",
            )
        }
    }

    @Test
    fun `the extremes of the range are not the same plane`() {
        val shallow = resolve(requested.first(), LightColorPalette)
        val deep = resolve(requested.last(), LightColorPalette)
        val d = lstar(shallow) - lstar(deep)
        assertTrue(d >= 3.0, "0.35 and 0.85 are only ${"%.2f".format(d)} L* apart -- the depth axis is inert")
    }

    @Test
    fun `every answer is a rung of the ladder, not a shade between them`() {
        // Only five colours may back a plane. A blend would put tones in the gaps
        // that nothing else in the system knows about and no separation rule covers.
        val rungs = with(LightColorPalette) {
            setOf(background, surfaceContainerLow, surface, surfaceContainer, surfaceContainerHigh)
        }
        for (a in requested) {
            val c = resolve(a, LightColorPalette)
            assertTrue(c in rungs, "$a resolved to $c, which is not a ladder colour")
        }
    }

    @Test
    fun `the shallow end matches what the Clear tier paints`() {
        // The shell draws a wedge that carries the content's corner into the chrome,
        // and it does that by painting `glassSurfaceAlpha(0.35)` next to a Clear
        // surface, which tints from its own Surface role. They only join while the
        // two are the same colour; when they parted, the wedge read as a patch stuck
        // on the corner instead of a transition.
        assertEquals(
            LightColorPalette.surface,
            resolve(0.35f, LightColorPalette),
            "the wedge and the chrome it joins must resolve to one colour",
        )
    }

    @Test
    fun `light can be asked for a real alpha instead of a rung`() {
        // The rung substitution is the default answer to mud on a light ground, not a
        // rule: a caller that blurs what is behind it, or knows nothing busy is there,
        // has its own answer and must be able to spend the number as an alpha.
        val rung = resolve(0.45f, LightColorPalette)
        val alpha = resolve(0.45f, LightColorPalette, translucentOnLight = true)
        assertEquals(1f, rung.alpha, "the default light plane must stay opaque")
        // Color packs alpha into 8 bits, so 0.45 comes back as 115/255.
        assertEquals(0.45f, alpha.alpha, absoluteTolerance = 0.01f)
        assertEquals(LightColorPalette.surface, alpha.copy(alpha = 1f))
    }

    @Test
    fun `dark still composites and still honours the glass knob`() {
        // The light branch must not have taken the dark one with it: there the
        // number is an alpha over the page, and that is what the intensity scales.
        val shallow = resolve(0.35f, DarkColorPalette)
        val deep = resolve(0.85f, DarkColorPalette)
        assertEquals(DarkColorPalette.surface.copy(alpha = 0.35f), shallow)
        assertEquals(DarkColorPalette.surface.copy(alpha = 0.85f), deep)
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun resolve(alpha: Float, palette: NxColors, translucentOnLight: Boolean = false): Color {
        var out = Color.Unspecified
        val scene = ImageComposeScene(1, 1, density = Density(1f)) {
            CompositionLocalProvider(LocalNxColors provides palette) {
                Probe { out = glassSurfaceAlpha(alpha, translucentOnLight) }
            }
        }
        scene.render()
        scene.close()
        return out
    }

    @Composable
    private fun Probe(body: @Composable () -> Unit) = body()

    private fun lstar(c: Color): Double {
        fun lin(v: Float) = if (v <= 0.04045f) v / 12.92 else Math.pow(((v + 0.055) / 1.055), 2.4)
        val y = 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
        return if (y > 0.008856) 116 * Math.cbrt(y) - 16 else 903.3 * y
    }
}

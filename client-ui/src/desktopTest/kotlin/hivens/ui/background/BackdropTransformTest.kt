package hivens.ui.background

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The wallpaper transform helpers: scale mode, alignment, saturation and the
 * parallax pair. One reader left now that the frost blurs the canvas beneath it
 * rather than reproducing the wallpaper, but they decide where every pixel of the
 * background lands, so they are worth pinning.
 */
class BackdropTransformTest {

    private fun near(expected: Float, actual: Float) =
        assertTrue(abs(expected - actual) < 1e-4f, "expected ~$expected but was $actual")

    @Test
    fun parallaxScaleAddsHeadroom() {
        near(1f, parallaxScaleFor(0f))
        near(1.15f, parallaxScaleFor(1f))
    }

    @Test
    fun parallaxTranslationIsZeroAtCenter() {
        assertEquals(Offset.Zero, parallaxTranslationFor(Offset(0.5f, 0.5f), 1f))
    }

    @Test
    fun parallaxTranslationSignsAwayFromCursor() {
        // Cursor at top-left -> image pushed down-right (positive x, ... y by formula).
        val t = parallaxTranslationFor(Offset(0f, 1f), 1f)
        near(40f, t.x)   // (0.5 - 0.0) * 1 * 80
        near(-40f, t.y)  // (0.5 - 1.0) * 1 * 80
    }

    @Test
    fun parallaxTranslationZeroWhenDisabled() {
        assertEquals(Offset.Zero, parallaxTranslationFor(Offset(0f, 0f), 0f))
    }

    @Test
    fun scaleModeMapsToContentScale() {
        assertEquals(ContentScale.Crop, bgContentScale(ScaleMode.COVER))
        assertEquals(ContentScale.Fit, bgContentScale(ScaleMode.CONTAIN))
        assertEquals(ContentScale.FillBounds, bgContentScale(ScaleMode.STRETCH))
        assertEquals(ContentScale.None, bgContentScale(ScaleMode.ORIGINAL))
        assertEquals(ContentScale.None, bgContentScale(ScaleMode.TILE))
    }

    @Test
    fun alignmentPlacesByFraction() {
        val center = bgAlignment(0.5f, 0.5f).align(IntSize(100, 100), IntSize(300, 300), LayoutDirection.Ltr)
        assertEquals(100, center.x) // (300 - 100) * 0.5
        assertEquals(100, center.y)
        val topLeft = bgAlignment(0f, 0f).align(IntSize(100, 100), IntSize(300, 300), LayoutDirection.Ltr)
        assertEquals(0, topLeft.x)
        assertEquals(0, topLeft.y)
        val bottomRight = bgAlignment(1f, 1f).align(IntSize(100, 100), IntSize(300, 300), LayoutDirection.Ltr)
        assertEquals(200, bottomRight.x)
        assertEquals(200, bottomRight.y)
    }
}

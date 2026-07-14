package hivens.ui.chrome

import java.awt.Dimension
import kotlin.test.Test
import kotlin.test.assertEquals

class WindowMinSizeTest {

    @Test
    fun `large screen returns the design intent unchanged`() {
        // Roomy 4K desktop -- the 960x600 dp design intent (here passed
        // as the already-density-resolved pixel values) is well within
        // 90% of the screen, so no clamp happens.
        val result = computeSafeWindowMinSize(
            designWidthPx = 1920,
            designHeightPx = 1200,
            screen = Dimension(3840, 2160),
        )
        assertEquals(Dimension(1920, 1200), result)
    }

    @Test
    fun `tiny screen clamps both dimensions to fraction of screen`() {
        // 13" laptop at 1366x768 -- a 1920x1200 design intent would
        // exceed the screen entirely. Clamp leaves the user some
        // headroom (90% default) to drag the window edges.
        val result = computeSafeWindowMinSize(
            designWidthPx = 1920,
            designHeightPx = 1200,
            screen = Dimension(1366, 768),
        )
        assertEquals(Dimension((1366 * 0.9f).toInt(), (768 * 0.9f).toInt()), result)
    }

    @Test
    fun `clamp applies per-axis -- ultrawide retains design height, clamps width only when needed`() {
        // 1280-wide design intent fits inside a 3440x1440 ultrawide
        // (1280 < 3440 * 0.9). Height intent 1500 exceeds 1440 * 0.9
        // = 1296, so only the height axis is clamped.
        val result = computeSafeWindowMinSize(
            designWidthPx = 1280,
            designHeightPx = 1500,
            screen = Dimension(3440, 1440),
        )
        assertEquals(1280, result.width, "width fit under the cap; should not be clamped")
        assertEquals((1440 * 0.9f).toInt(), result.height, "height exceeded the cap; should be clamped")
    }

    @Test
    fun `maxScreenFraction = 1f never clamps unless screen is strictly smaller`() {
        // Edge case: with fraction = 1.0 the design intent passes
        // through unless the screen is genuinely smaller. Validates
        // the multiplication uses Float -> Int truncation correctly
        // at the boundary.
        val result = computeSafeWindowMinSize(
            designWidthPx = 1366,
            designHeightPx = 768,
            screen = Dimension(1366, 768),
            maxScreenFraction = 1.0f,
        )
        assertEquals(Dimension(1366, 768), result)
    }

    @Test
    fun `aggressive clamp fraction reduces minimum even on roomy screens`() {
        // A future caller could ask for a stricter clamp (say 0.5f) to
        // give the user even more drag room. Verify the parameter
        // actually drives the result, not just the screen size.
        val result = computeSafeWindowMinSize(
            designWidthPx = 1920,
            designHeightPx = 1200,
            screen = Dimension(3840, 2160),
            maxScreenFraction = 0.5f,
        )
        assertEquals(Dimension(1920, 1080), result, "design width fits under 50% cap; height does not")
    }
}

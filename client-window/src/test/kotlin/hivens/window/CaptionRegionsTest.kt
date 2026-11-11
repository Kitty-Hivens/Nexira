package hivens.window

import hivens.window.CaptionRegions.Area
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Where a press moves the window under the native frame: the bar's empty lane, minus what sits in it. */
class CaptionRegionsTest {

    @Test
    fun `the lane is the caption and what sits in it is not`() {
        val regions = CaptionRegions()
        val lane = Any()
        val widget = Any()
        // The lane read off a live window: from the breadcrumb to the right slot, the bar's height.
        regions.put(lane, Area(163f, 0f, 1177f, 44f), exclusion = false)
        regions.put(widget, Area(600f, 8f, 700f, 36f), exclusion = true)

        assertTrue(regions.isCaption(400, 20))
        assertFalse(regions.isCaption(650, 20), "a widget in the lane keeps its own clicks")
        assertFalse(regions.isCaption(100, 20), "the breadcrumb left of the lane is not the caption")
        assertFalse(regions.isCaption(400, 44), "the bottom edge of the bar is outside it")
    }

    @Test
    fun `a region that leaves the composition stops counting`() {
        val regions = CaptionRegions()
        val lane = Any()
        regions.put(lane, Area(0f, 0f, 100f, 44f), exclusion = false)
        regions.put(lane, null, exclusion = false)
        assertFalse(regions.isCaption(50, 20), "in edit mode the lane is a drop target, not a caption")
    }

    @Test
    fun `an empty slot in the lane excludes nothing`() {
        val regions = CaptionRegions()
        regions.put(Any(), Area(0f, 0f, 100f, 44f), exclusion = false)
        regions.put(Any(), Area(50f, 22f, 50f, 22f), exclusion = true)
        assertTrue(regions.isCaption(50, 22))
    }
}

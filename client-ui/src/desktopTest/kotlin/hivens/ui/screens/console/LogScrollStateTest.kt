package hivens.ui.screens.console

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogScrollStateTest {

    private fun state(content: Int, viewport: Int) = LogScrollState().apply {
        contentHeightPx = content
        viewportPx = viewport
    }

    @Test
    fun clampsToRange() {
        val s = state(content = 1000, viewport = 300) // maxOffset = 700
        assertEquals(700f, s.maxOffset)
        s.scrollTo(500f); assertEquals(500f, s.offsetPx)
        s.scrollTo(9999f); assertEquals(700f, s.offsetPx) // clamped high
        s.scrollTo(-50f); assertEquals(0f, s.offsetPx)    // clamped low
    }

    @Test
    fun scrollByReportsConsumedPixels() {
        val s = state(1000, 300)
        s.scrollTo(0f)
        assertEquals(700f, s.scrollBy(700f)) // full
        assertEquals(0f, s.scrollBy(200f))   // already at bottom, nothing consumed
        assertEquals(-700f, s.scrollBy(-9999f))
    }

    @Test
    fun followArmsAtBottomAndDisengagesOnScrollUp() {
        val s = state(1000, 300)
        assertTrue(s.follow) // a fresh view follows the tail
        s.scrollToBottom(); assertTrue(s.follow); assertEquals(700f, s.offsetPx)
        s.scrollBy(-50f); assertFalse(s.follow) // scrolled up -> paused
        assertFalse(s.atBottom)
        s.scrollBy(50f); assertTrue(s.follow)   // back to the bottom -> re-armed
        assertTrue(s.atBottom)
    }

    @Test
    fun stickIfFollowingRepinsOnlyWhenFollowing() {
        val s = state(1000, 300)
        s.scrollToBottom() // follow, offset 700
        s.contentHeightPx = 1200 // content grew; maxOffset now 900
        s.stickIfFollowing(); assertEquals(900f, s.offsetPx) // re-pinned to new bottom

        s.scrollTo(0f) // user scrolls up -> paused
        s.contentHeightPx = 1500
        s.stickIfFollowing(); assertEquals(0f, s.offsetPx) // stays put
    }

    @Test
    fun reclampPreservesFollow() {
        val s = state(1000, 300)
        s.scrollToBottom()
        s.viewportPx = 500 // viewport grew; maxOffset now 500
        s.reclamp(); assertEquals(500f, s.offsetPx) // still pinned to bottom
    }

    @Test
    fun contentShorterThanViewportPinsToZero() {
        val s = state(content = 200, viewport = 300)
        assertEquals(0f, s.maxOffset)
        assertTrue(s.atBottom) // nothing to scroll -> always following
        s.scrollTo(100f); assertEquals(0f, s.offsetPx)
    }

    @Test
    fun shiftByKeepsVisiblePositionAndDoesNotArmFollow() {
        val s = state(1000, 300)
        s.scrollTo(400f)
        assertFalse(s.follow)
        s.shiftBy(120f) // content above grew by 120px
        assertEquals(520f, s.offsetPx)
        assertFalse(s.follow) // history paging is not tail-following
    }

    @Test
    fun scrollbarAdapterReflectsState() {
        val s = state(1000, 300)
        s.scrollTo(250f)
        val a = s.scrollbarAdapter()
        assertEquals(250.0, a.scrollOffset)
        assertEquals(1000.0, a.contentSize)
        assertEquals(300.0, a.viewportSize)
    }
}

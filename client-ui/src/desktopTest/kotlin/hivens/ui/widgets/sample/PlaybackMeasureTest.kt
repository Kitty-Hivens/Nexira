package hivens.ui.widgets.sample

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The timecode every player prints.
 *
 * It formatted minutes and seconds alone, so an hour read `60:00`. Nothing caught
 * it because every card had only ever been drawn with a four-minute track behind
 * it, and a duration is exactly the sort of input the corpus decides rather than
 * the developer: a set, a podcast or a stitched compilation is over an hour.
 */
class PlaybackMeasureTest {

    @Test
    fun `under a minute keeps a zero minutes field`() {
        assertEquals("0:00", formatMs(0L))
        assertEquals("0:09", formatMs(9_400L))
        assertEquals("0:59", formatMs(59_999L))
    }

    @Test
    fun `an ordinary track is minutes and seconds, with no hours field`() {
        assertEquals("1:00", formatMs(60_000L))
        assertEquals("4:55", formatMs(295_000L))
        assertEquals("9:59", formatMs(599_000L))
        assertEquals("59:59", formatMs(3_599_000L))
    }

    @Test
    fun `an hour rolls into an hours field instead of counting past sixty`() {
        assertEquals("1:00:00", formatMs(3_600_000L))
        assertEquals("1:30:00", formatMs(5_400_000L))
        assertEquals("2:05:07", formatMs(7_507_000L))
    }

    @Test
    fun `a negative position reads as the start rather than as a negative clock`() {
        assertEquals("0:00", formatMs(-1L))
        assertEquals("0:00", formatMs(-90_000L))
    }
}

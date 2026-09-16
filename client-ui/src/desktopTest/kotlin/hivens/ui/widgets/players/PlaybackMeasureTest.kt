package hivens.ui.widgets.players

import hivens.ui.audio.PlaybackState
import java.nio.file.Path
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

    @Test
    fun `the clock and the bar read the same field in every state`() {
        // They did not. Ready was answered with a hard zero on the assumption that
        // a loaded-but-unplayed track sits at its start, while the bar beside it
        // read the real position out of the same object: scrub a track before
        // pressing play, or into one that had finished, and the bar moved to where
        // the press landed while the clock stayed at 0:00.
        val file = Path.of("/music/track.flac")
        val states = listOf(
            PlaybackState.Playing(file, positionMs = 45_000L, durationMs = 180_000L),
            PlaybackState.Paused(file, positionMs = 45_000L, durationMs = 180_000L),
            PlaybackState.Ready(file, positionMs = 45_000L, durationMs = 180_000L),
        )
        for (state in states) {
            assertEquals("0:45", elapsedLabel(state), "the clock in $state")
            assertEquals(0.25f, progressFraction(state), "the bar in $state")
        }
    }

    @Test
    fun `nothing loaded prints no clock at all`() {
        // Empty rather than 0:00, so a card can put its own dashes there: a zero is
        // a position in a track, and there is no track.
        assertEquals("", elapsedLabel(PlaybackState.Idle))
        assertEquals("", totalLabel(PlaybackState.Idle))
        assertEquals(0f, progressFraction(PlaybackState.Idle))
    }
}

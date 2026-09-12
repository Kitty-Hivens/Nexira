package hivens.ui.audio

import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Where the queue goes at its edges.
 *
 * This is the whole of #660's behaviour, and it cannot be tested through the
 * engine: opening a track needs FFmpeg natives that are not on the test path, and
 * "does the next track start" is a question about arithmetic rather than about
 * decoding. So the arithmetic is pure, the way [mapPlaybackState] already is, and
 * this is what pins it.
 *
 * The case that matters most is the quiet one: [RepeatMode.Off] on a queue of
 * three must still step from the first entry to the second. The mode governs what
 * happens at the END of the queue, not between its entries, and reading it as
 * "off means do not advance" turns a queue back into a single-file player.
 */
class QueueSteppingTest {

    private fun p(name: String): Path = Paths.get("/music/$name.mp3")

    // ── Forward ─────────────────────────────────────────────────────────────

    @Test
    fun `repeat off still walks the queue and stops at its end`() {
        assertEquals(1, nextQueueIndex(size = 3, index = 0, repeat = RepeatMode.Off))
        assertEquals(2, nextQueueIndex(size = 3, index = 1, repeat = RepeatMode.Off))
        assertNull(nextQueueIndex(size = 3, index = 2, repeat = RepeatMode.Off), "the end is the end")
    }

    @Test
    fun `repeat queue wraps the end round to the front`() {
        assertEquals(0, nextQueueIndex(size = 3, index = 2, repeat = RepeatMode.Queue))
        assertEquals(1, nextQueueIndex(size = 3, index = 0, repeat = RepeatMode.Queue))
    }

    @Test
    fun `repeat queue on a single entry is that entry again`() {
        // This is what looping one track used to do, and the mode still says it.
        assertEquals(0, nextQueueIndex(size = 1, index = 0, repeat = RepeatMode.Queue))
    }

    @Test
    fun `repeat off on a single entry stops`() {
        assertNull(nextQueueIndex(size = 1, index = 0, repeat = RepeatMode.Off))
    }

    @Test
    fun `repeat one answers with the current entry under either direction`() {
        assertEquals(1, nextQueueIndex(size = 3, index = 1, repeat = RepeatMode.One))
        assertEquals(1, previousQueueIndex(size = 3, index = 1, repeat = RepeatMode.One))
    }

    @Test
    fun `an empty queue and an unloaded player step nowhere`() {
        assertNull(nextQueueIndex(size = 0, index = -1, repeat = RepeatMode.Queue))
        assertNull(previousQueueIndex(size = 0, index = -1, repeat = RepeatMode.Queue))
        assertNull(nextQueueIndex(size = 3, index = -1, repeat = RepeatMode.Queue))
    }

    // ── Backward ────────────────────────────────────────────────────────────

    @Test
    fun `skip back stops at the front unless the queue wraps`() {
        assertEquals(0, previousQueueIndex(size = 3, index = 1, repeat = RepeatMode.Off))
        assertNull(previousQueueIndex(size = 3, index = 0, repeat = RepeatMode.Off))
        assertEquals(2, previousQueueIndex(size = 3, index = 0, repeat = RepeatMode.Queue))
    }

    // ── Removal ─────────────────────────────────────────────────────────────

    @Test
    fun `dropping an entry before the loaded one shifts it without touching the engine`() {
        val queue = listOf(p("a"), p("b"), p("c"))
        val edit = removeFromQueue(queue, index = 2, removeAt = 0)!!
        assertEquals(listOf(p("b"), p("c")), edit.queue)
        assertEquals(1, edit.index, "the loaded entry moved down a slot")
        assertFalse(edit.reload, "what is playing did not change, so the engine must not")
    }

    @Test
    fun `dropping an entry after the loaded one changes nothing else`() {
        val queue = listOf(p("a"), p("b"), p("c"))
        val edit = removeFromQueue(queue, index = 0, removeAt = 2)!!
        assertEquals(listOf(p("a"), p("b")), edit.queue)
        assertEquals(0, edit.index)
        assertFalse(edit.reload)
    }

    @Test
    fun `dropping the loaded entry hands the engine to what slid into its slot`() {
        val queue = listOf(p("a"), p("b"), p("c"))
        val edit = removeFromQueue(queue, index = 1, removeAt = 1)!!
        assertEquals(listOf(p("a"), p("c")), edit.queue)
        assertEquals(1, edit.index)
        assertTrue(edit.reload)
        assertEquals(p("c"), edit.queue[edit.index])
    }

    @Test
    fun `dropping the loaded entry at the tail falls back onto the new tail`() {
        val queue = listOf(p("a"), p("b"), p("c"))
        val edit = removeFromQueue(queue, index = 2, removeAt = 2)!!
        assertEquals(listOf(p("a"), p("b")), edit.queue)
        assertEquals(1, edit.index, "there is nothing after it, so it takes what is before")
        assertTrue(edit.reload)
    }

    @Test
    fun `emptying the queue leaves nothing loaded`() {
        val edit = removeFromQueue(listOf(p("a")), index = 0, removeAt = 0)!!
        assertTrue(edit.queue.isEmpty())
        assertEquals(-1, edit.index)
        assertFalse(edit.reload, "there is nothing to load, so nothing is reloaded")
    }

    @Test
    fun `a removal that names an entry the queue does not have is refused`() {
        val queue = listOf(p("a"), p("b"))
        assertNull(removeFromQueue(queue, index = 0, removeAt = 2))
        assertNull(removeFromQueue(queue, index = 0, removeAt = -1))
        assertNull(removeFromQueue(emptyList(), index = -1, removeAt = 0))
    }
}

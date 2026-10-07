package hivens.ui.editor

import hivens.widget.model.LayoutGraph
import hivens.widget.model.SlotContent
import hivens.widget.model.SlotId
import hivens.widget.model.SurfaceId
import hivens.widget.model.SurfaceLayout
import hivens.widget.model.WidgetInstance
import hivens.widget.model.WidgetKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The undo stack, and the one thing it has to get right: a drag is one step.
 *
 * Free placement writes an offset on every frame, so the difference between
 * coalescing and not is the difference between Ctrl+Z escaping a gesture and
 * Ctrl+Z having to be held down sixty times to escape the same gesture.
 */
class EditHistoryTest {

    private var clock = 0L
    private fun history(limit: Int = 64) = EditHistory(limit = limit, now = { clock })

    /** Graphs that differ by the number of widgets in one slot, so equality is cheap to reason about. */
    private fun graph(widgets: Int): LayoutGraph = LayoutGraph(
        surfaces = mapOf(
            SurfaceId("home") to SurfaceLayout(
                mapOf(
                    SlotId("main") to SlotContent(
                        widgets = (0 until widgets).map {
                            WidgetInstance(kind = WidgetKind("w"), instanceId = "w$it")
                        },
                    ),
                ),
            ),
        ),
    )

    @Test
    fun `an edit can be taken back`() {
        val h = history()
        val before = graph(1)
        val after = graph(2)
        h.record(key = null, before = before, after = after)

        assertTrue(h.canUndo)
        assertSame(before, h.undo(after))
        assertFalse(h.canUndo)
    }

    @Test
    fun `a run under one key is one step`() {
        val h = history()
        val start = graph(0)
        // Sixty frames of a drag, each one a new graph.
        var current = start
        repeat(60) { frame ->
            val next = graph(frame + 1)
            h.record("offset:w0", current, next)
            current = next
            clock += 16
        }

        assertEquals(1, h.depth, "a drag is one thing the person did")
        assertSame(start, h.undo(current), "undo goes back to where the gesture began")
    }

    @Test
    fun `a gap longer than the window starts a new step`() {
        val h = history()
        h.record("offset:w0", graph(0), graph(1))
        clock += 5_000
        h.record("offset:w0", graph(1), graph(2))

        assertEquals(2, h.depth, "a pause long enough to be persisted separately is long enough to undo separately")
    }

    @Test
    fun `a different widget does not merge with the one before it`() {
        val h = history()
        h.record("offset:w0", graph(0), graph(1))
        h.record("offset:w1", graph(1), graph(2))

        assertEquals(2, h.depth)
    }

    @Test
    fun `a structural edit never merges`() {
        val h = history()
        // Two drops in the same instant. Null key, so no coalescing whatever the clock says.
        h.record(key = null, before = graph(0), after = graph(1))
        h.record(key = null, before = graph(1), after = graph(2))

        assertEquals(2, h.depth)
    }

    @Test
    fun `a transform that changed nothing is not a step`() {
        val h = history()
        val same = graph(1)
        h.record(key = null, before = same, after = same)

        assertFalse(h.canUndo, "undoing a refused transform would look like the key did nothing")
    }

    @Test
    fun `redo puts back what undo took, until a new edit branches away`() {
        val h = history()
        val before = graph(1)
        val after = graph(2)
        h.record(key = null, before = before, after = after)

        assertSame(before, h.undo(after))
        assertTrue(h.canRedo)
        assertSame(after, h.redo(before))
        assertFalse(h.canRedo)

        // Undo, then do something else: the branch that was undone is gone.
        h.undo(after)
        h.record(key = null, before = before, after = graph(3))
        assertFalse(h.canRedo)
    }

    @Test
    fun `a run cannot continue across an undo`() {
        val h = history()
        h.record("offset:w0", graph(0), graph(1))
        h.undo(graph(1))
        // Same key, same instant, but the run was broken by the undo.
        h.record("offset:w0", graph(1), graph(2))

        assertEquals(1, h.depth)
        assertTrue(h.canUndo, "the edit after an undo is its own step, not a continuation of the one undone")
    }

    @Test
    fun `the stack is bounded and drops the oldest`() {
        val h = history(limit = 3)
        repeat(10) { i ->
            h.record(key = null, before = graph(i), after = graph(i + 1))
        }

        assertEquals(3, h.depth)
        // The three kept are the newest three, so the first undo lands on the graph
        // the tenth edit started from.
        assertEquals(graph(9), h.undo(graph(10)))
    }

    @Test
    fun `nothing to undo or redo is a no-op rather than a throw`() {
        val h = history()
        assertNull(h.undo(graph(1)))
        assertNull(h.redo(graph(1)))
    }
}

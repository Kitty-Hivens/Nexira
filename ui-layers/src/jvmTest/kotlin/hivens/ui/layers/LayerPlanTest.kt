package hivens.ui.layers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The order layers stack in, as a pure function of what is shown. The host draws
 * and hit-tests in exactly this order, so this is where the rules live.
 */
class LayerPlanTest {

    private fun resident(
        stamp: Long,
        parent: LayerResident? = null,
        modal: Boolean = false,
        staysUsable: Boolean = false,
        pinnedTop: Boolean = false,
    ) = LayerResident(parent).also {
        it.stamp = stamp
        it.modal = modal
        it.staysUsable = staysUsable
        it.pinnedTop = pinnedTop
    }

    @Test
    fun `the most recently shown is on top`() {
        val a = resident(1)
        val b = resident(2)
        val c = resident(3)
        assertEquals(listOf(a, b, c), planOf(listOf(c, a, b)).order)
    }

    // A sheet hidden and shown again while a menu opened from it stays open would
    // otherwise come back over its own menu.
    @Test
    fun `a child stays above its parent when the parent appears again later`() {
        val sheet = resident(1)
        val menu = resident(2, parent = sheet)
        sheet.stamp = 3
        assertEquals(listOf(sheet, menu), planOf(listOf(menu, sheet)).order)
    }

    @Test
    fun `a family stacks as one, by its most recent member`() {
        val sheet = resident(1)
        val other = resident(2)
        val menu = resident(3, parent = sheet)
        assertEquals(listOf(other, sheet, menu), planOf(listOf(sheet, other, menu)).order, "the newest thing is on top")

        val later = resident(4)
        assertEquals(listOf(other, sheet, menu, later), planOf(listOf(sheet, other, menu, later)).order)
    }

    @Test
    fun `a modal child stands above a later sibling of its parent and gates it`() {
        val sheet = resident(1)
        val menu = resident(2)
        val confirm = resident(3, parent = sheet, modal = true)
        val plan = planOf(listOf(sheet, menu, confirm))
        assertEquals(listOf(menu, sheet, confirm), plan.order)
        assertFalse(plan.takesInput(plan.rankOf(menu)))
        assertFalse(plan.takesInput(plan.rankOf(sheet)), "the sheet waits for its own confirmation")
    }

    @Test
    fun `under a modal a usable layer's children stay above it and usable`() {
        val pill = resident(1, staysUsable = true)
        val pillMenu = resident(2, parent = pill)
        val other = resident(3)
        val dialog = resident(4, modal = true)
        val plan = planOf(listOf(pill, pillMenu, other, dialog))
        assertEquals(listOf(other, pill, pillMenu, dialog), plan.order)
        assertTrue(plan.takesInput(plan.rankOf(pillMenu)))
        assertFalse(plan.takesInput(plan.rankOf(other)))
    }

    @Test
    fun `a pinned layer stays above every layer shown after it`() {
        val debug = resident(1, pinnedTop = true)
        val later = resident(2)
        val modal = resident(3, modal = true)
        assertEquals(listOf(later, modal, debug), planOf(listOf(debug, later, modal)).order)
    }

    @Test
    fun `a child of a parent that is not shown stands among the roots`() {
        val gone = resident(1)
        val orphan = resident(2, parent = gone)
        val root = resident(3)
        assertEquals(listOf(orphan, root), planOf(listOf(root, orphan)).order)
    }

    @Test
    fun `without a modal nothing is gated`() {
        val plan = planOf(listOf(resident(1), resident(2)))
        assertEquals(-1, plan.gate)
        assertNull(plan.modal)
        assertTrue(plan.takesInput(-1), "the window's own content takes input")
    }

    // The pill's Cancel has to stay reachable under a dialog. Input and drawing share
    // one order, so a usable layer goes above the gate and the layers it blocks.
    @Test
    fun `under a modal the usable layers are lifted above the gate and the rest stay below it`() {
        val pill = resident(1, staysUsable = true)
        val menu = resident(2)
        val dialog = resident(3, modal = true)
        val tooltip = resident(4)
        val plan = planOf(listOf(pill, menu, dialog, tooltip))

        assertEquals(listOf(menu, pill, dialog, tooltip), plan.order)
        assertEquals(1, plan.gate)
        assertSame(dialog, plan.modal)
        assertFalse(plan.takesInput(plan.rankOf(null)), "the window's own content is blocked")
        assertFalse(plan.takesInput(plan.rankOf(menu)))
        assertTrue(plan.takesInput(plan.rankOf(pill)))
        assertTrue(plan.takesInput(plan.rankOf(dialog)))
        assertTrue(plan.takesInput(plan.rankOf(tooltip)), "what opened above the modal is not under it")
    }

    @Test
    fun `the topmost modal is the one that gates`() {
        val first = resident(1, modal = true)
        val second = resident(2, modal = true)
        val plan = planOf(listOf(first, second))
        assertSame(second, plan.modal)
        assertFalse(plan.takesInput(plan.rankOf(first)), "a modal under another modal is blocked like anything else")
    }

    @Test
    fun `z is the rank in the order and never the stamp`() {
        val a = resident(1_000_000_000_000L)
        val b = resident(1_000_000_000_001L)
        val plan = planOf(listOf(b, a))
        assertEquals(0, plan.rankOf(a))
        assertEquals(1, plan.rankOf(b))
    }
}

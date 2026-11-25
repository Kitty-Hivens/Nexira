package hivens.ui.host

import hivens.widget.model.CompositionName
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Which compositions are open in a window of their own, as the host keeps them. */
class CompositionWindowsTest {

    private val console = CompositionName("console")
    private val windows = CompositionWindows { it == console }

    @Test
    fun `a composition the document has opens once and closes`() {
        assertTrue(windows.show(console))
        assertEquals(setOf(console), windows.open.value)
        windows.close(console)
        assertEquals(emptySet(), windows.open.value)
        windows.close(console)
        assertEquals(emptySet(), windows.open.value, "closing what is closed changes nothing")
    }

    @Test
    fun `after a crash every window is closed, and opens again when asked`() {
        windows.show(console)
        windows.closeAfterCrash()
        assertEquals(emptySet(), windows.open.value)
        assertTrue(windows.show(console))
        assertEquals(setOf(console), windows.open.value)
    }

    @Test
    fun `one the document does not have opens nothing`() {
        assertFalse(windows.show(CompositionName("nowhere")))
        assertEquals(emptySet(), windows.open.value)
    }

    @Test
    fun `one asked for again while open is brought to the front and opened once`() = runBlocking {
        val raised = ArrayList<CompositionName>()
        val listening = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { windows.raises.toList(raised) }
        windows.show(console)
        assertEquals(emptyList(), raised, "a window opened is not raised as well")
        windows.show(console)
        assertEquals(listOf(console), raised)
        assertEquals(setOf(console), windows.open.value)
        listening.cancel()
    }
}

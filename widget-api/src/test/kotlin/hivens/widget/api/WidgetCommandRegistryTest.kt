package hivens.widget.api

import hivens.widget.model.CommandKey
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WidgetCommandRegistryTest {

    private val clearKey = CommandKey<Unit>("clear")
    private val volumeKey = CommandKey<Int>("volume")

    @Test
    fun `register then dispatch runs the command`() {
        val reg = WidgetCommandRegistry()
        var ran = false
        reg.register(clearKey, command { ran = true })
        reg.dispatch(clearKey)
        assertTrue(ran)
    }

    @Test
    fun `dispatch passes the payload through`() {
        val reg = WidgetCommandRegistry()
        var seen = -1
        reg.register(volumeKey, command { level -> seen = level })
        reg.dispatch(volumeKey, 42)
        assertEquals(42, seen)
    }

    @Test
    fun `dispatch throws for an unregistered key`() {
        val reg = WidgetCommandRegistry()
        val ex = assertFailsWith<IllegalStateException> { reg.dispatch(clearKey) }
        assertTrue("clear" in (ex.message ?: ""), "error names the missing id")
    }

    @Test
    fun `register rejects a duplicate id`() {
        val reg = WidgetCommandRegistry()
        reg.register(clearKey, command { })
        val ex = assertFailsWith<IllegalArgumentException> {
            reg.register(CommandKey<Unit>("clear"), command { })
        }
        assertTrue("clear" in (ex.message ?: ""))
    }

    @Test
    fun `find returns the command or null`() {
        val reg = WidgetCommandRegistry()
        val cmd = command<Unit> { }
        reg.register(clearKey, cmd)
        assertSame(cmd, reg.find(clearKey))
        assertNull(reg.find(volumeKey))
    }

    @Test
    fun `keys lists the registered ids`() {
        val reg = WidgetCommandRegistry()
        reg.register(clearKey, command { })
        reg.register(volumeKey, command { })
        assertEquals(setOf("clear", "volume"), reg.keys())
    }

    @Test
    fun `suspendCommand runs the suspend block fire-and-forget on the scope`() = runTest {
        val reg = WidgetCommandRegistry()
        var ran = false
        reg.register(clearKey, suspendCommand(this) { ran = true })
        reg.dispatch(clearKey)
        // The block is launched fire-and-forget; let the test scheduler drain it.
        advanceUntilIdle()
        assertTrue(ran)
    }
}

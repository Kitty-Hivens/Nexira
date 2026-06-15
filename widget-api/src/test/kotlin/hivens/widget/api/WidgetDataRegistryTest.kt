package hivens.widget.api

import hivens.widget.model.SourceKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WidgetDataRegistryTest {

    private val intKey = SourceKey<Int>("count")
    private val strKey = SourceKey<String>("label")

    @Test
    fun `register then get returns the source`() {
        val reg = WidgetDataRegistry()
        val src = flowSource(MutableStateFlow(7))
        reg.register(intKey, src)
        assertSame(src, reg.get(intKey))
    }

    @Test
    fun `find returns the source or null`() {
        val reg = WidgetDataRegistry()
        val src = flowSource(MutableStateFlow(7))
        reg.register(intKey, src)
        assertSame(src, reg.find(intKey))
        assertNull(reg.find(strKey))
    }

    @Test
    fun `get throws for an unregistered key`() {
        val reg = WidgetDataRegistry()
        val ex = assertFailsWith<IllegalStateException> { reg.get(intKey) }
        assertTrue("count" in (ex.message ?: ""), "error names the missing id")
    }

    @Test
    fun `register rejects a duplicate id`() {
        val reg = WidgetDataRegistry()
        reg.register(intKey, flowSource(MutableStateFlow(1)))
        val ex = assertFailsWith<IllegalArgumentException> {
            reg.register(SourceKey<Int>("count"), flowSource(MutableStateFlow(2)))
        }
        assertTrue("count" in (ex.message ?: ""))
    }

    @Test
    fun `keys lists the registered ids`() {
        val reg = WidgetDataRegistry()
        reg.register(intKey, flowSource(MutableStateFlow(1)))
        reg.register(strKey, flowSource(MutableStateFlow("x")))
        assertEquals(setOf("count", "label"), reg.keys())
    }

    @Test
    fun `current reads the live value and flow exposes the raw source (rule-engine path)`() {
        val reg = WidgetDataRegistry()
        val flow = MutableStateFlow(1)
        reg.register(intKey, flowSource(flow))
        assertEquals(1, reg.current(intKey))
        flow.value = 42
        assertEquals(42, reg.current(intKey), "current() tracks the source's StateFlow")
        assertSame(flow, reg.flow(intKey))
    }
}

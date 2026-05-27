package hivens.widget.api

import hivens.widget.model.WidgetService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WidgetServiceRegistryTest {

    private interface SampleService : WidgetService {
        val tag: String
    }

    private interface OtherService : WidgetService

    private class SampleImpl(override val tag: String) : SampleService
    private class OtherImpl : OtherService

    @Test
    fun `register then first returns the registered impl`() {
        val reg = WidgetServiceRegistry()
        val impl = SampleImpl("a")
        reg.register(SampleService::class, "instance-a", impl)
        assertSame(impl, reg.first(SampleService::class))
    }

    @Test
    fun `byInstance returns the right impl for the right id`() {
        val reg = WidgetServiceRegistry()
        val a = SampleImpl("a")
        val b = SampleImpl("b")
        reg.register(SampleService::class, "id-a", a)
        reg.register(SampleService::class, "id-b", b)
        assertSame(a, reg.byInstance(SampleService::class, "id-a"))
        assertSame(b, reg.byInstance(SampleService::class, "id-b"))
        assertNull(reg.byInstance(SampleService::class, "missing"))
    }

    @Test
    fun `all returns every registered impl sorted by instanceId`() {
        val reg = WidgetServiceRegistry()
        // Register in reverse-alphabetical order; expect sorted output.
        reg.register(SampleService::class, "z-id", SampleImpl("z"))
        reg.register(SampleService::class, "a-id", SampleImpl("a"))
        reg.register(SampleService::class, "m-id", SampleImpl("m"))
        val list = reg.all(SampleService::class)
        assertEquals(listOf("a", "m", "z"), list.map { it.tag })
    }

    @Test
    fun `first picks the instanceId-sorted-first impl deterministically`() {
        val reg = WidgetServiceRegistry()
        reg.register(SampleService::class, "z-id", SampleImpl("z"))
        reg.register(SampleService::class, "a-id", SampleImpl("a"))
        reg.register(SampleService::class, "m-id", SampleImpl("m"))
        assertEquals("a", reg.first(SampleService::class)?.tag)
    }

    @Test
    fun `unregister removes the impl from all lookup forms`() {
        val reg = WidgetServiceRegistry()
        val impl = SampleImpl("a")
        reg.register(SampleService::class, "instance-a", impl)
        reg.unregister(SampleService::class, "instance-a")
        assertNull(reg.first(SampleService::class))
        assertNull(reg.byInstance(SampleService::class, "instance-a"))
        assertTrue(reg.all(SampleService::class).isEmpty())
    }

    @Test
    fun `unregister of unknown id is a no-op`() {
        val reg = WidgetServiceRegistry()
        reg.register(SampleService::class, "instance-a", SampleImpl("a"))
        reg.unregister(SampleService::class, "does-not-exist")
        assertEquals(1, reg.all(SampleService::class).size)
    }

    @Test
    fun `services of different KClasses do not collide`() {
        val reg = WidgetServiceRegistry()
        val sample = SampleImpl("a")
        val other = OtherImpl()
        // Same instanceId used across two service kinds -- common case
        // when one widget exposes multiple contracts.
        reg.register(SampleService::class, "shared-id", sample)
        reg.register(OtherService::class, "shared-id", other)
        assertSame(sample, reg.first(SampleService::class))
        assertSame(other, reg.first(OtherService::class))
    }

    @Test
    fun `registering same id twice replaces the impl`() {
        val reg = WidgetServiceRegistry()
        reg.register(SampleService::class, "id", SampleImpl("first"))
        reg.register(SampleService::class, "id", SampleImpl("second"))
        assertEquals("second", reg.first(SampleService::class)?.tag)
        assertEquals(1, reg.all(SampleService::class).size)
    }

    @Test
    fun `all returns empty list when no provider registered for that kind`() {
        val reg = WidgetServiceRegistry()
        reg.register(OtherService::class, "id", OtherImpl())
        assertTrue(reg.all(SampleService::class).isEmpty())
    }

    @Test
    fun `register rejects an impl that does not implement the declared service kind`() {
        val reg = WidgetServiceRegistry()
        val ex = assertFailsWith<IllegalArgumentException> {
            // A plugin / hand-written provider could pass the wrong
            // KClass; the registry must catch the mismatch loud and
            // local, not defer it to a ClassCastException on the
            // consumer's first<T>() call.
            reg.register(SampleService::class, "id", OtherImpl())
        }
        assertTrue("SampleService" in (ex.message ?: ""))
    }
}

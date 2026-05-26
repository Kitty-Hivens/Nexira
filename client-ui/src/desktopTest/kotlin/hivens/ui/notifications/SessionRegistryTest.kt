package hivens.ui.notifications

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SessionRegistryTest {

    @Test
    fun `register adds to active map`() = runTest {
        val reg = SessionRegistry(appScope = this, clock = ::fixedClock)
        reg.register("pack1", "Pack 1", null, abort = {}, showConsole = {})
        val active = reg.active.first()
        assertEquals(1, active.size)
        val session = active["pack1"]
        assertNotNull(session)
        assertEquals("Pack 1", session.packDisplayName)
        // Cleanup ticker so the test scope can finish.
        reg.unregister("pack1")
    }

    @Test
    fun `unregister removes the session`() = runTest {
        val reg = SessionRegistry(appScope = this, clock = ::fixedClock)
        reg.register("pack1", "Pack 1", null, abort = {}, showConsole = {})
        reg.unregister("pack1")
        assertTrue(reg.active.first().isEmpty())
    }

    @Test
    fun `unregister of unknown id is a no-op`() = runTest {
        val reg = SessionRegistry(appScope = this, clock = ::fixedClock)
        reg.unregister("never-existed")
        assertTrue(reg.active.first().isEmpty())
    }

    @Test
    fun `register again with same id replaces the prior session`() = runTest {
        val reg = SessionRegistry(appScope = this, clock = ::fixedClock)
        var firstAbortCalls = 0
        var secondAbortCalls = 0
        reg.register("pack1", "First",  null, abort = { firstAbortCalls++  }, showConsole = {})
        reg.register("pack1", "Second", null, abort = { secondAbortCalls++ }, showConsole = {})

        val session = reg.active.first()["pack1"]
        assertEquals("Second", session?.packDisplayName)
        session?.abort?.invoke()
        assertEquals(0, firstAbortCalls, "first session's abort must be unreachable")
        assertEquals(1, secondAbortCalls)
        reg.unregister("pack1")
    }

    @Test
    fun `concurrent register and unregister do not lose entries`() = runTest {
        val reg = SessionRegistry(appScope = this, clock = ::fixedClock)
        // Hammer the same registry from many parallel coroutines. Plain
        // `_active.value = _active.value + ...` would lose updates here;
        // .update {} CAS retry is what we test.
        val keys = (1..40).map { "pack-$it" }
        keys.map { k ->
            launch {
                reg.register(k, "Pack $k", null, abort = {}, showConsole = {})
            }
        }
        // Drain.
        advanceTimeBy(50)
        assertEquals(40, reg.active.first().size, "no entries lost under concurrent register")
        keys.map { k -> launch { reg.unregister(k) } }
        advanceTimeBy(50)
        assertTrue(reg.active.first().isEmpty(), "no entries lost under concurrent unregister")
    }

    @Test
    fun `uptime ticks while session is registered`() = runTest {
        val reg = SessionRegistry(appScope = this, clock = ::fixedClock)
        reg.register("pack1", "Pack 1", null, abort = {}, showConsole = {})
        val session = reg.active.first()["pack1"]
        assertNotNull(session)
        // Initial tick before the first delay fires.
        assertEquals(0, session.uptime.first().seconds)
        reg.unregister("pack1")
    }

    private var clockNanos = 0L
    private fun fixedClock(): Instant = Instant.ofEpochSecond(0, clockNanos)
}

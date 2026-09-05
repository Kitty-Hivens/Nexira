package hivens.core.activity

import hivens.test.TestClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The three properties this covers are the registry's contract rather than its
 * implementation: the surface reading it is permanent chrome, so leaked text,
 * an unthrottled feed and unbounded growth all land somewhere a toast never
 * reached.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ActivityRegistryTest {

    private fun running(done: Long, total: Long = 100, detail: String? = null) =
        ActivityPhase.Running(done, total, detail)

    // ── Redaction ──────────────────────────────────────────────────────

    @Test
    fun `failure reason is redacted on the way in`() = runTest {
        val reg = ActivityRegistry(scope = this, clock = TestClock())
        reg.report(
            key = "install:A", kind = ActivityKind.Install, title = "Industrial",
            phase = ActivityPhase.Failed(
                "GET https://smrt.hivens.dev/v1/packs/A failed: 401 body={\"accessToken\":\"abc123def456ghi\"}"
            ),
        )
        val reason = (reg.activities.value.single().phase as ActivityPhase.Failed).reason
        assertNotNull(reason)
        assertTrue("<redacted>" in reason, "token should be masked, got: $reason")
        assertTrue("abc123def456ghi" !in reason, "raw token survived: $reason")
    }

    @Test
    fun `running detail is redacted on the way in`() = runTest {
        val reg = ActivityRegistry(scope = this, clock = TestClock())
        reg.report(
            key = "install:A", kind = ActivityKind.Install, title = "Industrial",
            phase = running(1, 10, detail = "sessionToken: Zm9vYmFyYmF6cXV4"),
        )
        val detail = (reg.activities.value.single().phase as ActivityPhase.Running).detail
        assertTrue(detail != null && "<redacted>" in detail, "got: $detail")
    }

    @Test
    fun `title is left alone`() = runTest {
        val reg = ActivityRegistry(scope = this, clock = TestClock())
        // A display name a person chose. Mangling it would be a visible defect
        // protecting nothing -- the value is not machine output.
        reg.report("i", ActivityKind.Install, "password of the deep", running(1))
        assertEquals("password of the deep", reg.activities.value.single().title)
    }

    // ── Rate limiting ──────────────────────────────────────────────────

    @Test
    fun `a burst of progress publishes at most once per interval`() = runTest {
        val reg = ActivityRegistry(scope = this, clock = TestClock(), minPublishIntervalMs = 250)

        // First report of a key is a shape change: published at once.
        reg.report("i", ActivityKind.Install, "Industrial", running(1))
        runCurrent()
        assertEquals(1L, done(reg))

        repeat(50) { reg.report("i", ActivityKind.Install, "Industrial", running(it + 2L)) }
        runCurrent()
        assertEquals(1L, done(reg), "progress-only ticks must not reach the view yet")

        advanceTimeBy(300); runCurrent()
        assertEquals(51L, done(reg), "the latest value lands on the next tick")
    }

    @Test
    fun `a terminal phase is never held behind the throttle`() = runTest {
        val reg = ActivityRegistry(scope = this, clock = TestClock(), minPublishIntervalMs = 250)
        reg.report("i", ActivityKind.Install, "Industrial", running(1))
        runCurrent()
        reg.report("i", ActivityKind.Install, "Industrial", running(2))
        runCurrent()
        assertEquals(1L, done(reg))

        reg.report("i", ActivityKind.Install, "Industrial", ActivityPhase.Failed("boom"))
        runCurrent()
        assertTrue(reg.activities.value.single().phase is ActivityPhase.Failed, "failure must not wait")
    }

    @Test
    fun `an appearing activity publishes at once even mid-throttle`() = runTest {
        val reg = ActivityRegistry(scope = this, clock = TestClock(), minPublishIntervalMs = 250)
        reg.report("a", ActivityKind.Install, "A", running(1)); runCurrent()
        reg.report("a", ActivityKind.Install, "A", running(2)); runCurrent()
        reg.report("b", ActivityKind.Sync, "B", running(1)); runCurrent()
        assertEquals(2, reg.activities.value.size, "a new activity is news, not a tick")
    }

    // ── Caps and eviction ──────────────────────────────────────────────

    @Test
    fun `settled entries are evicted after the hold, failures are not`() = runTest {
        val clock = TestClock()
        val reg = ActivityRegistry(scope = this, clock = clock, terminalHoldMs = 4_000)

        reg.report("ok", ActivityKind.Install, "A", ActivityPhase.Succeeded)
        reg.report("bad", ActivityKind.Install, "B", ActivityPhase.Failed("boom"))
        runCurrent()
        assertEquals(2, reg.activities.value.size)

        advanceTimeBy(5_000); runCurrent()
        val left = reg.activities.value
        assertEquals(1, left.size, "the success should be gone")
        assertEquals("bad", left.single().key, "a failure is the only record of a problem")
    }

    @Test
    fun `a re-run key is not evicted by its previous hold`() = runTest {
        val clock = TestClock()
        val reg = ActivityRegistry(scope = this, clock = clock, terminalHoldMs = 4_000)

        reg.report("i", ActivityKind.Install, "A", ActivityPhase.Succeeded); runCurrent()
        clock.advance(10)
        reg.report("i", ActivityKind.Install, "A", running(1)); runCurrent()

        advanceTimeBy(5_000); runCurrent()
        assertEquals(1, reg.activities.value.size, "the stale timer must not take the live run")
        assertTrue(reg.activities.value.single().phase is ActivityPhase.Running)
    }

    @Test
    fun `running entries are never evicted by the cap`() = runTest {
        val reg = ActivityRegistry(scope = this, clock = TestClock(), maxTerminal = 2)
        repeat(20) { reg.report("run$it", ActivityKind.Sync, "S$it", running(1)) }
        runCurrent()
        assertEquals(20, reg.activities.value.size, "in-flight work keeps its cancel control")
    }

    @Test
    fun `settled overflow drops successes before failures`() = runTest {
        val reg = ActivityRegistry(scope = this, clock = TestClock(), maxTerminal = 2, terminalHoldMs = 60_000)
        reg.report("ok1", ActivityKind.Install, "1", ActivityPhase.Succeeded)
        reg.report("bad", ActivityKind.Install, "2", ActivityPhase.Failed("boom"))
        reg.report("ok2", ActivityKind.Install, "3", ActivityPhase.Succeeded)
        runCurrent()

        val keys = reg.activities.value.map { it.key }
        assertEquals(2, keys.size)
        assertTrue("bad" in keys, "the failure must outlive a success, got $keys")
    }

    @Test
    fun `failures are capped so a retry loop cannot grow the list`() = runTest {
        val reg = ActivityRegistry(scope = this, clock = TestClock(), maxFailed = 3, maxTerminal = 99)
        repeat(10) { reg.report("f$it", ActivityKind.Update, "U$it", ActivityPhase.Failed("boom $it")) }
        runCurrent()
        assertEquals(3, reg.activities.value.size)
        assertEquals(listOf("f7", "f8", "f9"), reg.activities.value.map { it.key }, "newest kept")
    }

    // ── Identity ───────────────────────────────────────────────────────

    @Test
    fun `re-reporting a key keeps its start time so elapsed survives a tick`() = runTest {
        val clock = TestClock(1_000)
        val reg = ActivityRegistry(scope = this, clock = clock)
        reg.report("g", ActivityKind.Sync, "SkyBlock", running(0, 0)); runCurrent()
        clock.advance(30_000)
        reg.report("g", ActivityKind.Sync, "SkyBlock", running(0, 0)); runCurrent()
        advanceTimeBy(300); runCurrent()

        val a = reg.activities.value.single()
        assertEquals(1_000, a.startedAtMillis)
        assertEquals(31_000, a.updatedAtMillis)
    }

    @Test
    fun `dismiss removes a failure and clear empties everything`() = runTest {
        val reg = ActivityRegistry(scope = this, clock = TestClock())
        reg.report("bad", ActivityKind.Install, "A", ActivityPhase.Failed("boom"))
        reg.report("run", ActivityKind.Sync, "B", running(1))
        runCurrent()

        reg.dismiss("bad"); runCurrent()
        assertEquals(listOf("run"), reg.activities.value.map { it.key })
        assertNull(reg.activities.value.firstOrNull { it.phase is ActivityPhase.Failed })

        reg.clear(); runCurrent()
        assertTrue(reg.activities.value.isEmpty())
    }

    @Test
    fun `dismissSettled leaves what is still in flight`() = runTest {
        val reg = ActivityRegistry(scope = this, clock = TestClock(), terminalHoldMs = 60_000)
        reg.report("run", ActivityKind.Install, "A", running(1))
        reg.report("bad", ActivityKind.Install, "B", ActivityPhase.Failed("boom"))
        reg.report("ok", ActivityKind.Install, "C", ActivityPhase.Succeeded)
        runCurrent()

        reg.dismissSettled(); runCurrent()
        assertEquals(listOf("run"), reg.activities.value.map { it.key })
    }

    private fun done(reg: ActivityRegistry): Long =
        (reg.activities.value.single().phase as ActivityPhase.Running).done
}

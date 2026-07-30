package hivens.core.cache

import hivens.test.TestClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DefaultCacheTest {

    /** In-memory [DiskStore] with knobs for failure + write counting. */
    private class MapDiskStore<V> : DiskStore<V> {
        val map = ConcurrentHashMap<String, StoredEntry<V>>()
        @Volatile var failRead = false
        val writeCount = AtomicInteger(0)
        override fun read(key: String): StoredEntry<V>? {
            if (failRead) throw RuntimeException("disk read boom")
            return map[key]
        }
        override fun write(key: String, value: V, storedAtMillis: Long) {
            writeCount.incrementAndGet()
            map[key] = StoredEntry(value, storedAtMillis)
        }
        override fun delete(key: String) { map.remove(key) }
        override fun clear() { map.clear() }
    }

    /** Memory-only: read always misses, so memory eviction is observable. */
    private class NullDiskStore<V> : DiskStore<V> {
        override fun read(key: String): StoredEntry<V>? = null
        override fun write(key: String, value: V, storedAtMillis: Long) {}
        override fun delete(key: String) {}
        override fun clear() {}
    }

    // Cache scope = the TestScope itself, so its launched work (background SWR
    // refresh, debounced disk writes) is advanced by advanceUntilIdle(). The IO
    // dispatcher is Unconfined so disk hops run inline without a separate dispatch.
    private fun <V> TestScope.cache(
        disk: DiskStore<V>,
        config: CacheConfig<V>,
        clock: TestClock,
    ): DefaultCache<V> = DefaultCache(
        diskStore = disk,
        config = config,
        scope = this,
        clock = clock,
        namespace = "test",
        ioDispatcher = UnconfinedTestDispatcher(testScheduler),
    )

    @Test
    fun `miss loads then a fresh hit avoids the loader`() = runTest {
        val clock = TestClock()
        val calls = AtomicInteger(0)
        val cache = cache(MapDiskStore<String>(), CacheConfig(ttlMs = 1_000), clock)
        val loader: suspend () -> String = { calls.incrementAndGet(); "v1" }

        assertEquals("v1", cache.get("k", loader))
        assertEquals("v1", cache.get("k", loader))
        assertEquals(1, calls.get(), "second get within TTL must not call the loader")
    }

    @Test
    fun `stale read serves stale then refreshes with a single upstream call`() = runTest {
        val clock = TestClock()
        val calls = AtomicInteger(0)
        var current = "old"
        val cache = cache(MapDiskStore<String>(), CacheConfig(ttlMs = 1_000), clock)
        val loader: suspend () -> String = { calls.incrementAndGet(); current }

        assertEquals("old", cache.get("k", loader))      // miss -> load
        current = "new"
        clock.advance(1_500)                              // now stale
        assertEquals("old", cache.get("k", loader), "stale value served immediately")
        advanceUntilIdle()                                // let the background refresh run
        assertEquals(2, calls.get(), "exactly one background refresh")
        assertEquals("new", cache.get("k", loader), "refreshed value now fresh")
        assertEquals(2, calls.get(), "fresh hit, no extra load")
    }

    @Test
    fun `concurrent misses collapse to one upstream load`() = runTest {
        val clock = TestClock()
        val calls = AtomicInteger(0)
        val cache = cache(MapDiskStore<String>(), CacheConfig(ttlMs = 10_000), clock)
        val loader: suspend () -> String = { calls.incrementAndGet(); delay(50); "v" }

        val results = (1..50).map { async { cache.get("k", loader) } }
        advanceUntilIdle()
        results.forEach { assertEquals("v", it.await()) }
        assertEquals(1, calls.get(), "single-flight: 50 concurrent gets -> one loader call")
    }

    @Test
    fun `value survives a restart via disk`() = runTest {
        val clock = TestClock()
        val disk = MapDiskStore<String>()
        val calls = AtomicInteger(0)
        val loader: suspend () -> String = { calls.incrementAndGet(); "v" }

        cache(disk, CacheConfig(ttlMs = 10_000), clock).get("k", loader)
        advanceUntilIdle()                                // flush debounced disk write
        assertTrue(disk.map.containsKey("k"))

        // "restart": fresh cache over the same disk + same clock
        val calls2 = AtomicInteger(0)
        val v = cache(disk, CacheConfig(ttlMs = 10_000), clock).get("k") { calls2.incrementAndGet(); "other" }
        assertEquals("v", v, "served from disk, not the loader")
        assertEquals(0, calls2.get())
    }

    @Test
    fun `refresh failure keeps the stale value and retries next time`() = runTest {
        val clock = TestClock()
        val calls = AtomicInteger(0)
        var failing = false
        val cache = cache(MapDiskStore<String>(), CacheConfig(ttlMs = 1_000), clock)
        val loader: suspend () -> String = {
            calls.incrementAndGet()
            if (failing) throw RuntimeException("upstream down") else "old"
        }

        cache.get("k", loader)                            // store "old"
        failing = true
        clock.advance(1_500)
        assertEquals("old", cache.get("k", loader))       // stale served
        advanceUntilIdle()                                // refresh runs + fails (logged)
        assertEquals("old", cache.get("k", loader), "stale retained after failed refresh")
        advanceUntilIdle()
        assertTrue(calls.get() >= 3, "failed refresh retries on subsequent stale reads")
    }

    @Test
    fun `past the hard staleness cap the loader error propagates`() = runTest {
        val clock = TestClock()
        val cache = cache(MapDiskStore<String>(), CacheConfig(ttlMs = 1_000, staleTtlMs = 2_000), clock)
        var failing = false
        val loader: suspend () -> String = { if (failing) throw IllegalStateException("down") else "old" }

        cache.get("k", loader)
        failing = true
        clock.advance(2_500)                              // past hard cap -> not served
        assertFailsWith<IllegalStateException> { cache.get("k", loader) }
    }

    @Test
    fun `shouldStore veto keeps the previous value`() = runTest {
        val clock = TestClock()
        val cache = cache(
            MapDiskStore<List<String>>(),
            CacheConfig(ttlMs = 1_000, shouldStore = { it.isNotEmpty() }),
            clock,
        )
        cache.get("k") { listOf("a", "b") }               // stored
        clock.advance(1_500)
        cache.get("k") { emptyList() }                    // stale -> background refresh returns empty
        advanceUntilIdle()
        assertEquals(listOf("a", "b"), cache.get("k") { listOf("a", "b") }, "empty result must not clobber the cache")
    }

    @Test
    fun `invalidate drops memory and disk`() = runTest {
        val clock = TestClock()
        val disk = MapDiskStore<String>()
        val calls = AtomicInteger(0)
        val cache = cache(disk, CacheConfig(ttlMs = 10_000), clock)
        val loader: suspend () -> String = { calls.incrementAndGet(); "v" }

        cache.get("k", loader); advanceUntilIdle()
        cache.invalidate("k"); advanceUntilIdle() // delete is routed through the debounced writer
        assertTrue(!disk.map.containsKey("k"))
        cache.get("k", loader)
        assertEquals(2, calls.get(), "post-invalidate get reloads")
    }

    @Test
    fun `invalidateAll wipes disk and a not-yet-flushed write does not resurrect`() = runTest {
        val clock = TestClock()
        val disk = MapDiskStore<String>()
        // A long debounce keeps the write pending across the invalidateAll, so the
        // clear has to win over an op that was scheduled before it.
        val cache = cache(disk, CacheConfig(ttlMs = 10_000, diskDebounceMs = 10_000), clock)

        cache.get("k1") { "v1" }; advanceUntilIdle()      // persisted to disk
        assertTrue(disk.map.containsKey("k1"))
        cache.get("k2") { "v2" }                          // write scheduled, still within debounce

        cache.invalidateAll()
        assertTrue(disk.map.isEmpty(), "invalidateAll wipes disk immediately")
        advanceUntilIdle()                                // let any pending writer run
        assertTrue(disk.map.isEmpty(), "a write scheduled before the clear must not resurrect after it")
    }

    @Test
    fun `a cancelled leader load lets a later get recover (no inFlight leak)`() = runTest {
        val cache = cache(MapDiskStore<String>(), CacheConfig(ttlMs = 10_000), TestClock())
        val job = launch { cache.get("k") { delay(1_000); "v" } } // leader, suspended mid-load
        advanceTimeBy(10)
        job.cancel()                                              // cancel the leader before it completes
        advanceUntilIdle()
        // inFlight must have been cleared in the leader's finally, so a fresh get
        // becomes a new leader rather than awaiting a dead deferred.
        assertEquals("v2", cache.get("k") { "v2" })
    }

    @Test
    fun `refresh reloads inside the TTL, where get would have answered from cache`() = runTest {
        val clock = TestClock()
        val calls = AtomicInteger(0)
        var current = "old"
        val cache = cache(MapDiskStore<String>(), CacheConfig(ttlMs = 10_000), clock)
        val loader: suspend () -> String = { calls.incrementAndGet(); current }

        assertEquals("old", cache.get("k", loader))
        current = "new"

        // Well inside the TTL: this is exactly the window where a "check now"
        // button used to report a stale answer as if it had just looked.
        assertEquals("old", cache.get("k", loader))
        assertEquals(1, calls.get())

        assertEquals("new", cache.refresh("k", loader))
        assertEquals(2, calls.get(), "refresh must reach the loader despite a fresh entry")
        assertEquals("new", cache.get("k", loader), "the refreshed value replaces the cached one")
        assertEquals(2, calls.get())
    }

    @Test
    fun `concurrent refreshes collapse to one upstream load`() = runTest {
        val clock = TestClock()
        val calls = AtomicInteger(0)
        val gate = CompletableDeferred<Unit>()
        val cache = cache(MapDiskStore<String>(), CacheConfig(ttlMs = 1_000), clock)
        val loader: suspend () -> String = { calls.incrementAndGet(); gate.await(); "v" }

        val a = async { cache.refresh("k", loader) }
        val b = async { cache.refresh("k", loader) }
        runCurrent()
        gate.complete(Unit)

        assertEquals("v", a.await())
        assertEquals("v", b.await())
        assertEquals(1, calls.get(), "a burst of explicit refreshes is still one call")
    }

    @Test
    fun `flow emits stale then fresh`() = runTest {
        val clock = TestClock()
        var current = "old"
        val cache = cache(MapDiskStore<String>(), CacheConfig(ttlMs = 1_000), clock)
        val loader: suspend () -> String = { current }

        cache.get("k", loader)
        current = "new"
        clock.advance(1_500)
        val emissions = cache.flow("k", loader).toList()
        assertEquals(
            listOf(CacheValue("old", Freshness.STALE), CacheValue("new", Freshness.FRESH)),
            emissions,
        )
    }

    @Test
    fun `flow on a miss emits only fresh`() = runTest {
        val clock = TestClock()
        val cache = cache(MapDiskStore<String>(), CacheConfig(ttlMs = 1_000), clock)
        val emissions = cache.flow("k") { "v" }.toList()
        assertEquals(listOf(CacheValue("v", Freshness.FRESH)), emissions)
    }

    @Test
    fun `LRU evicts the eldest beyond maxEntries`() = runTest {
        val clock = TestClock()
        val calls = ConcurrentHashMap<String, Int>()
        val cache = cache(NullDiskStore<String>(), CacheConfig(ttlMs = 10_000, maxEntries = 2), clock)
        val loader: (String) -> suspend () -> String = { k -> { calls.merge(k, 1, Int::plus); k } }

        cache.get("k1", loader("k1"))
        cache.get("k2", loader("k2"))
        cache.get("k1", loader("k1"))                     // touch k1 (now MRU)
        cache.get("k3", loader("k3"))                     // size 3 -> evict eldest = k2
        cache.get("k1", loader("k1"))                     // still cached
        cache.get("k2", loader("k2"))                     // evicted -> reload

        assertEquals(1, calls["k1"], "k1 stayed cached (was touched)")
        assertEquals(2, calls["k2"], "k2 was evicted and reloaded")
    }

    @Test
    fun `disk read failure degrades to a miss, not a crash`() = runTest {
        val clock = TestClock()
        val disk = MapDiskStore<String>().apply { failRead = true }
        val cache = cache(disk, CacheConfig(ttlMs = 1_000), clock)
        assertEquals("v", cache.get("k") { "v" }, "a throwing disk read must be treated as a miss")
    }

    @Test
    fun `background refresh is independent of the caller scope`() = runTest {
        val clock = TestClock()
        var current = "old"
        val cache = cache(MapDiskStore<String>(), CacheConfig(ttlMs = 1_000), clock)
        val loader: suspend () -> String = { current }

        cache.get("k", loader)
        current = "new"
        clock.advance(1_500)
        // A short-lived caller triggers the refresh then "leaves".
        async { cache.get("k", loader) }.await()
        advanceUntilIdle()                                // refresh runs on the cache scope regardless
        assertEquals("new", cache.get("k", loader))
    }

    @Test
    fun `a store during a pending write conflates to the latest value (no reorder)`() = runTest {
        // A second store for the same key while the first is still
        // pending must not let an older value land last. The single per-key
        // writer conflates onto the newest value and writes it exactly once.
        val clock = TestClock()
        val disk = MapDiskStore<String>()
        var current = "v1"
        val cache = cache(disk, CacheConfig(ttlMs = 1_000, diskDebounceMs = 200), clock)

        cache.get("k") { current }          // store v1 (writer is debouncing)
        current = "v2"
        clock.advance(1_500)                // stale
        cache.get("k") { current }          // stale served; background refresh stores v2
        advanceUntilIdle()

        assertEquals("v2", disk.map["k"]?.value, "disk holds the newest value, not the older one")
        assertEquals(1, disk.writeCount.get(), "the two stores collapse into one write")
    }

    @Test
    fun `disk writes for the same key are debounced`() = runTest {
        val clock = TestClock()
        val disk = MapDiskStore<String>()
        val cache = cache(disk, CacheConfig(ttlMs = 0, diskDebounceMs = 200), clock)
        // ttl 0 -> every get is a fresh load+store; hammer the same key.
        repeat(5) { cache.get("k") { "v$it" } }
        advanceUntilIdle()
        assertTrue(disk.writeCount.get() <= 2, "rapid stores coalesce; got ${disk.writeCount.get()}")
    }
}

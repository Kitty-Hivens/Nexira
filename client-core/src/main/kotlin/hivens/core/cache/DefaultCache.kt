package hivens.core.cache

import hivens.core.time.Clock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/**
 * The cache engine. In-memory LRU over a [DiskStore], with single-flight,
 * stale-while-revalidate, TTL via an injected [Clock], and debounced disk writes.
 *
 * Concurrency model:
 *  - [mutex] guards the [memory] index, the [inFlight] single-flight map, and
 *    [pendingWrites]; it is never held across disk or network I/O.
 *  - A background refresh (stale read) runs on [scope] (a SupervisorJob), so a
 *    caller leaving composition cannot cancel a refresh other callers share.
 *  - A blocking miss runs the loader in the caller's own coroutine, so caller
 *    cancellation correctly aborts a first-ever fetch.
 *  - [ioDispatcher] is injectable so disk hops stay under a test scheduler's
 *    virtual time.
 */
class DefaultCache<V>(
    private val diskStore: DiskStore<V>,
    private val config: CacheConfig<V>,
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val namespace: String = "cache",
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : Cache<V> {

    private val log = LoggerFactory.getLogger(DefaultCache::class.java)
    private val mutex = Mutex()

    private class Entry<V>(val value: V, val storedAtMillis: Long)

    /**
     * The latest pending disk op for a key, conflated onto by the single per-key
     * writer. [delete] = true is an invalidation routed through the same writer so
     * a delete can never interleave with an in-flight write (no resurrection).
     */
    private class Pending<V>(val value: V?, val storedAtMillis: Long, val delete: Boolean)

    private val memory = object : LinkedHashMap<String, Entry<V>>(16, 0.75f, /* accessOrder = */ true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry<V>>?): Boolean =
            size > config.maxEntries
    }
    private val inFlight = HashMap<String, CompletableDeferred<V>>()
    private val pending = HashMap<String, Pending<V>>()
    private val writerActive = HashSet<String>()

    override suspend fun get(key: String, loader: suspend () -> V): V {
        val entry = lookup(key)
        if (entry != null) {
            val age = ageOf(entry)
            if (age < config.ttlMs) return entry.value
            if (age < config.staleTtlMs) {
                triggerRefresh(key, loader)
                return entry.value
            }
            // past the hard-staleness cap -> fall through to a blocking reload
        }
        return load(key, loader)
    }

    override fun flow(key: String, loader: suspend () -> V): Flow<CacheValue<V>> = flow {
        val entry = lookup(key)
        if (entry != null) {
            val age = ageOf(entry)
            if (age < config.ttlMs) {
                emit(CacheValue(entry.value, Freshness.FRESH))
                return@flow
            }
            if (age < config.staleTtlMs) {
                emit(CacheValue(entry.value, Freshness.STALE))
                emit(CacheValue(load(key, loader), Freshness.FRESH))
                return@flow
            }
        }
        emit(CacheValue(load(key, loader), Freshness.FRESH))
    }

    override suspend fun invalidate(key: String) {
        mutex.withLock {
            memory.remove(key)
            // Route the delete through the per-key writer instead of deleting
            // off-lock here: that's what keeps it ordered with any in-flight
            // write so an older value can't land back on disk after the delete.
            pending[key] = Pending(value = null, storedAtMillis = 0, delete = true)
            if (writerActive.add(key)) {
                scope.launch { runWriter(key) }
            }
        }
    }

    override suspend fun invalidateAll() {
        mutex.withLock {
            memory.clear()
            pending.clear()
        }
        withContext(ioDispatcher) { runCatching { diskStore.clear() } }
    }

    /** Memory hit, else promote from disk (I/O off-lock) into memory. */
    private suspend fun lookup(key: String): Entry<V>? {
        mutex.withLock { memory[key] }?.let { return it }
        val stored = withContext(ioDispatcher) { runCatching { diskStore.read(key) }.getOrNull() } ?: return null
        return mutex.withLock {
            memory[key] ?: Entry(stored.value, stored.storedAtMillis).also { memory[key] = it }
        }
    }

    private fun triggerRefresh(key: String, loader: suspend () -> V) {
        scope.launch {
            runCatching { load(key, loader) }
                .onFailure { log.warn("cache[{}] background refresh failed for {}; keeping stale", namespace, key, it) }
        }
    }

    /**
     * Single-flight load: the first caller (leader) runs [loader] in its own
     * coroutine, stores the result, and completes the shared deferred; concurrent
     * callers (followers) await it -- one upstream call per key.
     */
    private suspend fun load(key: String, loader: suspend () -> V): V {
        val (deferred, isLeader) = mutex.withLock {
            val existing = inFlight[key]
            if (existing != null) existing to false
            else CompletableDeferred<V>().also { inFlight[key] = it } to true
        }
        if (!isLeader) return deferred.await()
        try {
            val value = loader()
            store(key, value)
            deferred.complete(value)
            return value
        } catch (t: Throwable) {
            deferred.completeExceptionally(t)
            throw t
        } finally {
            mutex.withLock { inFlight.remove(key) }
        }
    }

    /**
     * Write-through to memory now + debounced to disk; [CacheConfig.shouldStore]
     * can veto. Disk writes are serialized per key by a single [runWriter]
     * coroutine that conflates onto the latest [Pending] value -- so two rapid
     * stores can neither race on the shared `<key>.tmp` nor publish out of order
     * (an older value landing after a newer one). The write runs on [scope]
     * (IO-dispatched in production), off any UI thread.
     */
    private suspend fun store(key: String, value: V) {
        if (!config.shouldStore(value)) return
        val now = clock.nowMillis()
        mutex.withLock {
            memory[key] = Entry(value, now)
            pending[key] = Pending(value, now, delete = false)
            // add() returns true only if no writer is already running for this key,
            // so there is at most one writer (and one in-flight disk op) per key.
            if (writerActive.add(key)) {
                scope.launch { runWriter(key) }
            }
        }
    }

    /**
     * One writer per key: debounce, then apply the latest pending op (write or
     * delete) and loop until none is pending. Taking the op and the exit decision
     * (`writerActive.remove`) under the SAME lock is what prevents a store from
     * seeing a still-active writer that is about to exit and orphaning its op.
     * The op is applied off-lock but is the only disk mutation in flight for the
     * key, so writes and deletes can't reorder.
     */
    @Suppress("UNCHECKED_CAST")
    private suspend fun runWriter(key: String) {
        try {
            while (true) {
                delay(config.diskDebounceMs)
                val op = mutex.withLock {
                    val p = pending.remove(key)
                    if (p == null) writerActive.remove(key)
                    p
                } ?: return
                if (op.delete) {
                    runCatching { diskStore.delete(key) }
                } else {
                    runCatching { diskStore.write(key, op.value as V, op.storedAtMillis) }
                }
            }
        } finally {
            // Cancellation (scope shutdown) can skip the in-loop removal; make the
            // writerActive slot release uncancellable so a key can't get stuck.
            withContext(NonCancellable) { mutex.withLock { writerActive.remove(key) } }
        }
    }

    private fun ageOf(entry: Entry<V>): Long =
        (clock.nowMillis() - entry.storedAtMillis).coerceAtLeast(0)
}

package hivens.core.cache

import hivens.core.time.Clock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

    private val memory = object : LinkedHashMap<String, Entry<V>>(16, 0.75f, /* accessOrder = */ true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry<V>>?): Boolean =
            size > config.maxEntries
    }
    private val inFlight = HashMap<String, CompletableDeferred<V>>()
    private val pendingWrites = HashMap<String, Job>()

    override suspend fun get(key: String, loader: suspend () -> V): V {
        val entry = lookup(key)
        if (entry != null) {
            val age = clock.nowMillis() - entry.storedAtMillis
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
            val age = clock.nowMillis() - entry.storedAtMillis
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
            pendingWrites.remove(key)?.cancel()
        }
        withContext(ioDispatcher) { runCatching { diskStore.delete(key) } }
    }

    override suspend fun invalidateAll() {
        mutex.withLock {
            memory.clear()
            pendingWrites.values.forEach { it.cancel() }
            pendingWrites.clear()
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
     * can veto. The debounced write runs on [scope] (IO-dispatched in production),
     * so the disk op already runs off any UI thread without a nested context hop.
     */
    private suspend fun store(key: String, value: V) {
        if (!config.shouldStore(value)) return
        val now = clock.nowMillis()
        mutex.withLock {
            memory[key] = Entry(value, now)
            pendingWrites.remove(key)?.cancel()
            pendingWrites[key] = scope.launch {
                delay(config.diskDebounceMs)
                runCatching { diskStore.write(key, value, now) }
                mutex.withLock { pendingWrites.remove(key) }
            }
        }
    }
}

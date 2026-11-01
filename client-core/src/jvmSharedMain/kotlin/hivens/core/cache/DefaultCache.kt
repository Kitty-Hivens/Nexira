package hivens.core.cache

import hivens.core.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
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
 *  - [mutex] guards the in-memory state (the [memory] index, the [inFlight]
 *    single-flight map, [pending], [writerActive]); it is never held across disk
 *    or network I/O.
 *  - [diskMutex] serializes the off-[mutex] disk mutations (per-key write/delete
 *    vs the bulk [invalidateAll] clear) so they cannot interleave.
 *  - A background refresh (stale read) runs on [scope] (a SupervisorJob), so a
 *    caller leaving composition cannot cancel a refresh other callers share.
 *  - A blocking miss runs the loader in the caller's own coroutine, so caller
 *    cancellation aborts a first-ever fetch nobody else is waiting for -- and
 *    hands it to one of them when somebody is.
 *  - [ioDispatcher] is injectable so disk hops stay under a test scheduler's
 *    virtual time.
 */
@OptIn(DelicateCoroutinesApi::class) // CoroutineStart.ATOMIC, for the reason given at each use.
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

    /**
     * Serializes the off-[mutex] disk mutations (per-key write/delete vs the bulk
     * [invalidateAll] clear) so a clear and an in-flight write cannot interleave.
     * Held only around disk I/O, never together with [mutex].
     */
    private val diskMutex = Mutex()

    /**
     * Bumped under [diskMutex] by [invalidateAll]. A write op captures the epoch it
     * was produced under; if a clear has since bumped it, the writer drops the op
     * instead of resurrecting a value the clear was meant to remove.
     */
    @Volatile
    private var diskEpoch: Long = 0

    private class Entry<V>(val value: V, val storedAtMillis: Long)

    /**
     * The latest pending disk op for a key, conflated onto by the single per-key
     * writer. [delete] = true is an invalidation routed through the same writer so
     * a delete can never interleave with an in-flight write (no resurrection).
     * [epoch] is the [diskEpoch] the op was produced under (write ops only).
     */
    private class Pending<V>(
        val value: V?,
        val storedAtMillis: Long,
        val delete: Boolean,
        val epoch: Long,
    )

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
                when (config.staleMode) {
                    StaleMode.Eager -> {
                        triggerRefresh(key, loader)
                        return entry.value
                    }
                    // Ask, and keep what we had only if asking failed. A caller of
                    // get() reads once, so handing it the old value means the old
                    // value is what the screen shows until it is opened again.
                    StaleMode.FallbackOnFailure -> {
                        return runCatching { load(key, loader) }
                            .getOrElse { e ->
                                // Only THIS caller leaving skips the fallback. A
                                // loader that timed out threw a CancellationException
                                // too, and reading that as "the reader left" put an
                                // error on the one screen the fallback exists for.
                                currentCoroutineContext().ensureActive()
                                log.warn(
                                    "cache[{}] refresh failed for {}; falling back on the stale entry",
                                    namespace, key, e,
                                )
                                entry.value
                            }
                    }
                }
            }
            // past the hard-staleness cap -> fall through to a blocking reload
        }
        return load(key, loader)
    }

    override suspend fun refresh(key: String, loader: suspend () -> V): V = load(key, loader)

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
            pending[key] = Pending(value = null, storedAtMillis = 0, delete = true, epoch = diskEpoch)
            if (writerActive.add(key)) {
                scope.launch(start = CoroutineStart.ATOMIC) { runWriter(key) }
            }
        }
    }

    override suspend fun invalidateAll() {
        mutex.withLock {
            memory.clear()
            pending.clear()
        }
        // Bump the epoch and clear under diskMutex so any per-key writer that has
        // already taken its op but not yet written observes the new epoch (or is
        // ordered after the clear) and drops its now-superseded write.
        withContext(ioDispatcher) {
            diskMutex.withLock {
                diskEpoch++
                runCatching { diskStore.clear() }
            }
        }
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
     *
     * A cancelled LEADER is not a failed load. It says the caller walked away,
     * which is a fact about that caller and about nothing else, so the flight is
     * released and whichever waiter is still there picks it up. Handing the
     * cancellation on instead made one reader leaving a screen read, to every
     * other reader of the same key, as the source having refused them.
     */
    private suspend fun load(key: String, loader: suspend () -> V): V {
        while (true) {
            val (deferred, isLeader) = mutex.withLock {
                val existing = inFlight[key]
                if (existing != null) existing to false
                else CompletableDeferred<V>().also { inFlight[key] = it } to true
            }
            if (!isLeader) {
                try {
                    return deferred.await()
                } catch (e: LeaderGone) {
                    // Waking on somebody else's departure is no reason to carry on
                    // if this caller has left too. A resume that carries an
                    // exception skips the cancellation check that would otherwise
                    // have stopped us here, and the mutex above takes its fast path
                    // without one either -- so a dead waiter would take the flight
                    // over and run the loader on behalf of nobody.
                    currentCoroutineContext().ensureActive()
                    // The flight is already released, so this pass either takes it
                    // over or joins whoever took it first.
                    continue
                }
            }
            try {
                val value = loader()
                store(key, value)
                deferred.complete(value)
                // Released LAST on the way out with a value, and a caller arriving
                // in the meantime joins a deferred that is already complete.
                // Releasing first let that caller become a SECOND leader for the
                // same key: two upstream calls, and -- since store() stamps the
                // clock when it writes rather than when the value was fetched --
                // the slower of the two could land its older answer on top of the
                // newer one and have it read as the fresher entry for a whole TTL.
                release(key, deferred)
                return value
            } catch (t: Throwable) {
                // Released BEFORE the deferred is settled, so a waiter waking on
                // [LeaderGone] finds the slot empty instead of re-awaiting the
                // corpse it just woke from.
                release(key, deferred)
                // Whether the CALLER is gone, not whether the throwable happens to
                // be a CancellationException. A loader with a withTimeout of its
                // own throws one of those when the fetch times out, and reading
                // that as "the reader left" would hand every waiter a re-election
                // instead of the error -- each of them then paying the same
                // timeout again, in turn, and none of them ever learning why.
                if (!currentCoroutineContext().isActive) deferred.cancel(LeaderGone())
                else deferred.completeExceptionally(t)
                throw t
            }
        }
    }

    /**
     * Takes this key's flight down, if it is still ours.
     *
     * Uncancellable, because it has to survive the cancellation it is cleaning up
     * after: the previous version released under the caller's own job, so a
     * cancelled leader left its entry behind and every later reader of that key
     * awaited a deferred nobody would ever complete. Keyed on the deferred as
     * well, so a leader finishing late cannot remove a successor's flight.
     */
    private suspend fun release(key: String, deferred: CompletableDeferred<V>) {
        withContext(NonCancellable) { mutex.withLock { inFlight.remove(key, deferred) } }
    }

    /** The leader left. Distinct from a cancellation of the waiter's own making. */
    private class LeaderGone : CancellationException("the caller loading this key went away")

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
            pending[key] = Pending(value, now, delete = false, epoch = diskEpoch)
            // add() returns true only if no writer is already running for this key,
            // so there is at most one writer (and one in-flight disk op) per key.
            // ATOMIC start guarantees the body (and its writerActive-releasing
            // finally) runs even if the scope is already cancelled, so a key can't
            // get stuck with no live writer.
            if (writerActive.add(key)) {
                scope.launch(start = CoroutineStart.ATOMIC) { runWriter(key) }
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
                delay(config.diskDebounceMs.milliseconds)
                val op = mutex.withLock {
                    val p = pending.remove(key)
                    if (p == null) writerActive.remove(key)
                    p
                } ?: return
                diskMutex.withLock {
                    if (op.delete) {
                        runCatching { diskStore.delete(key) }
                    } else if (op.epoch == diskEpoch) {
                        runCatching { diskStore.write(key, op.value as V, op.storedAtMillis) }
                    }
                    // else: an invalidateAll bumped the epoch after this op was
                    // produced; dropping the write avoids resurrecting a cleared value.
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

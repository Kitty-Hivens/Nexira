package hivens.core.cache

import kotlinx.coroutines.flow.Flow

/** How current a [CacheValue] is relative to the namespace TTL. */
enum class Freshness { FRESH, STALE, REVALIDATING }

data class CacheValue<V>(val value: V, val freshness: Freshness)

/**
 * URL/key-keyed cache with TTL + stale-while-revalidate + single-flight, backed
 * by an in-memory layer over a [DiskStore]. Implementations serve a stale value
 * immediately and refresh in the background, collapse concurrent identical loads
 * into one upstream call, and survive process restart via the disk layer.
 */
interface Cache<V> {
    /**
     * Stale-while-revalidate read. Fresh entry -> returned with no I/O. Stale
     * entry (older than TTL but within the hard-staleness cap) -> returned
     * immediately while a background refresh runs. Missing or past the hard cap
     * -> awaits [loader] (single-flighted) and returns its result.
     */
    suspend fun get(key: String, loader: suspend () -> V): V

    /**
     * Reactive view: emits the stale value first (if any, within the cap) then
     * the fresh value; a missing entry emits only the fresh value. The load is
     * single-flighted with concurrent callers.
     */
    fun flow(key: String, loader: suspend () -> V): Flow<CacheValue<V>>

    suspend fun invalidate(key: String)
    suspend fun invalidateAll()
}

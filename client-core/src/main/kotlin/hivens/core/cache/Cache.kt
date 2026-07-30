package hivens.core.cache

import kotlinx.coroutines.flow.Flow

/** How current a [CacheValue] is relative to the namespace TTL. */
enum class Freshness { FRESH, STALE }

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
     * Ignore the TTL and reload: always runs [loader], stores the result, and
     * returns it. Still single-flighted, so a burst of explicit refreshes for
     * one key costs one upstream call.
     *
     * This is what an action the user took deliberately must use. [get] answers
     * a four-minute-old entry without touching the network, which is right for
     * ambient reads and wrong for a "check now" button -- there the whole point
     * is to learn something the cache cannot know.
     */
    suspend fun refresh(key: String, loader: suspend () -> V): V

    /**
     * Reactive view: emits the stale value first (if any, within the cap) then
     * the fresh value; a missing entry emits only the fresh value. The load is
     * single-flighted with concurrent callers.
     */
    fun flow(key: String, loader: suspend () -> V): Flow<CacheValue<V>>

    suspend fun invalidate(key: String)
    suspend fun invalidateAll()
}

/**
 * [Cache.get] or [Cache.refresh] chosen by [forceRefresh], so a caller can carry
 * "the user asked for this" down as a flag instead of branching at every read.
 */
suspend fun <V> Cache<V>.read(key: String, forceRefresh: Boolean, loader: suspend () -> V): V =
    if (forceRefresh) refresh(key, loader) else get(key, loader)

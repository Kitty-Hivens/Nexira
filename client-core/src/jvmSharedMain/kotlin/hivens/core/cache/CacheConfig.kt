package hivens.core.cache

/**
 * What a cache does with an entry past its [CacheConfig.ttlMs].
 *
 * The choice is between latency and currency, and which one matters is a property
 * of the data rather than of the cache.
 */
enum class StaleMode {
    /**
     * Hand the old value back at once and refresh behind it.
     *
     * Right where the reader observes the entry over time, through [Cache.flow],
     * and wrong where they read it once: a caller using [Cache.get] receives the
     * old value and never sees the refresh, so the screen shows what the cache had
     * and keeps showing it for the whole visit.
     */
    Eager,

    /**
     * Fetch, and fall back on the old value only if the fetch fails.
     *
     * For anything a reader takes as current: a project's download count, its
     * updated date, its description. The catalogue's own launcher simply treats an
     * expired entry as missing; the fallback is the part we keep, because a stale
     * project beats an error page when the network is gone and there is no
     * difference between the two when it is not.
     */
    FallbackOnFailure,
}

/**
 * Per-namespace cache policy.
 *
 * @param ttlMs age beyond which an entry is *stale* and triggers a background
 *   stale-while-revalidate refresh (but is still served).
 * @param staleTtlMs hard staleness cap: past this age the entry is NOT served --
 *   a [Cache.get] blocks on the loader and propagates its error. Default
 *   [Long.MAX_VALUE] = serve-stale-forever-on-error.
 * @param staleMode what happens to an entry past [ttlMs] but inside
 *   [staleTtlMs]: hand the old value back and refresh behind it, or fetch and
 *   keep the old value only if the fetch failed.
 * @param maxEntries in-memory LRU bound (access-order eviction).
 * @param diskDebounceMs coalesce window for disk writes of the same key.
 * @param shouldStore gate on persisting a loaded value. Returning false keeps any
 *   existing entry instead of overwriting it -- e.g. an empty server list from a
 *   transient outage must not clobber the last-known-good cache.
 */
data class CacheConfig<V>(
    val ttlMs: Long,
    val staleTtlMs: Long = Long.MAX_VALUE,
    val staleMode: StaleMode = StaleMode.Eager,
    val maxEntries: Int = 256,
    val diskDebounceMs: Long = 200,
    val shouldStore: (V) -> Boolean = { true },
)

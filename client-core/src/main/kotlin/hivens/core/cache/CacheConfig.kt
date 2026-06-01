package hivens.core.cache

/**
 * Per-namespace cache policy.
 *
 * @param ttlMs age beyond which an entry is *stale* and triggers a background
 *   stale-while-revalidate refresh (but is still served).
 * @param staleTtlMs hard staleness cap: past this age the entry is NOT served --
 *   a [Cache.get] blocks on the loader and propagates its error. Default
 *   [Long.MAX_VALUE] = serve-stale-forever-on-error.
 * @param maxEntries in-memory LRU bound (access-order eviction).
 * @param diskDebounceMs coalesce window for disk writes of the same key.
 * @param shouldStore gate on persisting a loaded value. Returning false keeps any
 *   existing entry instead of overwriting it -- e.g. an empty server list from a
 *   transient outage must not clobber the last-known-good cache.
 */
data class CacheConfig<V>(
    val ttlMs: Long,
    val staleTtlMs: Long = Long.MAX_VALUE,
    val maxEntries: Int = 256,
    val diskDebounceMs: Long = 200,
    val shouldStore: (V) -> Boolean = { true },
)

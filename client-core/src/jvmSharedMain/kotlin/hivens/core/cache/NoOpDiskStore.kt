package hivens.core.cache

/**
 * Disk backend that persists nothing -- turns a [DefaultCache] into a pure
 * in-memory cache (single-flight + TTL + stale-while-revalidate, no restart
 * persistence). For consumers whose durability is handled elsewhere but that
 * still want the primitive's in-memory dedup.
 */
class NoOpDiskStore<V> : DiskStore<V> {
    override fun read(key: String): StoredEntry<V>? = null
    override fun write(key: String, value: V, storedAtMillis: Long) {}
    override fun delete(key: String) {}
    override fun clear() {}
}

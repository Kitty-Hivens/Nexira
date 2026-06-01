package hivens.core.cache

/**
 * Persistence backend for one typed cache namespace. Synchronous on purpose so a
 * cold read can seed a UI before any coroutine (the tray menu pattern). A read
 * MUST return null -- never throw -- on a missing, corrupt, or schema-mismatched
 * entry, self-healing by deleting the bad file so it doesn't re-fail every launch.
 */
interface DiskStore<V> {
    fun read(key: String): StoredEntry<V>?
    fun write(key: String, value: V, storedAtMillis: Long)
    fun delete(key: String)
    fun clear()
}

data class StoredEntry<V>(val value: V, val storedAtMillis: Long)

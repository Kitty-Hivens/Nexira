package hivens.core.cache

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * No-op [Cache]: every call goes straight to the loader, nothing is stored. The
 * default for cache-aware collaborators so a construction without a real cache
 * (e.g. a unit test, or a not-yet-wired path) behaves exactly as it did before
 * caching existed.
 */
class PassthroughCache<V> : Cache<V> {
    override suspend fun get(key: String, loader: suspend () -> V): V = loader()
    override suspend fun refresh(key: String, loader: suspend () -> V): V = loader()
    override fun flow(key: String, loader: suspend () -> V): Flow<CacheValue<V>> =
        flow { emit(CacheValue(loader(), Freshness.FRESH)) }
    override suspend fun invalidate(key: String) {}
    override suspend fun invalidateAll() {}
}

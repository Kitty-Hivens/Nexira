package hivens.launcher.cache

import hivens.core.cache.Cache
import hivens.core.cache.CacheConfig
import hivens.core.cache.DefaultCache
import hivens.core.time.Clock
import hivens.core.time.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.nio.file.Path

/**
 * Builds typed, disk-backed caches that share one root dir, Json, scope, and
 * clock. Each call yields an independent [Cache] for one value type/namespace --
 * resolving heterogeneous cached types without `Any`/erasure (every namespace
 * carries its own [KSerializer] and disk subdirectory).
 */
class CacheFactory(
    private val rootDir: Path,            // <dataDir>/cache
    private val json: Json,
    private val scope: CoroutineScope,    // shared app scope (SupervisorJob + IO)
    private val clock: Clock = SystemClock,
) {
    fun <V> create(namespace: String, serializer: KSerializer<V>, config: CacheConfig<V>): Cache<V> {
        val disk = JsonDiskStore(rootDir.resolve(namespace), serializer, json)
        return DefaultCache(disk, config, scope, clock, namespace)
    }
}

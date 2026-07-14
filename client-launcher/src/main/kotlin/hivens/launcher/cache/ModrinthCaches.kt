package hivens.launcher.cache

import hivens.core.api.dto.modrinth.ModrinthProject
import hivens.core.api.dto.modrinth.ModrinthVersion
import hivens.core.cache.Cache
import hivens.core.cache.PassthroughCache

/**
 * Per-endpoint caches the [hivens.launcher.modrinth.ModrinthClient] reads
 * through. Built from [CacheFactory] in DI; [passthrough] gives a no-op set for
 * tests and any construction that doesn't wire caching.
 */
class ModrinthCaches(
    val project: Cache<ModrinthProject>,
    val version: Cache<ModrinthVersion>,
) {
    companion object {
        fun passthrough(): ModrinthCaches = ModrinthCaches(
            project = PassthroughCache(),
            version = PassthroughCache(),
        )
    }
}

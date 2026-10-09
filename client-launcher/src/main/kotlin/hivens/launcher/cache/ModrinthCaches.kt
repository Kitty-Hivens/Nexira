package hivens.launcher.cache

import hivens.core.api.dto.modrinth.ModrinthProject
import hivens.core.api.dto.modrinth.ModrinthSearchResponse
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
    /**
     * Search pages by their full URL: the question, the filters, the order and the
     * offset. Kept briefly, because the same page is asked for again every time a
     * reader comes back to a search, opens a project and returns, or flips a kind
     * and flips it back.
     */
    val search: Cache<ModrinthSearchResponse> = PassthroughCache(),
) {
    companion object {
        fun passthrough(): ModrinthCaches = ModrinthCaches(
            project = PassthroughCache(),
            version = PassthroughCache(),
        )
    }
}

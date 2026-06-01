package hivens.launcher.cache

import hivens.core.api.dto.smrt.ModrinthProject
import hivens.core.api.dto.smrt.ModrinthVersion
import hivens.core.api.dto.smrt.SmrtPackListing
import hivens.core.api.dto.smrt.SmrtPackManifest
import hivens.core.api.dto.smrt.SmrtPackSummary
import hivens.core.cache.Cache
import hivens.core.cache.PassthroughCache

/**
 * The per-endpoint caches the [hivens.launcher.smrt.SmrtPackClient] reads through.
 * Built from [CacheFactory] in DI; [passthrough] gives a no-op set for tests and
 * any construction that doesn't wire caching.
 */
class SmrtPackCaches(
    val listing: Cache<SmrtPackListing>,
    val summary: Cache<SmrtPackSummary>,
    val manifest: Cache<SmrtPackManifest>,
    val modrinthProject: Cache<ModrinthProject>,
    val modrinthVersion: Cache<ModrinthVersion>,
) {
    companion object {
        fun passthrough(): SmrtPackCaches = SmrtPackCaches(
            listing = PassthroughCache(),
            summary = PassthroughCache(),
            manifest = PassthroughCache(),
            modrinthProject = PassthroughCache(),
            modrinthVersion = PassthroughCache(),
        )
    }
}

package hivens.launcher.cache

import hivens.core.api.dto.smrt.SmrtPackListing
import hivens.core.api.dto.smrt.SmrtPackManifest
import hivens.core.api.dto.smrt.SmrtPackSummary
import hivens.core.cache.Cache
import hivens.core.cache.PassthroughCache

/**
 * The per-endpoint caches the [hivens.launcher.smrt.SmrtPackClient] reads through.
 * Built from [CacheFactory] in DI; [passthrough] gives a no-op set for tests and
 * any construction that doesn't wire caching. Modrinth caches moved to
 * [ModrinthCaches] alongside [hivens.launcher.modrinth.ModrinthClient].
 */
class SmrtPackCaches(
    val listing: Cache<SmrtPackListing>,
    val summary: Cache<SmrtPackSummary>,
    val manifest: Cache<SmrtPackManifest>,
) {
    companion object {
        fun passthrough(): SmrtPackCaches = SmrtPackCaches(
            listing = PassthroughCache(),
            summary = PassthroughCache(),
            manifest = PassthroughCache(),
        )
    }
}

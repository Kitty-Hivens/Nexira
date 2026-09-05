package hivens.launcher.catalogue

import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.launcher.modrinth.ModrinthClient
import hivens.launcher.smrt.SmrtPackClient
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/** Square icon + wide banner for a pack, either captured at install or resolved from the source. */
data class PackArt(val iconUrl: String?, val bannerUrl: String?) {
    companion object {
        val NONE = PackArt(null, null)
    }
}

/**
 * Native cover (icon + banner) for an installed instance. Art captured at install
 * time ([PackInstance.iconUrl]/[PackInstance.bannerUrl]) wins with no network.
 * Instances created before that field existed carry neither, so the source is
 * asked once per `(origin, id)` -- a cached Modrinth project or mirror summary
 * lookup, NOT the full catalogue `details()` (which also lists versions) -- and
 * the result is memoised for the resolver's life. This is what swaps the pixel
 * placeholder for a pack's real cover; a failed or source-less lookup returns
 * [PackArt.NONE] and the UI keeps the pixel art.
 */
class PackArtResolver(
    private val modrinth: ModrinthClient,
    private val mirror: SmrtPackClient,
) {
    private val log = LoggerFactory.getLogger(PackArtResolver::class.java)
    private val cache = ConcurrentHashMap<String, PackArt>()

    /**
     * The answer when it is already known: art captured at install, or a lookup
     * this resolver has already made. Null means the source has not been asked.
     *
     * Exists so a reader can seed with it before suspending. [resolve] answers a
     * warm pack immediately too, but only after a dispatcher hop, and a hop is a
     * frame -- long enough to paint the pixel placeholder and replace it, on
     * every mount, for art that was never in doubt.
     */
    fun cached(instance: PackInstance): PackArt? {
        if (instance.iconUrl != null || instance.bannerUrl != null) {
            return PackArt(instance.iconUrl, instance.bannerUrl)
        }
        return cache[keyOf(instance)]
    }

    /** One expression, so the reader and the writer cannot drift apart. */
    private fun keyOf(instance: PackInstance): String =
        "${instance.packRef.origin}:${instance.packRef.id}"

    suspend fun resolve(instance: PackInstance): PackArt {
        if (instance.iconUrl != null || instance.bannerUrl != null) {
            return PackArt(instance.iconUrl, instance.bannerUrl)
        }
        val key = keyOf(instance)
        cache[key]?.let { return it }

        val art = try {
            when (instance.packRef.origin) {
                PackOrigin.Modrinth -> modrinth.resolveProject(instance.packRef.id).let { p ->
                    PackArt(
                        iconUrl   = p.iconUrl,
                        // Full-res (raw_url) -- the hero banner upscales a 350px `url` thumbnail.
                        bannerUrl = (p.gallery.firstOrNull { it.featured } ?: p.gallery.firstOrNull())?.let { it.rawUrl ?: it.url },
                    )
                }
                PackOrigin.Mirror -> mirror.fetchSummary(instance.packRef.id).let { PackArt(it.iconUrl, it.bannerUrl) }
                else              -> PackArt.NONE
            }
        } catch (e: Exception) {
            log.warn("Pack art lookup failed for {} ({}): {}", instance.packRef.id, instance.packRef.origin, e.message)
            PackArt.NONE
        }
        cache[key] = art
        return art
    }
}

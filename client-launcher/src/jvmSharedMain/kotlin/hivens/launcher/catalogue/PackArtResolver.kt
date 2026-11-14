package hivens.launcher.catalogue

import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.core.data.PackReference
import hivens.launcher.modrinth.ModrinthClient
import hivens.launcher.smrt.SmrtPackClient
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/** Square icon + wide banner for a pack, either captured at install or resolved from the source. */
data class PackArt(val iconUrl: String?, val bannerUrl: String?) {
    companion object {
        val NONE = PackArt(null, null)
    }
}

/**
 * Native cover (icon + banner) for an installed instance.
 *
 * Art captured at install time ([PackInstance.iconUrl]/[PackInstance.bannerUrl])
 * is what shows first, with no network, but it is not the last word: the source is
 * still asked once per `(origin, id)` per resolver life, and a field it answers
 * replaces the captured one. A pack that gained an icon after it was installed, or
 * whose author swapped the banner, otherwise kept what it had on the day of the
 * install. The lookup is a cached Modrinth project or mirror summary, NOT the full
 * catalogue `details()` (which also lists versions). A failed or source-less lookup
 * leaves the captured art, or [PackArt.NONE], and the UI keeps the pixel art.
 *
 * Only an answer is memoised. A lookup that failed, or was cancelled because the
 * card scrolled away, says nothing about the pack, and written down as NONE it
 * kept the placeholder up until the next start.
 *
 * A pack detached into a Local one is asked about at the source it was detached
 * from ([PackInstance.forkedFrom]). Asked by its own reference it named a Local
 * pack, which has no source to answer, and a detached pack lost the cover its
 * source still publishes. The answer is the source's, so it is shared with an
 * install of that same pack, and what each record captured is laid over it per
 * instance.
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
        cache[keyOf(instance)]?.let { return it.over(instance) }
        return captured(instance)
    }

    /** Whether the source has answered for this pack, so [resolve] would not ask again. */
    fun answered(instance: PackInstance): Boolean = cache.containsKey(keyOf(instance))

    /** One expression, so the reader and the writer cannot drift apart. */
    private fun keyOf(instance: PackInstance): String =
        sourceOf(instance).let { "${it.origin}:${it.id}" }

    /** Where the pack's art is published: the source it was detached from, or its own. */
    private fun sourceOf(instance: PackInstance): PackReference = instance.forkedFrom ?: instance.packRef

    private fun captured(instance: PackInstance): PackArt? =
        if (instance.iconUrl != null || instance.bannerUrl != null) PackArt(instance.iconUrl, instance.bannerUrl) else null

    /** The source's answer field by field, falling back to what the record captured where it has none. */
    private fun PackArt.over(instance: PackInstance): PackArt =
        PackArt(iconUrl ?: instance.iconUrl, bannerUrl ?: instance.bannerUrl)

    suspend fun resolve(instance: PackInstance): PackArt {
        val key = keyOf(instance)
        cache[key]?.let { return it.over(instance) }

        val source = sourceOf(instance)
        val fromSource = try {
            when (source.origin) {
                PackOrigin.Modrinth -> modrinth.resolveProject(source.id).let { p ->
                    PackArt(
                        iconUrl   = p.iconUrl,
                        // Full-res (raw_url) -- the hero banner upscales a 350px `url` thumbnail.
                        bannerUrl = (p.gallery.firstOrNull { it.featured } ?: p.gallery.firstOrNull())?.let { it.rawUrl ?: it.url },
                    )
                }
                PackOrigin.Mirror -> mirror.fetchSummary(source.id).let { PackArt(it.iconUrl, it.bannerUrl) }
                else              -> PackArt.NONE
            }.also { cache[key] = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Pack art lookup failed for {} ({}): {}", source.id, source.origin, e.message)
            return captured(instance) ?: PackArt.NONE
        }
        return fromSource.over(instance)
    }
}

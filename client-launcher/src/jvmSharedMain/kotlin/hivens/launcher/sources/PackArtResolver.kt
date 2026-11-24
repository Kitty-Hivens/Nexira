package hivens.launcher.sources

import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.core.data.PackReference
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

/** Square icon + wide banner for a pack, either captured at install or resolved from the source. */
@Serializable
data class PackArt(val iconUrl: String?, val bannerUrl: String?) {
    companion object {
        val NONE = PackArt(null, null)
    }
}

/**
 * Native cover (icon + banner) for an installed instance.
 *
 * What the source answered on an earlier run ([PackArtMemory]), or without one the art
 * captured at install time ([PackInstance.iconUrl]/[PackInstance.bannerUrl]), is what
 * shows first, with no network, but it is not the last word: the source is still asked
 * once per `(origin, id)` per resolver life, and a field it answers replaces the
 * captured one. A pack that gained an icon after it was installed, or
 * whose author swapped the banner, otherwise kept what it had on the day of the
 * install. The source answers through its [SourcePackWork.art], a cheap read of what
 * it publishes for the pack and not its whole page. A failed or source-less lookup
 * leaves the captured art, or [PackArt.NONE], and the UI keeps the pixel art.
 *
 * Only an answer is memoised. A lookup that failed, or was cancelled because the
 * card scrolled away, says nothing about the pack, and written down as NONE it
 * kept the placeholder up until the next start. A source whose module is not loaded
 * says nothing either, so it is asked again once its module is.
 *
 * A pack detached into a Local one is asked about at the source it was detached
 * from ([PackInstance.forkedFrom]). Asked by its own reference it named a Local
 * pack, which has no source to answer, and a detached pack lost the cover its
 * source still publishes. The answer is the source's, so it is shared with an
 * install of that same pack, and what each record captured is laid over it per
 * instance.
 */
class PackArtResolver(
    private val sources: SourceWorks,
    private val memory: PackArtMemory = PackArtMemory.None,
) {
    private val log = LoggerFactory.getLogger(PackArtResolver::class.java)
    private val cache = ConcurrentHashMap<String, PackArt>()

    /** What earlier runs were answered, with this run's answers laid over it. */
    private val remembered: ConcurrentHashMap<String, PackArt> by lazy { ConcurrentHashMap(memory.load()) }

    /**
     * The answer when it is already known: a lookup this resolver has already made,
     * one an earlier run made, or art captured at install. Null means none of them.
     *
     * Exists so a reader can seed with it before suspending. [resolve] answers a
     * warm pack immediately too, but only after a dispatcher hop, and a hop is a
     * frame, long enough to paint the pixel placeholder and replace it, on
     * every mount, for art that was never in doubt.
     */
    fun cached(instance: PackInstance): PackArt? {
        val key = keyOf(instance)
        (cache[key] ?: remembered[key])?.let { return it.over(instance) }
        return captured(instance)
    }

    /** Whether the source has answered for this pack this run, so [resolve] would not ask again. */
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
            when (val status = sources.status(source.origin)) {
                is SourceStatus.Loaded -> status.work.art(source.id) ?: PackArt.NONE
                SourceStatus.None -> PackArt.NONE
                is SourceStatus.Missing -> return cached(instance) ?: PackArt.NONE
            }.also { answer ->
                cache[key] = answer
                if (source.origin !in PackOrigin.Reserved) remember(key, answer)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Pack art lookup failed for {} ({}): {}", source.id, source.origin, e.message)
            return cached(instance) ?: PackArt.NONE
        }
        return fromSource.over(instance)
    }

    private fun remember(key: String, answer: PackArt) {
        if (remembered.put(key, answer) == answer) return
        synchronized(memory) { memory.save(remembered.toSortedMap()) }
    }
}

package hivens.ui.pictures

import coil3.Extras
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** Marks a fetch that asks the server about a kept picture, rather than one that draws it. */
internal val Rechecking = Extras.Key(default = false)

/**
 * Asks servers about pictures already drawn from disk, a few at a time and after the page
 * that drew them has [settle]d: an entry still being read cannot be written. One address
 * is asked about once in [every], however often it is drawn.
 *
 * A picture that changed is dropped from memory, so the next draw reads the new one. A
 * server that does not answer leaves the kept picture as it is.
 */
internal class PictureRechecks(
    private val scope: CoroutineScope,
    private val settle: Duration = 2.seconds,
    private val every: Duration = 10.minutes,
    private val clock: TimeSource = TimeSource.Monotonic,
) {
    /** The loader whose pictures these are. Asking before it is there does nothing. */
    @Volatile
    var loader: ImageLoader? = null

    private val log = LoggerFactory.getLogger(PictureRechecks::class.java)
    private val askedAt = ConcurrentHashMap<String, TimeMark>()
    private val changed: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val gate = Semaphore(AT_ONCE)

    /** Asks about [url] unless it was asked about lately. */
    fun ask(url: String, context: PlatformContext) {
        var due = false
        askedAt.compute(url) { _, last ->
            due = last == null || last.elapsedNow() >= every
            if (due) clock.markNow() else last
        }
        if (!due) return
        scope.launch(Dispatchers.IO) {
            delay(settle)
            gate.withPermit { recheck(url, context) }
        }
    }

    /** Told that the fetch of [url] under way put something else on disk. */
    fun changed(url: String) {
        changed += url
    }

    private suspend fun recheck(url: String, context: PlatformContext) {
        val loader = loader ?: return
        val options = Options(context, extras = Extras.Builder().set(Rechecking, true).build())
        try {
            val fetcher = loader.components.newFetcher(url.toUri(), options, loader)?.first ?: return
            (fetcher.fetch() as? SourceFetchResult)?.source?.close()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.debug("A kept picture could not be asked about: {}", e.message)
        } finally {
            if (changed.remove(url)) forget(loader, url)
        }
    }

    private fun forget(loader: ImageLoader, url: String) {
        val memory = loader.memoryCache ?: return
        memory.keys.filter { it.key == url }.forEach { memory.remove(it) }
    }

    private companion object {
        const val AT_ONCE = 4
    }
}

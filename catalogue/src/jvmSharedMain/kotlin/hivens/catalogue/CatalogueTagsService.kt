package hivens.catalogue

import hivens.catalogue.api.CatalogueTags
import hivens.core.api.dto.modrinth.ModrinthCategoryTag
import hivens.core.api.dto.modrinth.ModrinthGameVersion
import hivens.core.api.dto.modrinth.ModrinthLoaderTag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.time.Duration

/**
 * The catalogue's own lists the filters are built from: its game versions, its
 * categories with the group each is filed under, and its loaders.
 *
 * Read once per run, on the first reader that asks. They change when Mojang ships or
 * the catalogue adds a category, which a launcher restart covers. A read that failed
 * is tried again by the next reader that asks, rather than leaving the filters empty
 * for the rest of the run.
 */
class CatalogueTagsService(
    /** Reads the three lists, the catalogue's client in the launcher. */
    private val read: suspend () -> CatalogueTags,
    private val scope: CoroutineScope,
    /** Lists already in hand, for a test that has no catalogue to ask. */
    initial: CatalogueTags? = null,
) {
    private val _tags = MutableStateFlow(initial)
    val tags: StateFlow<CatalogueTags?> = _tags.asStateFlow()

    private var loading: Job? = null

    /** Starts the read unless it is done or already under way. */
    @Synchronized
    fun ensure() {
        if (_tags.value != null || loading?.isActive == true) return
        loading = scope.launch {
            _tags.value = try {
                read()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("reading the catalogue's filter lists failed", e)
                null
            }
        }
    }

    /**
     * The lists, waited for up to [patience] when a read is under way. Null when they
     * could not be had in that time, which a caller treats as not knowing.
     */
    suspend fun await(patience: Duration): CatalogueTags? {
        ensure()
        _tags.value?.let { return it }
        val running = synchronized(this) { loading }
        withTimeoutOrNull(patience) { running?.join() }
        return _tags.value
    }

    companion object {
        private val log: Logger = LoggerFactory.getLogger(CatalogueTagsService::class.java)

        /** The three lists read side by side. */
        suspend fun readAll(
            versions: suspend () -> List<ModrinthGameVersion>,
            categories: suspend () -> List<ModrinthCategoryTag>,
            loaders: suspend () -> List<ModrinthLoaderTag>,
        ): CatalogueTags = coroutineScope {
            val v = async { versions() }
            val c = async { categories() }
            val l = async { loaders() }
            catalogueTags(v.await(), c.await(), l.await())
        }
    }
}

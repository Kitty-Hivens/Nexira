package hivens.catalogue

import hivens.catalogue.api.CatalogueTags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.time.Duration

/**
 * The lists the filters are built from, as the project source answers them: its game
 * versions, its categories with the group each is filed under, and its loaders.
 *
 * Read once per run, on the first reader that asks. They change when Mojang ships or
 * the source adds a category, which a launcher restart covers. A read that failed is
 * tried again by the next reader that asks, rather than leaving the filters empty for
 * the rest of the run, and so is one made while no project source was brought in.
 */
class CatalogueTagsService(
    /** Reads the three lists from the project source, or null where there is none to ask. */
    private val read: suspend () -> CatalogueTags?,
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
            } catch (e: Throwable) {
                if (!e.isSourceFault) throw e
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

    private companion object {
        val log: Logger = LoggerFactory.getLogger(CatalogueTagsService::class.java)
    }
}

package hivens.ui.feature.catalogue.browse

import hivens.core.api.dto.modrinth.ModrinthCategoryTag
import hivens.core.api.dto.modrinth.ModrinthGameVersion
import hivens.core.api.dto.modrinth.ModrinthLoaderTag
import hivens.launcher.modrinth.ModrinthClient
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
import org.slf4j.LoggerFactory
import kotlin.time.Duration

/**
 * The catalogue's own lists the filters are built from: its game versions, its
 * categories with the group each is filed under, and its loaders.
 *
 * Read once per run, on the first filter that asks. They change when Mojang ships
 * or the catalogue adds a category, which a launcher restart covers. A read that
 * failed is tried again by the next widget that asks, rather than leaving the
 * filters empty for the rest of the run.
 */
class BrowseTags(
    private val modrinth: ModrinthClient,
    private val scope: CoroutineScope,
    /** Lists already in hand, for a render sheet that has no catalogue to ask. */
    initial: Tags? = null,
) {

    class Tags(
        val gameVersions: List<ModrinthGameVersion>,
        val categories: List<ModrinthCategoryTag>,
        val loaders: List<ModrinthLoaderTag>,
    )

    private val _tags = MutableStateFlow(initial)
    val tags: StateFlow<Tags?> = _tags.asStateFlow()

    private var loading: Job? = null

    /** Starts the read unless it is done or already under way. */
    fun ensure() {
        if (_tags.value != null || loading?.isActive == true) return
        loading = scope.launch {
            _tags.value = try {
                coroutineScope {
                    val versions = async { modrinth.gameVersions() }
                    val categories = async { modrinth.categoryTags() }
                    val loaders = async { modrinth.loaderTags() }
                    Tags(versions.await(), categories.await(), loaders.await())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("reading the catalogue's filter lists failed", e)
                null
            }
        }
    }

    /**
     * The lists, waited for up to [patience] when a read is under way. Null when
     * they could not be had in that time, which a caller treats as not knowing.
     */
    suspend fun await(patience: Duration): Tags? {
        ensure()
        _tags.value?.let { return it }
        withTimeoutOrNull(patience) { loading?.join() }
        return _tags.value
    }

    private companion object {
        val log = LoggerFactory.getLogger(BrowseTags::class.java)
    }
}

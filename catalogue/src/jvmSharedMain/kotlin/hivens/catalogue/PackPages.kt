package hivens.catalogue

import hivens.catalogue.api.CatalogueTags
import hivens.catalogue.api.OpenProject
import hivens.catalogue.api.PackDetails
import hivens.catalogue.api.PackPageStatus
import hivens.catalogue.api.PackPageView
import hivens.catalogue.api.ProjectSource
import hivens.catalogue.api.gameVersionChips
import hivens.catalogue.api.packTargetKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * A pack's page in the catalogue, whichever source lists it, as [PackPageView], and
 * what it publishes for the rail in the terms a project page publishes in.
 *
 * Opens on the page as it was last read, not on a spinner: the details are the same on
 * the way back as they were on the way in. Every answer the source gives is taken, the
 * stored one and the fresh one behind it, so the page and its install offer what the
 * source says now rather than whatever the cache held.
 *
 * One per page level, each showing a visit of its own the way [ProjectPageModel] does.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PackPageModel(
    /** The pack source with an id, opened, or null where none is brought in. */
    private val catalogueFor: (origin: String) -> OpenPackSource?,
    private val snapshots: PackSnapshots,
    private val open: OpenProjects,
    private val tags: StateFlow<CatalogueTags?>,
    /** Starts reading [tags] where they are not read yet. */
    private val ensureTags: () -> Unit,
    scope: CoroutineScope,
    /** Moves when the modules exchange a source for another instance of it, which the page reads again from. */
    private val sourceGeneration: StateFlow<Int> = MutableStateFlow(0),
) {
    private val scope = CoroutineScope(scope.coroutineContext + Dispatchers.Default.limitedParallelism(1))

    private val _view = MutableStateFlow<PackPageView?>(null)
    val view: StateFlow<PackPageView?> = _view.asStateFlow()

    private var loading: Job? = null
    private var publishing: Job? = null

    /**
     * Shows the page of [packId] from [origin] for [visit]. A new visit opens at once on
     * what was last read, so a screen drawn right after paints it, and reads it again.
     */
    fun show(origin: String, packId: String, visit: String) {
        val fresh = synchronized(this) {
            if (_view.value?.visit == visit) return@synchronized false
            loading?.cancel()
            publishing?.cancel()
            val kept = snapshots.details(origin, packId)
            _view.value = PackPageView(origin, packId, visit, kept?.let { PackPageStatus.Loaded(it) } ?: PackPageStatus.Loading)
            true
        }
        open.claim(visit)
        if (!fresh) return
        ensureTags()
        synchronized(this) {
            publishing = scope.launch { follow(visit) }
            loading = scope.launch { sourceGeneration.collectLatest { load(visit) } }
        }
    }

    /** The visit [visit] left the screen: its read stops, and the rail goes back to what it showed before. */
    fun hide(visit: String) {
        synchronized(this) {
            if (_view.value?.visit == visit) {
                loading?.cancel()
                publishing?.cancel()
            }
        }
        open.release(visit)
    }

    /** Reads the pack again after a read that failed. */
    fun retry() {
        synchronized(this) {
            val visit = _view.value?.visit ?: return
            loading?.cancel()
            loading = scope.launch { sourceGeneration.collectLatest { load(visit) } }
        }
    }

    private fun edit(visit: String, change: (PackPageView) -> PackPageView) {
        _view.update { current -> if (current?.visit == visit) change(current) else current }
    }

    private suspend fun load(visit: String) {
        val page = _view.value?.takeIf { it.visit == visit } ?: return
        // Only a page with nothing on it says it is loading. A refresh behind a page
        // already read replaces it when it lands.
        if (page.status !is PackPageStatus.Loaded) edit(visit) { it.copy(status = PackPageStatus.Loading) }
        // A page kept from before its source left still shows what it read.
        val catalogue = catalogueFor(page.origin)
            ?: return edit(visit) { if (it.status is PackPageStatus.Loaded) it else it.copy(status = PackPageStatus.Failed()) }
        try {
            catalogue.listing.detailsStream(page.packId).flowOn(Dispatchers.IO).collect { details ->
                snapshots.putDetails(page.origin, page.packId, details)
                edit(visit) { it.copy(status = PackPageStatus.Loaded(details)) }
            }
        } catch (e: Throwable) {
            if (!e.isSourceFault) throw e
            // A source that failed while a page of its own is shown keeps showing it.
            edit(visit) { if (it.status is PackPageStatus.Loaded) it else it.copy(status = PackPageStatus.Failed(e.message)) }
        }
    }

    /** Hands the rail what the page knows, again whenever the page or the catalogue's lists change. */
    private suspend fun follow(visit: String) {
        combine(_view, tags) { page, lists -> page to lists }.collect { (page, lists) ->
            if (page?.visit != visit) return@collect
            val targetKey = packTargetKey(page.origin, page.packId)
            val details = page.details
            open.publish(
                visit,
                when {
                    details != null -> openPackOf(targetKey, details, lists)
                    // A page that could not be read has no facts to wait for.
                    page.status is PackPageStatus.Failed -> null
                    else -> OpenProject(targetKey = targetKey, title = page.packId, slug = page.packId, source = ProjectSource.Catalogue, pending = true)
                },
            )
            details?.let { open.name(targetKey, it.title) }
        }
    }
}

/** What the rail's project view draws for a pack, in the terms it draws a project in. */
internal fun openPackOf(targetKey: String, d: PackDetails, tags: CatalogueTags?): OpenProject {
    val latest = d.latest
    return OpenProject(
        targetKey = targetKey,
        title = d.title,
        slug = d.slug ?: d.id,
        source = ProjectSource.Catalogue,
        projectType = d.projectType,
        gameVersions = gameVersionChips(d.gameVersions, tags?.gameVersions.orEmpty()),
        loaders = d.loaders,
        categories = d.tags,
        clientSide = d.clientSide,
        serverSide = d.serverSide,
        licenseId = d.licenseId,
        licenseName = d.licenseName,
        published = d.publishedAt?.takeIf { it.isNotBlank() },
        updated = d.updatedAt?.takeIf { it.isNotBlank() },
        links = d.links,
        creators = d.creators,
        sizeBytes = latest?.sizeBytes,
        runtime = d.runtimeLabel,
        signIn = d.signIn,
        modsCount = latest?.modsCount,
        // A source that names a licence or a first release names them always, so neither
        // present means the source has no such field to fill.
        answersLicence = d.licenseId != null || d.licenseName != null,
        answersPublished = d.publishedAt != null,
    )
}

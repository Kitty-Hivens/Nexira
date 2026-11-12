package hivens.ui.feature.catalogue.project

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import hivens.core.api.HttpClientProvider
import hivens.core.api.dto.modrinth.ModrinthProject
import hivens.core.api.dto.modrinth.ModrinthVersion
import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.PackInstance
import hivens.core.net.TransferEngine
import hivens.launcher.instance.InstanceContentScanner
import hivens.launcher.modrinth.ModrinthClient
import hivens.ui.components.GalleryMedia
import hivens.ui.i18n.EnglishStrings
import hivens.widget.model.ScopeLevel
import hivens.widget.model.SourceKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.file.Path

/** The project page's surface, see [ProjectPage]. */
internal const val PROJECT_SURFACE = "project"

/**
 * What the project page's widgets share: the page's state, the tab it shows, its
 * shots, and its ways out.
 *
 * The page is a widget surface, its header, tabs and body widgets in the
 * `project` surface's slots. They meet here rather than at a window-level service
 * the way Browse's do, because a project page is one visit with state of its own,
 * and two of them are on screen at once while the shell fades from one to the next.
 */
@Stable
internal class ProjectPage(
    val state: ModDetailState,
    tab: MutableState<ModPageTab>,
    /** The project's shots, which decide whether the gallery tab is offered. */
    val gallery: List<GalleryMedia>,
    /** Opens a build's own page. */
    val onOpenVersion: (ModrinthVersion) -> Unit,
    /** Asks for the page again, after a lookup that did not run. */
    val onReload: () -> Unit,
) {
    var tab: ModPageTab by tab
}

/**
 * The project page the widgets are drawn for, put over the page's own level by the
 * screen for the widgets it draws. Each of the two pages on screen during a fade
 * carries its own down its own subtree, so each widget reads its own page. Anywhere else the key is missing: a widget of
 * the page dropped on another surface has no project to describe.
 */
internal val ProjectPageSource: SourceKey<ProjectPage> = SourceKey("project.page", ScopeLevel.Page)

/**
 * A project page to show the page's widgets on where there is no project: the
 * palette, and the editor drawing one of them off its page. Display data only. The
 * page reaches nothing: its catalogue client refuses every request, it reads no
 * pack, and it reports to a project state of its own rather than to the rail.
 */
internal fun sampleProjectPage(): ProjectPage {
    val offline = HttpClientProvider { error("a sample page makes no requests") }
    val state = ModDetailState(
        target = ModTarget.Catalogue(SAMPLE_PROJECT.id),
        modrinth = ModrinthClient(offline, TransferEngine(offline)),
        repo = NoPacks,
        dataDir = Path.of("."),
        scanner = InstanceContentScanner(),
        open = OpenProjectState(),
        strings = EnglishStrings,
        installScope = CoroutineScope(Dispatchers.Default),
    )
    state.project = SAMPLE_PROJECT
    state.loading = false
    return ProjectPage(state, mutableStateOf(ModPageTab.Description), emptyList(), onOpenVersion = {}, onReload = {})
}

private val SAMPLE_PROJECT = ModrinthProject(
    id = "sample",
    slug = "sample",
    title = "Sample Project",
    description = "A project as this page shows one.",
    body = "What a project says about itself appears here.",
    categories = listOf("optimization", "utility"),
    downloads = 1_250_000,
    followers = 4_200,
    loaders = listOf("fabric", "neoforge"),
    gameVersions = listOf("1.21.1"),
    clientSide = "required",
    serverSide = "optional",
)

private object NoPacks : IPackRepository {
    override fun observe(): StateFlow<List<PackInstance>> = MutableStateFlow(emptyList())
    override suspend fun list(): List<PackInstance> = emptyList()
    override suspend fun get(id: String): PackInstance? = null
    override suspend fun put(instance: PackInstance) = Unit
    override suspend fun delete(id: String) = Unit
}

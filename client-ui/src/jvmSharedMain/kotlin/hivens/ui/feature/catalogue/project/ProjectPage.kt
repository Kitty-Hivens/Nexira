package hivens.ui.feature.catalogue.project

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import hivens.catalogue.api.ProjectBuild
import hivens.catalogue.api.ProjectDetails
import hivens.catalogue.api.ProjectPageView
import hivens.catalogue.api.ProjectTarget
import hivens.ui.components.GalleryMedia
import hivens.widget.model.ScopeLevel
import hivens.widget.model.SourceKey

/** The project page's surface, see [ProjectPage]. */
internal const val PROJECT_SURFACE = "project"

/**
 * What the project page's widgets share: the page as the catalogue module read it, the
 * tab it shows, its shots, and its way to a build's own page.
 *
 * The page is a widget surface, its header, tabs and body widgets in the `project`
 * surface's slots. They meet here rather than at the window the way Browse's do,
 * because a project page is one visit with state of its own, and two of them are on
 * screen at once while the shell fades from one to the next. What the page knows and
 * does is the catalogue module's, read through [hivens.catalogue.api.CatalogueKeys.ProjectPage]
 * and changed through its page commands, which the widgets fire themselves.
 */
@Stable
internal class ProjectPage(
    val view: ProjectPageView,
    tab: MutableState<ModPageTab>,
    /** The project's shots, which decide whether the gallery tab is offered. */
    val gallery: List<GalleryMedia>,
    /** Opens a build's own page. */
    val onOpenVersion: (ProjectBuild) -> Unit,
) {
    var tab: ModPageTab by tab
}

/**
 * The project page the widgets are drawn for, put over the page's own level by the
 * screen for the widgets it draws. Each of the two pages on screen during a fade
 * carries its own down its own subtree, so each widget reads its own page. Anywhere
 * else the key is missing: a widget of the page dropped on another surface has no
 * project to describe.
 */
internal val ProjectPageSource: SourceKey<ProjectPage> = SourceKey("project.page", ScopeLevel.Page)

/**
 * A project page to show the page's widgets on where there is no project: the palette,
 * and the editor drawing one of them off its page. Display data only: its commands are
 * the page level's, and where the palette draws it there is no page to answer them.
 */
internal fun sampleProjectPage(): ProjectPage =
    ProjectPage(
        view = ProjectPageView(
            target = ProjectTarget.Catalogue(SAMPLE_PROJECT.id),
            visit = "sample",
            loading = false,
            project = SAMPLE_PROJECT,
        ),
        tab = mutableStateOf(ModPageTab.Description),
        gallery = emptyList(),
        onOpenVersion = {},
    )

private val SAMPLE_PROJECT = ProjectDetails(
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

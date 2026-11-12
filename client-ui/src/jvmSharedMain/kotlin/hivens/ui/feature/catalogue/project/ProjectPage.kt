package hivens.ui.feature.catalogue.project

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import hivens.core.api.dto.modrinth.ModrinthVersion
import hivens.ui.components.GalleryMedia
import hivens.widget.model.ScopeLevel
import hivens.widget.model.SourceKey

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

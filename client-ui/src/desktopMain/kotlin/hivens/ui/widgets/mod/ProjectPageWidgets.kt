package hivens.ui.widgets.mod

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import hivens.ui.screens.mod.Header
import hivens.ui.screens.mod.LocalProjectPage
import hivens.ui.screens.mod.ProjectPageBody
import hivens.ui.screens.mod.Tabs
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance

/**
 * The project page's widgets: the header, the tabs and the pane under them. Each
 * reads the page it is drawn on through [LocalProjectPage] and draws nothing where
 * there is none.
 */

@Widget(id = "project.header", displayName = "widget.project.header")
@Composable
fun ProjectHeaderWidget(instance: WidgetInstance) {
    val page = LocalProjectPage.current ?: return
    Header(page.state)
}

/**
 * The tabs are page chrome, above the body and outside its scroll. They used to be
 * the first thing inside it, so opening a long description and reading two screens
 * down left no way back to Versions without scrolling to the top first.
 */
@Widget(id = "project.tabs", displayName = "widget.project.tabs")
@Composable
fun ProjectTabsWidget(instance: WidgetInstance) {
    val page = LocalProjectPage.current ?: return
    Tabs(
        active = page.tab,
        onSelect = { page.tab = it },
        hasGallery = page.gallery.isNotEmpty(),
        modifier = Modifier.padding(top = 12.dp, bottom = 10.dp),
    )
}

// The ceiling is load-bearing: the versions pane lists lazily, and a lazy list
// cannot be measured against an unbounded axis.
@Widget(
    id = "project.body",
    displayName = "widget.project.body",
    minWidth = 320, minHeight = 200,
    maxWidth = 2400, maxHeight = 1600,
)
@Composable
fun ProjectBodyWidget(instance: WidgetInstance) {
    val page = LocalProjectPage.current ?: return
    ProjectPageBody(page, Modifier.fillMaxSize())
}

package hivens.ui.feature.catalogue.browse

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import hivens.ui.feature.catalogue.browse.CataloguePackPageBody
import hivens.ui.feature.catalogue.browse.CataloguePackPageHeader
import hivens.ui.feature.catalogue.browse.CataloguePackPageSource
import hivens.ui.feature.catalogue.browse.CataloguePackPageTabs
import hivens.widget.api.rememberScopedSource
import hivens.widget.api.valueOrNull
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance

/**
 * A catalogue pack's page as widgets: the header, the tabs and the pane under
 * them. Each reads the page it is drawn on through [CataloguePackPageSource], and a
 * slot anywhere else draws the placeholder for a widget whose page is not there.
 */

@Widget(id = "catalogue.pack.header", displayName = "widget.catalogue.pack.header", needs = ["catalogue.pack.page"])
@Composable
fun CataloguePackHeaderWidget(instance: WidgetInstance) {
    val page = rememberScopedSource(CataloguePackPageSource).valueOrNull() ?: return
    CataloguePackPageHeader(page)
}

@Widget(id = "catalogue.pack.tabs", displayName = "widget.catalogue.pack.tabs", needs = ["catalogue.pack.page"])
@Composable
fun CataloguePackTabsWidget(instance: WidgetInstance) {
    val page = rememberScopedSource(CataloguePackPageSource).valueOrNull() ?: return
    CataloguePackPageTabs(page)
}

// The ceiling is load-bearing: the versions table lists lazily, and a lazy list
// cannot be measured against an unbounded axis.
@Widget(
    id = "catalogue.pack.body",
    displayName = "widget.catalogue.pack.body",
    minWidth = 320, minHeight = 200,
    maxWidth = 2400, maxHeight = 1600,
    needs = ["catalogue.pack.page"],
)
@Composable
fun CataloguePackBodyWidget(instance: WidgetInstance) {
    val page = rememberScopedSource(CataloguePackPageSource).valueOrNull() ?: return
    CataloguePackPageBody(page, Modifier.fillMaxSize())
}

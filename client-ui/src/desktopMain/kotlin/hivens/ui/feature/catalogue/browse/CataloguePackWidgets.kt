package hivens.ui.feature.catalogue.browse

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import hivens.ui.feature.catalogue.browse.CataloguePackPageBody
import hivens.ui.feature.catalogue.browse.CataloguePackPageHeader
import hivens.ui.feature.catalogue.browse.CataloguePackPageTabs
import hivens.ui.feature.catalogue.browse.LocalCataloguePackPage
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance

/**
 * A catalogue pack's page as widgets: the header, the tabs and the pane under
 * them. Each reads the page it is drawn on through [LocalCataloguePackPage] and
 * draws nothing where there is none.
 */

@Widget(id = "catalogue.pack.header", displayName = "widget.catalogue.pack.header")
@Composable
fun CataloguePackHeaderWidget(instance: WidgetInstance) {
    val page = LocalCataloguePackPage.current ?: return
    CataloguePackPageHeader(page)
}

@Widget(id = "catalogue.pack.tabs", displayName = "widget.catalogue.pack.tabs")
@Composable
fun CataloguePackTabsWidget(instance: WidgetInstance) {
    val page = LocalCataloguePackPage.current ?: return
    CataloguePackPageTabs(page)
}

// The ceiling is load-bearing: the versions table lists lazily, and a lazy list
// cannot be measured against an unbounded axis.
@Widget(
    id = "catalogue.pack.body",
    displayName = "widget.catalogue.pack.body",
    minWidth = 320, minHeight = 200,
    maxWidth = 2400, maxHeight = 1600,
)
@Composable
fun CataloguePackBodyWidget(instance: WidgetInstance) {
    val page = LocalCataloguePackPage.current ?: return
    CataloguePackPageBody(page, Modifier.fillMaxSize())
}

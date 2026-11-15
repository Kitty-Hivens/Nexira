package hivens.ui.host

import hivens.catalogue.SourceShelf
import hivens.core.data.PackOrigin
import hivens.launcher.sources.SourcePackWork
import hivens.widget.api.WidgetScope
import hivens.widget.api.find

/**
 * What the engine does to a pack, by the source the pack came from, as the catalogue opened
 * that source under [app]: the work a module compiled into the launcher brought on its
 * source's listing, nothing beyond listing for a jar's source or one that brought none, and
 * null where no source of that id is loaded. Read at every question, so a module brought in,
 * taken away or loaded again is what the next one reads, and a document that brings in no
 * catalogue has no source loaded.
 */
internal fun sourceWorksOf(app: WidgetScope): (PackOrigin) -> SourcePackWork? = { origin ->
    app.launcherServices.find<SourceShelf>()?.work(origin.id)
}

package hivens.pages.api

import hivens.widget.model.CommandKey
import hivens.widget.model.ScopeLevel
import hivens.widget.model.SourceKey

/** What opens a page. */
object PagesKeys {
    /**
     * Shows a config, with the values to open its page with. One the composition has no config for shows nothing new,
     * and what is shown stays.
     */
    val Show = CommandKey<Show>("nav.show", ScopeLevel.Composition)
}

/** What every page answers for the widgets drawn in it. */
object PageKeys {
    /**
     * What the page is drawn for. Provided by whoever draws the page, to the page's level rather than over it, so the
     * factories of that level read it too.
     */
    val Entry = SourceKey<HistoryEntry>("page.entry", ScopeLevel.Page)
}

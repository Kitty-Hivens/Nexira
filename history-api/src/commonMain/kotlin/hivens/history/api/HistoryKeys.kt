package hivens.history.api

import hivens.pages.api.HistoryEntry
import hivens.widget.model.CommandKey
import hivens.widget.model.ScopeLevel
import hivens.widget.model.SourceKey
import kotlinx.serialization.json.JsonElement

/** Where the pages a composition showed have been, and the commands that step through them. */
object HistoryKeys {
    /** Where the history stands. Moved by the commands here and by every page shown. */
    val History = SourceKey<History>("nav.state", ScopeLevel.Composition)

    /** Steps back one entry of the history. At its root it moves nothing. */
    val Back = CommandKey<Unit>("nav.back", ScopeLevel.Composition)

    /** Steps forward to the entry back last stepped off. */
    val Forward = CommandKey<Unit>("nav.forward", ScopeLevel.Composition)

    /** Steps back to the nearest entry of this one's page. */
    val BackTo = CommandKey<HistoryEntry>("nav.back.to", ScopeLevel.Composition)

    /** Rewrites values of the entry shown, see [History.rewrite]. A null takes one out. */
    val Rewrite = CommandKey<Map<String, JsonElement?>>("nav.rewrite", ScopeLevel.Composition)
}

package hivens.widget.api

import hivens.widget.model.CommandKey
import hivens.widget.model.CompositionName
import hivens.widget.model.History
import hivens.widget.model.HistoryEntry
import hivens.widget.model.PageValue
import hivens.widget.model.ScopeLevel
import hivens.widget.model.Show
import hivens.widget.model.SourceKey
import hivens.widget.model.WidgetDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import kotlinx.serialization.json.JsonElement

/**
 * What every composition answers for the widgets drawn in it, whoever made it: where its history stands and the
 * commands that move it. The kernel's own keys rather than a module's, since every composition has a history, and they
 * name configs by id, so no widget needs a type of the host's to reach one.
 */
object CompositionKeys {
    /** Where the composition's history stands. Moved by the commands here. */
    val History = SourceKey<History>("nav.state", ScopeLevel.Composition)

    /**
     * Shows a config, with the values to open its page with. One the composition has no config for shows nothing new,
     * and the history stays where it was.
     */
    val Show = CommandKey<Show>("nav.show", ScopeLevel.Composition)

    /** Steps back one entry of the history. At its root it moves nothing. */
    val Back = CommandKey<Unit>("nav.back", ScopeLevel.Composition)

    /** Steps forward to the entry back last stepped off. */
    val Forward = CommandKey<Unit>("nav.forward", ScopeLevel.Composition)

    /** Steps back to the nearest entry of this one's page. */
    val BackTo = CommandKey<HistoryEntry>("nav.back.to", ScopeLevel.Composition)

    /** Rewrites values of the entry shown, see [hivens.widget.model.History.rewrite]. A null takes one out. */
    val Rewrite = CommandKey<Map<String, JsonElement?>>("nav.rewrite", ScopeLevel.Composition)

    /** The compositions shown in a window this composition opened, which go when it goes. */
    val Windows = SourceKey<Set<CompositionName>>("nav.windows", ScopeLevel.Composition)

    /**
     * Shows a composition of the document in a window this composition owns, a picker say, or brings the one it is
     * shown in to the front. The window goes when this composition goes, so nothing it opened is left behind with
     * nowhere to answer. One the document does not have opens nothing. A host with one window shows it over that
     * window instead. A window apart from any composition is [WindowKeys.Show]'s.
     */
    val ShowWindow = CommandKey<CompositionName>("nav.window.show", ScopeLevel.Composition)

    /** Closes a window this composition opened. */
    val CloseWindow = CommandKey<CompositionName>("nav.window.close", ScopeLevel.Composition)
}

/** What every page answers for the widgets drawn in it: the place in the history it is drawn for. */
object PageKeys {
    /**
     * The entry a page is drawn for. Provided by whoever draws the page, to the page's level rather than over it, so
     * the factories of that level read it too.
     */
    val Entry = SourceKey<HistoryEntry>("page.entry", ScopeLevel.Page)
}

/**
 * A source of what an opening hands the page it opens as [value], null on a page opened without it. It moves as the
 * page rewrites the value. [sample] is display data for the palette and the editor.
 */
fun <T : Any> ModuleKeys.pageValue(value: PageValue<T>, sample: (() -> T)? = null) = source(value.key, sample) { page -> page.read(value) }

/** [value] as this level's page was opened with it, for a factory of the page or of the host. See [pageValue]. */
fun <T : Any> LevelContext.read(value: PageValue<T>): WidgetDataSource<T?> {
    val entry = find(PageKeys.Entry)?.state ?: return flowSource(MutableStateFlow(null))
    // Unconfined, so whoever moves the entry leaves the value moved: a page composed with its new entry reads the new
    // value on that pass and not a frame later.
    val scope = coroutineScope + Dispatchers.Unconfined
    return flowSource(entry.map { value.read(it.values) }.stateIn(scope, SharingStarted.Eagerly, value.read(entry.value.values)))
}

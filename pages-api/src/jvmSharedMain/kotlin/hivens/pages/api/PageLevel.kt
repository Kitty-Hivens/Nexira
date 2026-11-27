package hivens.pages.api

import hivens.widget.api.LevelContext
import hivens.widget.api.ModuleKeys
import hivens.widget.api.PageLevels
import hivens.widget.api.WidgetScope
import hivens.widget.api.flowSource
import hivens.widget.model.WidgetDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus

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

/**
 * The level of the page [entry] shows, made the first time it is asked for, which answers [PageKeys.Entry] with the
 * entry the page was last asked for with. A page whose entry was rewritten is asked for with the new one, and its
 * widgets read it at once.
 */
fun PageLevels.levelFor(entry: HistoryEntry): WidgetScope = levelFor(entry.page, PageKeys.Entry, entry)

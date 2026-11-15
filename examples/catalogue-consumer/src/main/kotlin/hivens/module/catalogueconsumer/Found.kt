package hivens.module.catalogueconsumer

import androidx.compose.runtime.Composable
import hivens.catalogue.api.CatalogueKeys
import hivens.catalogue.api.SearchResults
import hivens.catalogue.api.SearchStatus
import hivens.widget.api.KeyContribution
import hivens.widget.api.ModuleKeys
import hivens.widget.api.flowSource
import hivens.widget.api.rememberScopedCommand
import hivens.widget.api.rememberScopedSource
import hivens.widget.api.valueOrNull
import hivens.widget.model.ScopeLevel
import hivens.widget.model.SourceKey
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * The keys this module answers. A class with a public constructor, because a jar's
 * contributions are made by the service loader.
 */
class FoundKeys : KeyContribution {
    override val id: String = "catalogue-consumer.keys"
    override val label: String = "Found"

    companion object {
        /**
         * The titles the window's search found, projects and packs alike, or empty while
         * it has found nothing. Made from the catalogue module's results, and missing
         * where that module is.
         */
        val Titles: SourceKey<List<String>> = SourceKey("catalogue-consumer.titles", ScopeLevel.Window)
    }

    override fun declare(keys: ModuleKeys) {
        keys.source(Titles, sample = { listOf("Sample Project") }) { window ->
            val results = checkNotNull(window.find(CatalogueKeys.Results)) { "the catalogue module is not brought in" }.state
            flowSource(results.map(::titles).stateIn(window.coroutineScope, SharingStarted.Eagerly, titles(results.value)))
        }
    }

    private fun titles(results: SearchResults): List<String> =
        if (results.status != SearchStatus.Loaded) emptyList() else results.projects.map { it.title } + results.packs.map { it.title }
}

/**
 * A widget body over the catalogue keys: what the window's search found, and a way to
 * ask it for other words. What it read and the search it got are handed to [seen], so
 * a test can see them.
 */
@Composable
fun SearchBox(seen: (results: SearchResults?, search: ((String) -> Unit)?) -> Unit) {
    val results = rememberScopedSource(CatalogueKeys.Results).valueOrNull()
    val search = rememberScopedCommand(CatalogueKeys.SetQuery)
    seen(results, search)
}

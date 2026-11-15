package hivens.ui.feature.catalogue.browse

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import hivens.catalogue.api.CatalogueKeys
import hivens.catalogue.api.CatalogueTags
import hivens.catalogue.api.ENV_CLIENT
import hivens.catalogue.api.ENV_SERVER
import hivens.catalogue.api.FilterField
import hivens.catalogue.api.ProjectKind
import hivens.catalogue.api.ProjectTypes
import hivens.catalogue.api.SearchFilter
import hivens.catalogue.api.TagSearch
import hivens.ui.Screen
import hivens.ui.feature.catalogue.project.Environment
import hivens.widget.api.LocalScope
import hivens.widget.api.rememberScopedCommand
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * One fact on a project's page that the catalogue can search by: a category, a
 * loader, a span of game versions, or where it runs.
 */
sealed interface ProjectTag {
    data class Category(val name: String) : ProjectTag
    data class Loader(val name: String) : ProjectTag

    /** A chip of the folded version list, standing for every version behind it. */
    data class GameVersions(val versions: List<String>) : ProjectTag
    data class Runs(val environment: Environment) : ProjectTag
}

/**
 * A search started from outside Browse: a tag on a project page opens the
 * catalogue narrowed by that tag, the way the catalogue's own pages do.
 */
fun interface CatalogueSearch {
    /** Opens Browse on [tag] alone for [type], with [packId] as the pack installs go into. */
    fun open(type: String, tag: ProjectTag, packId: String?)
}

/**
 * How a tag opens a search here.
 *
 * A composition local for the reason the link follower is one: the tags are drawn
 * by rail widgets and a page header, which must draw with no application around
 * them. With nothing provided a tag stays a plain label.
 */
val LocalCatalogueSearch: ProvidableCompositionLocal<CatalogueSearch?> = compositionLocalOf { null }

/**
 * The shell's search: the tag becomes the window's question, and Browse opens on it.
 * Null where no catalogue module is brought in, so a tag stays a plain label.
 *
 * Waits a moment for the catalogue's lists when they are still on their way. A
 * category reads as a resolution only by the group the lists file it under, and a
 * tag clicked before they landed searched as a plain category, which Browse then
 * showed chosen in the wrong group.
 */
@Composable
fun rememberShellCatalogueSearch(currentScreen: Screen, onScreenChange: (Screen) -> Unit): CatalogueSearch? {
    val searchFor = rememberScopedCommand(CatalogueKeys.SearchFor) ?: return null
    val loadTags = rememberScopedCommand(CatalogueKeys.LoadTags)
    val tags = LocalScope.current?.find(CatalogueKeys.Tags)?.state
    val scope = rememberCoroutineScope()
    val screen = rememberUpdatedState(currentScreen)
    return remember(searchFor, loadTags, tags, onScreenChange, scope) {
        CatalogueSearch { type, tag, packId ->
            scope.launch {
                searchFromTag(
                    type, tag, packId,
                    known = { if (it is ProjectTag.Category) awaitTags(tags, loadTags, TAGS_PATIENCE) else tags?.value },
                    currentScreen = { screen.value },
                    onScreenChange = onScreenChange,
                    search = searchFor,
                )
            }
        }
    }
}

/** The catalogue's lists, asked for and waited for up to [patience]. Null when they could not be had in that time. */
private suspend fun awaitTags(tags: StateFlow<CatalogueTags?>?, load: ((Unit) -> Unit)?, patience: Duration): CatalogueTags? {
    val flow = tags ?: return null
    flow.value?.let { return it }
    load?.invoke(Unit)
    return withTimeoutOrNull(patience) { flow.first { it != null } } ?: flow.value
}

/**
 * Turns a clicked [tag] into Browse's question and opens Browse on it, unless the
 * reader has gone somewhere else while the lists were on their way. The wait runs
 * on the shell's scope, which outlives the page, and a search that landed seconds
 * later took the reader away from wherever they had gone since.
 */
internal suspend fun searchFromTag(
    type: String,
    tag: ProjectTag,
    packId: String?,
    known: suspend (ProjectTag) -> CatalogueTags?,
    currentScreen: () -> Screen,
    onScreenChange: (Screen) -> Unit,
    search: (TagSearch) -> Unit,
) {
    val from = currentScreen()
    val tags = known(tag)
    if (currentScreen() != from) return
    val filters = filtersFor(tag, type, tags) ?: return
    search(TagSearch(type, filters, packId))
    onScreenChange(Screen.Browse)
}

/** How long a tag waits for the catalogue's lists before it searches without them. */
private val TAGS_PATIENCE = 3.seconds

/**
 * What clicking [tag] does on a page about a project of [type], or null where it
 * does nothing: no search is provided, Browse does not list the type, or the tag
 * names no filter for it.
 */
@Composable
fun tagSearch(type: String?, packId: String?, tag: ProjectTag): (() -> Unit)? {
    val search = LocalCatalogueSearch.current ?: return null
    if (type == null || filtersFor(tag, type, null) == null) return null
    return { search.open(type, tag, packId) }
}

/** The project types Browse searches, with the kind it lists each under. Null is packs. */
internal val BROWSE_TYPES: Map<String, ProjectKind?> = mapOf(
    ProjectTypes.MOD to ProjectKind.Mod,
    ProjectTypes.RESOURCE_PACK to ProjectKind.ResourcePack,
    ProjectTypes.SHADER to ProjectKind.ShaderPack,
    MODPACK to null,
)

/**
 * The filters [tag] stands for in a search for [type], or null where it stands for
 * none.
 *
 * A category the catalogue files under resolutions is a resolution, which is how
 * the rail shows it chosen. Without the catalogue's lists it is taken as a plain
 * category, which finds the same projects as a lone choice. A resource pack's
 * loader is `minecraft` and nothing else, and the catalogue has no platform filter
 * for it.
 */
internal fun filtersFor(tag: ProjectTag, type: String, tags: CatalogueTags?): Set<SearchFilter>? {
    if (type !in BROWSE_TYPES) return null
    return when (tag) {
        is ProjectTag.Category -> {
            val header = tags?.categories?.firstOrNull { it.name == tag.name && it.projectType == type }?.header
            val field = if (header == FilterGroup.Resolutions.header) FilterField.Resolution else FilterField.Category
            setOf(SearchFilter(field, tag.name))
        }
        is ProjectTag.Loader -> if (type == ProjectTypes.RESOURCE_PACK) null else setOf(SearchFilter(FilterField.Loader, tag.name))
        is ProjectTag.GameVersions -> tag.versions.map { SearchFilter(FilterField.GameVersion, it) }.toSet().ifEmpty { null }
        is ProjectTag.Runs -> when (tag.environment) {
            Environment.Client -> setOf(SearchFilter(FilterField.Environment, ENV_CLIENT))
            Environment.Server -> setOf(SearchFilter(FilterField.Environment, ENV_SERVER))
            Environment.Both -> setOf(SearchFilter(FilterField.Environment, ENV_CLIENT), SearchFilter(FilterField.Environment, ENV_SERVER))
        }.takeIf { type == ProjectTypes.MOD || type == MODPACK }
    }
}

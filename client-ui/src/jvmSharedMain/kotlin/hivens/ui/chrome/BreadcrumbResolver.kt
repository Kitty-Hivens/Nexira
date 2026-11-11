package hivens.ui.chrome

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.PackOrigin
import hivens.ui.Screen
import hivens.ui.i18n.AppStrings
import hivens.ui.i18n.LocalStrings
import hivens.ui.feature.catalogue.browse.BrowseSession
import hivens.ui.feature.catalogue.browse.catalogueTargetKey
import hivens.ui.feature.catalogue.project.ModTarget
import hivens.ui.feature.catalogue.project.OpenProject
import hivens.ui.feature.catalogue.project.OpenProjectState
import hivens.widget.api.LocalLayoutGraph
import hivens.widget.model.screen
import org.koin.compose.koinInject

/**
 * Static Screen -> breadcrumb label. Pure (no Compose, no async) so the mapping
 * is unit-testable and stays exhaustive over [Screen]. Returns null for the
 * screens whose label is a pack name resolved asynchronously (installed pack,
 * Modrinth project, mirror pack).
 */
fun staticCrumbLabel(screen: Screen, s: AppStrings): String? = when (screen) {
    Screen.Home                   -> s.crumbHome
    Screen.Library                -> s.libraryHeaderTitle
    Screen.Browse                 -> s.browseTitle
    Screen.Profile                -> s.profileTitle
    Screen.Wardrobe               -> s.wardrobeTitle
    Screen.Settings               -> s.settingsTitle
    Screen.ThemePicker            -> s.themePickerTitle
    Screen.About                  -> s.aboutTitle
    Screen.BackgroundSettings     -> s.backgroundTitle
    is Screen.PackVersions        -> s.packVersionsTitle
    is Screen.BrowseInto          -> s.contentFindProjects
    // Resolved to a human name by the catalogue / repository / roster (see below).
    is Screen.PackDetail          -> null
    is Screen.CataloguePackDetail -> null
    is Screen.ModDetail           -> null
    // The route carries the build's own number, so this one needs nothing fetched.
    is Screen.ModVersion          -> screen.versionNumber
    // Named in the layout graph, which a pure mapping cannot read.
    is Screen.Custom              -> null
}

/**
 * What a project page is called before anything has been fetched, and if nothing
 * ever is.
 *
 * A file answers with its own name minus the extension, which is what the reader
 * clicked and is recognisable even when the catalogue has never heard of it. A
 * catalogue entry answers with its id, which is usually the slug and reads as a
 * name.
 */
private fun modFallbackLabel(target: ModTarget): String = when (target) {
    is ModTarget.Catalogue -> target.projectId
    is ModTarget.Installed -> target.fileName.substringBeforeLast('.')
}

/**
 * Human label for a breadcrumb segment. Pack-detail screens resolve a real name
 * (installed pack via the repository, Browse/Modrinth via the catalogue) instead
 * of showing the raw id; the id is the placeholder until it loads, and the
 * fallback if resolution fails. Everything else is the synchronous [staticCrumbLabel].
 */
@Composable
fun rememberCrumbLabel(screen: Screen): String {
    val s = LocalStrings.current
    staticCrumbLabel(screen, s)?.let { return it }
    return when (screen) {
        is Screen.PackDetail -> {
            val repo: IPackRepository = koinInject()
            // Read once, this crumb kept the name the pack had when it was first
            // shown: the rename happens in place, without navigating, and the top
            // bar is chrome that is never disposed, so only visiting a different
            // pack could ever fix it. The registry always has a value, so there is
            // no loading state to pass through -- an id the registry does not hold
            // is a pack that is gone, and the id itself is the honest label for it.
            val instances by remember { repo.observe() }.collectAsState()
            instances.firstOrNull { it.id == screen.instanceId }?.displayName
                ?: screen.instanceId
        }
        is Screen.CataloguePackDetail -> catalogueCrumb(screen.origin, screen.packId)
        is Screen.ModDetail           -> modCrumb(screen.target)
        // Read live, so a rename in the editor relabels the crumb at once. A screen
        // deleted while it was open says so rather than naming nothing.
        is Screen.Custom              ->
            LocalLayoutGraph.current.screen(screen.id)?.title?.ifBlank { null } ?: s.screenMissing
        else -> staticCrumbLabel(screen, s).orEmpty() // unreachable: statics returned above
    }
}

/**
 * A project page's title, taken from what the page itself published rather than
 * fetched a second time.
 *
 * Guarded on the target, because the trail can hold an entry that is not the
 * screen on top: an unguarded read would label every project crumb with whatever
 * page is open now. A page already left answers with the name it had, and one
 * never loaded with the route's own name, which is what the reader clicked.
 */
@Composable
private fun modCrumb(target: ModTarget): String {
    val state: OpenProjectState = koinInject()
    val open by state.open.collectAsState()
    val names by state.names.collectAsState()
    return modCrumbLabel(target, open, names)
}

/**
 * Only an answer is a name. A page still waiting publishes the route's own id as
 * its title, and taken from there it covered the name a visit before had left in
 * [names] until the page had heard back.
 */
internal fun modCrumbLabel(target: ModTarget, open: OpenProject?, names: Map<String, String>): String =
    open?.takeIf { it.targetKey == target.key && !it.pending }?.title
        ?: names[target.key]
        ?: modFallbackLabel(target)

/**
 * A catalogue pack's title, from what its page published or last read, the way a
 * project crumb is. Asking the catalogue for the page a second time cost a whole
 * page read per visit, a Modrinth pack's every build and its team included. The id
 * stands in until the page has read the pack, and for one it never could.
 */
@Composable
private fun catalogueCrumb(origin: PackOrigin, id: String): String {
    val state: OpenProjectState = koinInject()
    val session: BrowseSession = koinInject()
    val names by state.names.collectAsState()
    return names[catalogueTargetKey(origin, id)] ?: session.details(origin, id)?.title ?: id
}

package hivens.ui.chrome

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.PackOrigin
import hivens.launcher.catalogue.PackCatalogueRegistry
import hivens.ui.Screen
import hivens.ui.i18n.AppStrings
import hivens.ui.i18n.LocalStrings
import hivens.ui.screens.ServerResolution
import hivens.ui.screens.rememberServerResolution
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
    // Resolved to a human name by the catalogue / repository / roster (see below).
    is Screen.ServerSettings      -> null
    is Screen.ServerDetails       -> null
    is Screen.PackDetail          -> null
    is Screen.CataloguePackDetail -> null
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
            // pack could ever fix it. Collecting the registry is also what puts the
            // loading placeholder back on a key change -- the flow re-emits for the
            // new id rather than leaving the previous pack's name up.
            val instances by remember { repo.observe() }.collectAsState(initial = null)
            instances?.firstOrNull { it.id == screen.instanceId }?.displayName
                ?: instances?.let { screen.instanceId }
                ?: s.crumbLoading
        }
        is Screen.ServerSettings      -> serverCrumb(screen.serverId)
        is Screen.ServerDetails       -> serverCrumb(screen.serverId)
        is Screen.CataloguePackDetail -> catalogueCrumb(screen.origin, screen.packId, s.crumbLoading)
        else -> staticCrumbLabel(screen, s).orEmpty() // unreachable: statics returned above
    }
}

/**
 * A server's display name from the roster; its id -- which is what the route
 * carries -- is the placeholder and the fallback while the roster is unreachable.
 */
@Composable
private fun serverCrumb(serverId: String): String =
    (rememberServerResolution(serverId) as? ServerResolution.Ready)
        ?.server?.let { it.title?.ifBlank { null } ?: it.name }
        ?: serverId

/** Resolve a catalogue pack's display title by id (cached when the detail screen
 *  has already fetched it); the raw id is the placeholder + failure fallback. */
@Composable
private fun catalogueCrumb(origin: PackOrigin, id: String, loading: String): String {
    val registry: PackCatalogueRegistry = koinInject()
    val label by produceState(initialValue = loading, origin, id) {
        value = loading
        value = runCatching { registry.forOrigin(origin)?.details(id)?.title }.getOrNull() ?: id
    }
    return label
}

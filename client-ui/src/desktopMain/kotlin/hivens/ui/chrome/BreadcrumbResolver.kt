package hivens.ui.chrome

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.PackOrigin
import hivens.launcher.catalogue.PackCatalogueRegistry
import hivens.ui.Screen
import hivens.ui.i18n.AppStrings
import hivens.ui.i18n.LocalStrings
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
    is Screen.ServerSettings      -> screen.server.title?.ifBlank { null } ?: screen.server.name
    is Screen.ServerDetails       -> screen.server.title?.ifBlank { null } ?: screen.server.name
    is Screen.PackVersions        -> s.packVersionsTitle
    // Resolved to a human pack name by the catalogue / repository (see below).
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
            val label by produceState(initialValue = s.crumbLoading, screen.instanceId) {
                value = runCatching { repo.get(screen.instanceId)?.displayName }.getOrNull() ?: screen.instanceId
            }
            label
        }
        is Screen.CataloguePackDetail -> catalogueCrumb(screen.origin, screen.packId, s.crumbLoading)
        else -> staticCrumbLabel(screen, s).orEmpty() // unreachable: statics returned above
    }
}

/** Resolve a catalogue pack's display title by id (cached when the detail screen
 *  has already fetched it); the raw id is the placeholder + failure fallback. */
@Composable
private fun catalogueCrumb(origin: PackOrigin, id: String, loading: String): String {
    val registry: PackCatalogueRegistry = koinInject()
    val label by produceState(initialValue = loading, origin, id) {
        value = runCatching { registry.forOrigin(origin)?.details(id)?.title }.getOrNull() ?: id
    }
    return label
}

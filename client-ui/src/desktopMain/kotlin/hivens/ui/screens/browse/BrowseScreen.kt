package hivens.ui.screens.browse

import androidx.compose.runtime.Composable
import hivens.ui.puppet.PuppetScreen
import hivens.ui.screens.common.PlaceholderScreen

/**
 * Browse = the catalog of everything installable per [[project_home_library_ia]]:
 * featured carousel (`/v1/featured`), all SC servers, all Hivens packs.
 * Filters by source / MC-version / tag; cards already in Library are flagged.
 *
 * Stub for now; awaits the mirror Browse endpoints to be wired and the
 * unified card component to land under Atelier.
 */
@Composable
fun BrowseScreen() {
    PuppetScreen("Browse")
    PlaceholderScreen(screenName = "Browse")
}

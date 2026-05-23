package hivens.ui.screens.library

import androidx.compose.runtime.Composable
import hivens.ui.puppet.PuppetScreen
import hivens.ui.screens.common.PlaceholderScreen

/**
 * Library = the user's personal collection per [[project_home_library_ia]]:
 * SC favorites + recently-played + installed Hivens packs, all as the same
 * unified card with a source badge.
 *
 * Stub for now; the real implementation lands under the Atelier UI rework.
 * Reachable via the LibraryFirst view-variant toggle in Settings.
 */
@Composable
fun LibraryScreen() {
    PuppetScreen("Library")
    PlaceholderScreen(screenName = "Library")
}

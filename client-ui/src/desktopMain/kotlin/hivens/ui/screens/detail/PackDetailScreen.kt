package hivens.ui.screens.detail

import androidx.compose.runtime.Composable
import hivens.core.api.model.ServerProfile
import hivens.ui.puppet.PuppetScreen
import hivens.ui.screens.common.PlaceholderScreen

/**
 * PackDetail = full-screen Modrinth-style detail page for a Library card
 * per [[project_home_library_ia]]. Holds: banner, summary, mod list with
 * display.name/category, Play action, Settings tab (instance prefs),
 * Logs tab.
 *
 * Stub for now; routed to when Library mode is active and the user clicks
 * into a card. Classic mode still uses the existing ServerDetailScreen.
 */
@Composable
fun PackDetailScreen(
    @Suppress("UNUSED_PARAMETER") server: ServerProfile,
    @Suppress("UNUSED_PARAMETER") onBack: () -> Unit,
) {
    PuppetScreen("PackDetail")
    PlaceholderScreen(screenName = "Pack detail")
}

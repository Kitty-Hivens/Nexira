package hivens.ui.widgets.shell

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import hivens.pages.api.PagesKeys
import hivens.ui.navigation.LauncherScreens
import hivens.ui.puppet.PuppetClick
import hivens.ui.screens.custom.ScreenIcons
import hivens.widget.api.rememberScopedCommand
import hivens.widget.api.rememberScopedSource
import hivens.widget.api.valueOrNull
import hivens.widget.model.Widget

/**
 * The screens a person made, one rail item each in the order they made them, each wearing its icon. A screen made
 * shows here at once and one deleted is gone from here at once: nothing is written into the rail for it.
 */
@Widget(id = "nav.screens", displayName = "widget.nav.screens", needs = ["pages.screens", "nav.show"])
@Composable
fun NavScreens() {
    val screens = rememberScopedSource(PagesKeys.Screens).valueOrNull().orEmpty().filter { it.made }
    val shown = rememberScopedSource(PagesKeys.Shown).valueOrNull()?.screen
    val show = rememberScopedCommand(PagesKeys.Show) ?: return
    Column(Modifier.fillMaxWidth()) {
        screens.forEach { screen ->
            key(screen.id) {
                PuppetClick("nav.screen.${screen.id.value}") { show(LauncherScreens.section(screen.id)) }
                NavSlot(
                    icon        = ScreenIcons.of(screen.icon.orEmpty()),
                    outlineSwap = true,
                    active      = shown == screen.id,
                    onClick     = { show(LauncherScreens.section(screen.id)) },
                )
            }
        }
    }
}

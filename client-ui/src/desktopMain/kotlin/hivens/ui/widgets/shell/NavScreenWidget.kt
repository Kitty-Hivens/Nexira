package hivens.ui.widgets.shell

import androidx.compose.runtime.Composable
import hivens.pages.api.PagesKeys
import hivens.pages.api.ScreenId
import hivens.ui.navigation.LauncherScreens
import hivens.ui.screens.custom.ScreenIcons
import hivens.widget.api.rememberProps
import hivens.widget.api.rememberScopedCommand
import hivens.widget.api.rememberScopedSource
import hivens.widget.api.valueOrNull
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance
import kotlinx.serialization.Serializable

@Serializable
data class ScreenLinkProps(
    /** The id of the screen this opens. */
    val screen: String = "",
)

/**
 * A rail item that opens one screen, wearing that screen's icon.
 *
 * Its own kind rather than another value of the nav entry's target, because the
 * target is an enum on the wire and the screens are not a closed set: a link is
 * a pointer to one screen, and a pointer belongs in a prop.
 *
 * Reads the composition's screens and the page shown, which every widget of the
 * composition sees, so a link dropped anywhere still opens its screen. A link to a
 * screen that is gone draws nothing.
 */
@Widget(id = "nav.screen", displayName = "widget.nav.screen", propsClass = ScreenLinkProps::class, needs = ["pages.screens", "nav.show"])
@Composable
fun NavScreenLink(instance: WidgetInstance) {
    val p = instance.rememberProps<ScreenLinkProps>()
    val id = ScreenId(p.screen)
    val screen = rememberScopedSource(PagesKeys.Screens).valueOrNull()?.firstOrNull { it.id == id } ?: return
    val shown = rememberScopedSource(PagesKeys.Shown).valueOrNull()?.screen
    val show = rememberScopedCommand(PagesKeys.Show) ?: return
    NavSlot(
        icon        = ScreenIcons.of(screen.icon.orEmpty()),
        outlineSwap = true,
        active      = shown == id,
        onClick     = { show(LauncherScreens.section(id)) },
    )
}

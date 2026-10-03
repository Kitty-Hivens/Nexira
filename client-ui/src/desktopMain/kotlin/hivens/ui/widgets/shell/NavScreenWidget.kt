package hivens.ui.widgets.shell

import androidx.compose.runtime.Composable
import hivens.ui.Screen
import hivens.ui.screens.custom.ScreenIcons
import hivens.widget.api.LocalLayoutGraph
import hivens.widget.api.rememberProps
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance
import hivens.widget.model.screen
import kotlinx.serialization.Serializable

@Serializable
data class ScreenLinkProps(
    /** The id of the made screen this opens. */
    val screen: String = "",
)

/**
 * A rail item that opens a screen somebody made, wearing that screen's icon.
 *
 * Its own kind rather than another value of the nav entry's target, because the
 * target is an enum on the wire and the screens are not a closed set: a link is
 * a pointer to one record in the graph, and a pointer belongs in a prop.
 *
 * Reads the shell's own context rather than the rail's, which the app provides
 * around everything, so a link dropped anywhere still opens its screen. A link to
 * a screen that is gone draws nothing, and deleting a screen clears its links.
 */
@Widget(id = "nav.screen", displayName = "widget.nav.screen", propsClass = ScreenLinkProps::class)
@Composable
fun NavScreenLink(instance: WidgetInstance) {
    val p = instance.rememberProps<ScreenLinkProps>()
    val spec = LocalLayoutGraph.current.screen(p.screen) ?: return
    val ctx = LocalShellContext.current
    val current = ctx.currentScreen
    NavSlot(
        icon        = ScreenIcons.of(spec.icon),
        outlineSwap = true,
        phase       = 0.8f,
        active      = current is Screen.Custom && current.id == spec.id,
        onClick     = { ctx.onSwitchTab(Screen.Custom(spec.id)) },
    )
}

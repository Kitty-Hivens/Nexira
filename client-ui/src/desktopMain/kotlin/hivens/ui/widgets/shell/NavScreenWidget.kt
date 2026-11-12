package hivens.ui.widgets.shell

import androidx.compose.runtime.Composable
import hivens.ui.Screen
import hivens.ui.screens.custom.ScreenIcons
import hivens.ui.widgets.Commands
import hivens.ui.widgets.Sources
import hivens.widget.api.LocalLayoutGraph
import hivens.widget.api.rememberProps
import hivens.widget.api.rememberScopedCommand
import hivens.widget.api.rememberScopedSource
import hivens.widget.api.valueOrNull
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
 * Reads the composition's history, which every surface of the composition sees,
 * so a link dropped anywhere still opens its screen. A link to a screen that is
 * gone draws nothing, and deleting a screen clears its links.
 */
@Widget(id = "nav.screen", displayName = "widget.nav.screen", propsClass = ScreenLinkProps::class, needs = ["nav.state", "nav.switch"])
@Composable
fun NavScreenLink(instance: WidgetInstance) {
    val p = instance.rememberProps<ScreenLinkProps>()
    val spec = LocalLayoutGraph.current.screen(p.screen) ?: return
    val nav = rememberScopedSource(Sources.Navigation).valueOrNull() ?: return
    val switchTab = rememberScopedCommand(Commands.SwitchTab) ?: return
    val current = nav.current
    NavSlot(
        icon        = ScreenIcons.of(spec.icon),
        outlineSwap = true,
        phase       = 0.8f,
        active      = current is Screen.Custom && current.id == spec.id,
        onClick     = { switchTab(Screen.Custom(spec.id)) },
    )
}

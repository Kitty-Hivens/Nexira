package hivens.ui.widgets.shell

import androidx.compose.runtime.Composable
import hivens.ui.navigation.LauncherConfigs
import hivens.ui.screens.custom.ScreenIcons
import hivens.widget.api.CompositionKeys
import hivens.widget.api.LocalLayoutGraph
import hivens.widget.api.rememberProps
import hivens.widget.api.rememberScopedCommand
import hivens.widget.api.rememberScopedSource
import hivens.widget.api.valueOrNull
import hivens.widget.model.ConfigId
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
@Widget(id = "nav.screen", displayName = "widget.nav.screen", propsClass = ScreenLinkProps::class, needs = ["nav.state", "nav.show"])
@Composable
fun NavScreenLink(instance: WidgetInstance) {
    val p = instance.rememberProps<ScreenLinkProps>()
    val spec = LocalLayoutGraph.current.screen(p.screen) ?: return
    val nav = rememberScopedSource(CompositionKeys.History).valueOrNull() ?: return
    val show = rememberScopedCommand(CompositionKeys.Show) ?: return
    val config = ConfigId(spec.id)
    NavSlot(
        icon        = ScreenIcons.of(spec.icon),
        outlineSwap = true,
        active      = nav.current.config == config,
        onClick     = { show(LauncherConfigs.section(config)) },
    )
}

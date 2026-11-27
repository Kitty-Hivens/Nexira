package hivens.ui.widgets.shell

import androidx.compose.runtime.Composable
import hivens.ui.navigation.LauncherConfigs
import hivens.ui.screens.custom.ScreenIcons
import hivens.widget.api.LocalDocument
import hivens.widget.api.LocalSlotPath
import hivens.widget.api.rememberProps
import hivens.widget.api.rememberScopedCommand
import hivens.widget.api.rememberScopedSource
import hivens.widget.api.valueOrNull
import hivens.widget.model.ConfigId
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance
import kotlinx.serialization.Serializable
import hivens.history.api.HistoryKeys
import hivens.pages.api.PagesKeys

@Serializable
data class ScreenLinkProps(
    /** The config of the screen this opens. */
    val screen: String = "",
)

/**
 * A rail item that opens a screen somebody made, wearing that screen's icon.
 *
 * Its own kind rather than another value of the nav entry's target, because the
 * target is an enum on the wire and the screens are not a closed set: a link is
 * a pointer to one config of the document, and a pointer belongs in a prop.
 *
 * Reads the composition's history, which every surface of the composition sees,
 * so a link dropped anywhere still opens its screen. A link to a screen that is
 * gone draws nothing, and deleting a screen clears its links.
 */
@Widget(id = "nav.screen", displayName = "widget.nav.screen", propsClass = ScreenLinkProps::class, needs = ["nav.state", "nav.show"])
@Composable
fun NavScreenLink(instance: WidgetInstance) {
    val p = instance.rememberProps<ScreenLinkProps>()
    val config = ConfigId(p.screen)
    val screen = LocalDocument.current?.compositions?.get(LocalSlotPath.current.composition)?.configs?.get(config) ?: return
    val nav = rememberScopedSource(HistoryKeys.History).valueOrNull() ?: return
    val show = rememberScopedCommand(PagesKeys.Show) ?: return
    NavSlot(
        icon        = ScreenIcons.of(screen.icon),
        outlineSwap = true,
        active      = nav.current.config == config,
        onClick     = { show(LauncherConfigs.section(config)) },
    )
}

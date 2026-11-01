package hivens.ui.widgets.home.new

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.PackInstance
import hivens.ui.AppState
import hivens.ui.components.LaunchControl
import hivens.ui.components.rememberLaunchControl
import hivens.ui.nx.PlayButton
import hivens.ui.nx.PlayGround
import hivens.ui.nx.PlayLayout
import org.koin.compose.koinInject

/**
 * The pack the home launch widgets offer, and its launch control.
 *
 * Which pack is this file's decision: the most recently played, else the most
 * recently installed. What the control says and does is [rememberLaunchControl]'s,
 * the same decision the pack page makes, so the two can no longer disagree about
 * whether a pack can be played or why not.
 */
internal class QuickLaunchTarget(
    val target: PackInstance,
    val control: LaunchControl,
)

/**
 * Null when the repository is empty: the widget elides itself, and the recent
 * list owns the install call to action.
 *
 * [playLabel] is the widget's own wording for the plain Play state, from its props
 * or its default. Every other state is named by the control.
 */
@Composable
internal fun rememberQuickLaunchTarget(playLabel: String? = null): QuickLaunchTarget? {
    val ctx = LocalHomeNewContext.current
    val repo: IPackRepository = koinInject()
    val all by remember { repo.observe() }.collectAsState()

    val target: PackInstance = remember(all) { all.minWithOrNull(RecentFirst) } ?: return null

    val session = (ctx.appState as? AppState.Authenticated)?.session
    return QuickLaunchTarget(target, rememberLaunchControl(target, session, playLabel))
}

/**
 * The order the home widgets put packs in: the most recently played first, and
 * among packs played equally recently, which is every pack never played, the most
 * recently installed.
 *
 * One comparator for every widget, because the quick-launch target is meant to be
 * the first row of the lists beside it. Picking it with two separate maxima never
 * reached the second: the first answers null only for an empty list, so with no
 * pack played yet the target was whichever the repository happened to list first.
 */
internal val RecentFirst: Comparator<PackInstance> =
    compareByDescending<PackInstance> { it.lastPlayedEpochOrZero }.thenByDescending { it.createdAtEpoch }

/**
 * The shared launch pill for the home widgets. [ground] is what the widget draws it
 * over, which only the widget knows; [layout] is the widget's own setting.
 */
@Composable
internal fun QuickLaunchButton(
    quickLaunch: QuickLaunchTarget,
    ground: PlayGround,
    layout: PlayLayout,
    modifier: Modifier = Modifier,
    iconOnly: Boolean = false,
) {
    val control = quickLaunch.control
    PlayButton(
        label    = control.label,
        icon     = control.icon,
        tone     = control.tone,
        progress = control.progress,
        onClick  = control.onClick,
        ground   = ground,
        layout   = layout,
        iconOnly = iconOnly,
        modifier = modifier,
    )
}

package hivens.ui.activity

import hivens.core.activity.Activity
import hivens.core.activity.ActivityAction
import hivens.core.activity.ActivityKind
import hivens.launcher.PackInstallService
import hivens.launcher.launch.LauncherController
import org.slf4j.LoggerFactory

/**
 * Turns an [ActivityAction] the registry advertised back into the call that
 * performs it.
 *
 * The handlers live here rather than on [Activity] deliberately. A lambda field
 * would compare by reference, so every re-report of a running job would look
 * like a changed activity, and the registry's throttle -- which publishes at
 * once on any change of shape -- would fire on every progress tick. Keeping the
 * model to a set of enum capabilities is what lets equality mean what the
 * throttle needs it to mean.
 *
 * A key that no longer maps to anything is a no-op with a log line rather than a
 * crash: a control can outlive its job by the width of one frame.
 */
class ActivityCommands(
    private val installs: PackInstallService,
    private val controller: LauncherController,
) {
    private val log = LoggerFactory.getLogger(ActivityCommands::class.java)

    fun perform(activity: Activity, action: ActivityAction) {
        when (action) {
            ActivityAction.Cancel -> when (activity.kind) {
                ActivityKind.Install -> installs.cancel(activity.key.removePrefix(INSTALL_PREFIX))
                // Cancelling a launch is the same abort the launch panel offers
                // while a launch is preparing or downloading.
                ActivityKind.Launch -> controller.abort()
                else -> unhandled(activity, action)
            }
            // The same abort the pack hero's Stop already calls.
            ActivityAction.Stop -> when (activity.kind) {
                ActivityKind.Game -> controller.abort()
                else -> unhandled(activity, action)
            }
            // No source advertises Pause yet: nothing in the transfer layer can
            // hold a job and resume it. The capability exists so the surface has
            // a name for it when one can.
            ActivityAction.Pause -> unhandled(activity, action)
        }
    }

    private fun unhandled(activity: Activity, action: ActivityAction) {
        log.warn("no handler for {} on {} ({})", action, activity.key, activity.kind)
    }

    private companion object {
        const val INSTALL_PREFIX = "install:"
    }
}

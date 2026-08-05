package hivens.ui.components

import androidx.compose.runtime.*
import hivens.config.Branding
import hivens.core.data.LauncherUpdate
import hivens.update.UpdateService
import hivens.ui.i18n.LocalStrings
import hivens.ui.notifications.Kind
import hivens.ui.notifications.NotifAction
import hivens.ui.notifications.NotifGlyph
import hivens.ui.notifications.NotificationCenter
import hivens.ui.notifications.Severity
import hivens.ui.puppet.PuppetClick
import kotlin.time.Duration.Companion.milliseconds
import org.koin.compose.koinInject

// Stable source key so a re-check coalesces onto the same card instead of
// stacking, and the mandatory / puppet paths can retire it by name.
private const val UPDATE_SOURCE_KEY = "launcher.update"

@Composable
fun UpdateManager() {
    val updateService = koinInject<UpdateService>()
    val center        = koinInject<NotificationCenter>()
    val s             = LocalStrings.current

    var availableUpdate by remember { mutableStateOf<LauncherUpdate?>(null) }
    var showDialog      by remember { mutableStateOf(false) }

    // Available-update goes through the standard notification center, so it lands
    // in the live stack AND the history log with an icon + actions instead of a
    // bespoke toast. ActionRequired never auto-dismisses; the card's own action
    // row dismisses it after either button, so "Later" only needs to exist while
    // "Details" flips the modal open.
    fun notifyUpdate(update: LauncherUpdate) {
        center.push(
            sourceKey = UPDATE_SOURCE_KEY,
            sender    = Branding.TITLE,
            iconUrl   = null,
            severity  = Severity.Info,
            kind      = Kind.ActionRequired,
            title     = s.updateTitle,
            body      = s.updateVersion(update.version),
            glyph     = NotifGlyph.Update,
            actions   = listOf(
                NotifAction("details", s.updateDetails) { showDialog = true },
                NotifAction("later", s.updateLater) {},
            ),
        )
    }

    // Startup check.
    LaunchedEffect(Unit) {
        // Drop stale installers before probing -- a partially-downloaded artefact
        // from a previous session could otherwise shadow the new one.
        updateService.cleanupOldUpdates()

        val update = updateService.checkForUpdate()
        if (update != null) {
            availableUpdate = update
            // Critical AND mandatory both skip the card and open the modal
            // directly -- neither is something the user should click past on a
            // small notification.
            if (update.isCritical || update.isMandatory) showDialog = true
            else notifyUpdate(update)
        }
    }

    // Mandatory poll loop. A session left open all afternoon needs a path for
    // emergency upstream-protocol-broke updates that does not wait for the next
    // restart. The service-side cooldown (5 min) caps the network rate; the UI
    // tick is faster (1 min) so a cooldown elapsing mid-tick is still picked up.
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(60_000L.milliseconds)
            // Skip when a dialog is already up -- the user is already being asked
            // to act on whatever is queued.
            if (showDialog) continue
            val mandatory = updateService.checkForMandatoryUpdate()
            if (mandatory != null) {
                availableUpdate = mandatory
                // Retire any pending non-critical card; the modal supersedes it.
                center.dismiss(UPDATE_SOURCE_KEY)
                showDialog = true
            }
        }
    }

    // Puppet automation for the notification actions -- the card itself is not a
    // puppet target, so the hooks live here, active while the update card is the
    // current surface (update found, modal not yet open).
    PuppetClick("updateNotification.details", enabled = availableUpdate != null && !showDialog) {
        center.dismiss(UPDATE_SOURCE_KEY)
        showDialog = true
    }
    PuppetClick("updateNotification.later", enabled = availableUpdate != null && !showDialog) {
        center.dismiss(UPDATE_SOURCE_KEY)
    }

    // Modal dialog.
    if (showDialog && availableUpdate != null) {
        UpdateDialog(
            update = availableUpdate!!,
            updateService = updateService,
            onDismiss = {
                showDialog = false
            }
        )
    }
}

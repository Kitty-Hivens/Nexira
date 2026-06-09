package hivens.ui.components

import androidx.compose.runtime.*
import hivens.core.data.LauncherUpdate
import hivens.launcher.update.UpdateService
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
fun UpdateManager() {
    val updateService = koinInject<UpdateService>()
    val scope = rememberCoroutineScope()

    var availableUpdate by remember { mutableStateOf<LauncherUpdate?>(null) }
    var showDialog by remember { mutableStateOf(false) }
    var showNotification by remember { mutableStateOf(false) }

    // Startup check
    LaunchedEffect(Unit) {
        scope.launch {
            // Drop stale installers before probing -- otherwise a partially-
            // downloaded artefact from a previous session could shadow the
            // new one in the updates dir.
            updateService.cleanupOldUpdates()

            val update = updateService.checkForUpdate()
            if (update != null) {
                availableUpdate = update
                // Critical AND mandatory both skip the corner-notification step
                // and open the modal directly -- neither is something the user
                // should be able to ignore by clicking past a small badge.
                if (update.isCritical || update.isMandatory) {
                    showDialog = true
                } else {
                    showNotification = true
                }
            }
        }
    }

    // Mandatory poll loop. Long-running launcher sessions (someone leaves it
    // open all afternoon) need a path for emergency upstream-protocol-broke
    // updates that doesn't wait for the next launcher restart. The
    // service-side cooldown (5 min) caps the actual network rate; the UI
    // tick is intentionally faster (1 min) so a cooldown that elapses
    // mid-tick still gets picked up promptly.
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(60_000L.milliseconds)
            // Skip when a dialog is already up -- the user is already being
            // asked to act on whatever update is queued.
            if (showDialog) continue
            val mandatory = updateService.checkForMandatoryUpdate()
            if (mandatory != null) {
                availableUpdate = mandatory
                showNotification = false
                showDialog = true
            }
        }
    }

    // Top-right corner notification
    if (showNotification && availableUpdate != null) {
        UpdateNotification(
            update = availableUpdate!!,
            onOpenDialog = {
                showNotification = false
                showDialog = true
            },
            onDismiss = {
                showNotification = false
            }
        )
    }

    // Modal dialog
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

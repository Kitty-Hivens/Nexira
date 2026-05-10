package hivens.ui.components

import androidx.compose.runtime.*
import hivens.core.data.LauncherUpdate
import hivens.launcher.update.UpdateService
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
fun UpdateManager() {
    val updateService = koinInject<UpdateService>()
    val scope = rememberCoroutineScope()
    
    var availableUpdate by remember { mutableStateOf<LauncherUpdate?>(null) }
    var showDialog by remember { mutableStateOf(false) }
    var showNotification by remember { mutableStateOf(false) }

    // Проверка при запуске
    LaunchedEffect(Unit) {
        scope.launch {
            // Очистка старых установщиков перед проверкой
            updateService.cleanupOldUpdates()
            
            val update = updateService.checkForUpdate(force = false)
            if (update != null) {
                availableUpdate = update
                // Critical AND mandatory both skip the corner-notification step
                // and open the modal directly — neither is something the user
                // should be able to ignore by clicking past a small badge.
                if (update.isCritical || update.isMandatory) {
                    showDialog = true
                } else {
                    showNotification = true
                }
            }
        }
    }

    // Уведомление (справа сверху)
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

    // Диалог (модальный)
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

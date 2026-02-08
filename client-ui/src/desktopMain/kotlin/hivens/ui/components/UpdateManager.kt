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

    @Suppress("UNUSED_VALUE")
    var showDialog by remember { mutableStateOf(false) }

    @Suppress("UNUSED_VALUE")
    var showNotification by remember { mutableStateOf(false) }

    // Проверка при запуске
    LaunchedEffect(Unit) {
        scope.launch {
            // Очистка старых установщиков перед проверкой
            updateService.cleanupOldUpdates()
            
            val update = updateService.checkForUpdate(force = false)
            if (update != null) {
                availableUpdate = update
                if (update.isCritical) {
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

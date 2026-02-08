package hivens.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import hivens.core.data.LauncherUpdate
import hivens.launcher.update.UpdateApplicator
import hivens.launcher.update.UpdateService
import hivens.ui.theme.CelestiaTheme
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import kotlin.math.roundToInt
import kotlin.system.exitProcess

@Composable
fun UpdateDialog(
    update: LauncherUpdate,
    updateService: UpdateService,
    onDismiss: () -> Unit
) {
    val logger = LoggerFactory.getLogger("UpdateDialog")
    val scope = rememberCoroutineScope()

    var downloadState by remember { mutableStateOf<DownloadState>(DownloadState.Idle) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = { if (!update.isCritical) onDismiss() }) {
        Surface(
            modifier = Modifier.width(550.dp).wrapContentHeight(),
            shape = RoundedCornerShape(16.dp),
            color = CelestiaTheme.colors.surface,
            elevation = 8.dp
        ) {
            Column(Modifier.padding(24.dp)) {
                
                // Header
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = if (update.isCritical) Icons.Default.Warning else Icons.Default.CloudDownload,
                        contentDescription = null,
                        tint = if (update.isCritical) CelestiaTheme.colors.error else CelestiaTheme.colors.primary,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            text = if (update.isCritical) "КРИТИЧЕСКОЕ ОБНОВЛЕНИЕ" else "Доступно обновление",
                            style = MaterialTheme.typography.h6,
                            color = if (update.isCritical) CelestiaTheme.colors.error else CelestiaTheme.colors.textPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = update.version,
                            style = MaterialTheme.typography.caption,
                            color = CelestiaTheme.colors.textSecondary
                        )
                    }
                }

                // Critical warning banner
                if (update.isCritical) {
                    Spacer(Modifier.height(16.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(CelestiaTheme.colors.error.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                            .padding(12.dp)
                    ) {
                        Text(
                            "Это обновление содержит критические исправления безопасности.",
                            style = MaterialTheme.typography.body2,
                            color = CelestiaTheme.colors.error,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                Divider(color = CelestiaTheme.colors.textSecondary.copy(alpha = 0.2f))
                Spacer(Modifier.height(16.dp))

                // Changelog
                Text(
                    "Что нового:",
                    style = MaterialTheme.typography.subtitle2,
                    color = CelestiaTheme.colors.textPrimary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                        .background(CelestiaTheme.colors.background.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                        .padding(12.dp)
                ) {
                    Text(
                        text = update.changelog,
                        style = MaterialTheme.typography.body2,
                        color = CelestiaTheme.colors.textSecondary,
                        modifier = Modifier.verticalScroll(rememberScrollState())
                    )
                }

                Spacer(Modifier.height(16.dp))

                // Download progress
                AnimatedVisibility(
                    visible = downloadState is DownloadState.Downloading,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    if (downloadState is DownloadState.Downloading) {
                        val state = downloadState as DownloadState.Downloading
                        DownloadProgress(state)
                    }
                }

                // Error message
                errorMessage?.let { error ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(CelestiaTheme.colors.error.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                            .padding(12.dp)
                    ) {
                        Column {
                            Text(
                                "Ошибка загрузки",
                                style = MaterialTheme.typography.subtitle2,
                                color = CelestiaTheme.colors.error,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                error,
                                style = MaterialTheme.typography.body2,
                                color = CelestiaTheme.colors.error
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    
                    // "Later" button (non-critical only)
                    if (!update.isCritical && downloadState !is DownloadState.Downloading) {
                        TextButton(onClick = onDismiss) {
                            Text("Позже", color = CelestiaTheme.colors.textSecondary)
                        }
                        Spacer(Modifier.width(8.dp))
                    }

                    // Main action button
                    when (downloadState) {
                        is DownloadState.Idle -> {
                            Button(
                                onClick = {
                                    scope.launch {
                                        downloadState = DownloadState.Downloading(0L, 0L, 0.0)
                                        errorMessage = null
                                        
                                        try {
                                            val installerPath = updateService.downloadUpdate(update) { downloaded, total, speed ->
                                                downloadState = DownloadState.Downloading(downloaded, total, speed)
                                            }
                                            
                                            downloadState = DownloadState.Ready(installerPath.toString())
                                            
                                        } catch (e: Exception) {
                                            logger.error("Download failed", e)
                                            errorMessage = e.message ?: "Неизвестная ошибка"
                                            downloadState = DownloadState.Failed
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    backgroundColor = if (update.isCritical) {
                                        CelestiaTheme.colors.error
                                    } else {
                                        CelestiaTheme.colors.primary
                                    }
                                ),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    if (update.isCritical) "СКАЧАТЬ СЕЙЧАС" else "Скачать и установить",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        
                        is DownloadState.Downloading -> {
                            Button(
                                onClick = { },
                                enabled = false,
                                colors = ButtonDefaults.buttonColors(
                                    backgroundColor = CelestiaTheme.colors.primary.copy(alpha = 0.5f),
                                    disabledBackgroundColor = CelestiaTheme.colors.primary.copy(alpha = 0.5f)
                                ),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("Загрузка...", color = Color.White)
                            }
                        }
                        
                        is DownloadState.Ready -> {
                            val path = (downloadState as DownloadState.Ready).installerPath
                            Button(
                                onClick = {
                                    try {
                                        UpdateApplicator.scheduleUpdate(java.nio.file.Paths.get(path))
                                        logger.info("Update scheduled, exiting...")
                                        exitProcess(0)
                                    } catch (e: Exception) {
                                        logger.error("Failed to schedule update", e)
                                        errorMessage = "Не удалось запланировать обновление: ${e.message}"
                                        downloadState = DownloadState.Failed
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    backgroundColor = Color(0xFF4CAF50)
                                ),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    "Установить и перезапустить",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        
                        is DownloadState.Failed -> {
                            Button(
                                onClick = {
                                    errorMessage = null
                                    downloadState = DownloadState.Idle
                                },
                                colors = ButtonDefaults.buttonColors(
                                    backgroundColor = CelestiaTheme.colors.primary
                                ),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Повторить", color = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadProgress(state: DownloadState.Downloading) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val downloadedMB = state.downloaded / 1024.0 / 1024.0
            val totalMB = state.total / 1024.0 / 1024.0
            val speedMBps = state.speed / 1024.0 / 1024.0
            
            Text(
                text = if (state.total > 0) {
                    "%.1f / %.1f MB".format(downloadedMB, totalMB)
                } else {
                    "%.1f MB".format(downloadedMB)
                },
                style = MaterialTheme.typography.caption,
                color = CelestiaTheme.colors.textSecondary
            )
            
            if (state.speed > 0) {
                Text(
                    "%.2f MB/s".format(speedMBps),
                    style = MaterialTheme.typography.caption,
                    color = CelestiaTheme.colors.primary,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        
        Spacer(Modifier.height(8.dp))
        
        val progress = if (state.total > 0) {
            (state.downloaded.toFloat() / state.total).coerceIn(0f, 1f)
        } else {
            0f
        }
        
        LinearProgressIndicator(
            progress = progress,
            modifier = Modifier.fillMaxWidth().height(8.dp),
            backgroundColor = CelestiaTheme.colors.surface,
            color = CelestiaTheme.colors.primary
        )
        
        if (state.total > 0) {
            Spacer(Modifier.height(4.dp))
            Text(
                "${(progress * 100).roundToInt()}%",
                style = MaterialTheme.typography.caption,
                color = CelestiaTheme.colors.textSecondary,
                modifier = Modifier.align(Alignment.End)
            )
        }
        
        Spacer(Modifier.height(16.dp))
    }
}

private sealed class DownloadState {
    object Idle : DownloadState()
    data class Downloading(val downloaded: Long, val total: Long, val speed: Double) : DownloadState()
    data class Ready(val installerPath: String) : DownloadState()
    object Failed : DownloadState()
}

package hivens.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.ui.logic.LaunchState
import hivens.ui.theme.CelestiaTheme
import java.text.DecimalFormat

@Composable
fun LaunchControlPanel(
    state: LaunchState,
    onLaunch: () -> Unit,
    onAbort: () -> Unit,
    onClearError: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        // СТАТУС БАР
        Row(
            Modifier.fillMaxWidth().height(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            when (state) {
                is LaunchState.Idle -> {
                    Text("Готов к игре", style = MaterialTheme.typography.caption, color = CelestiaTheme.colors.textSecondary)
                }
                is LaunchState.Prepare -> {
                    // Используем stepName, если он есть, иначе просто текст
                    Text(state.stepName, style = MaterialTheme.typography.caption, color = CelestiaTheme.colors.textSecondary)
                }
                is LaunchState.Downloading -> {
                    val downloadedMb = state.downloadedBytes / 1024.0 / 1024.0
                    val totalMb = state.totalBytes / 1024.0 / 1024.0
                    val format = DecimalFormat("#0.0")

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Загрузка: ", style = MaterialTheme.typography.caption, color = CelestiaTheme.colors.textSecondary)
                        Text(
                            "${format.format(downloadedMb)} / ${format.format(totalMb)} MB (${state.speedStr})",
                            style = MaterialTheme.typography.caption,
                            color = CelestiaTheme.colors.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                is LaunchState.Error -> {
                    // message вместо error
                    Text(state.message, style = MaterialTheme.typography.caption, color = CelestiaTheme.colors.error)
                }
                is LaunchState.GameRunning -> {
                    Text("Игра запущена", style = MaterialTheme.typography.caption, color = CelestiaTheme.colors.success)
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // ПРОГРЕСС БАР
        val progress = when(state) {
            is LaunchState.Prepare -> state.progress
            is LaunchState.Downloading -> state.progress
            is LaunchState.GameRunning -> 1.0f
            else -> 0f
        }

        if (state !is LaunchState.Idle && state !is LaunchState.Error) {
            LinearProgressIndicator(
                progress = progress,
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                backgroundColor = CelestiaTheme.colors.surface,
                color = CelestiaTheme.colors.primary
            )
        } else {
            // Пустое место, чтобы интерфейс не прыгал
            Spacer(Modifier.height(6.dp))
        }

        Spacer(Modifier.height(16.dp))

        // КНОПКА ДЕЙСТВИЯ
        val btnText = when (state) {
            is LaunchState.Downloading, is LaunchState.Prepare -> "ОТМЕНА"
            is LaunchState.GameRunning -> "ЗАПУЩЕНО"
            is LaunchState.Error -> "СБРОСИТЬ ОШИБКУ"
            else -> "ИГРАТЬ"
        }

        CelestiaButton(
            text = btnText,
            enabled = state !is LaunchState.GameRunning,
            onClick = {
                when (state) {
                    is LaunchState.Downloading, is LaunchState.Prepare -> onAbort()
                    is LaunchState.Error -> onClearError()
                    else -> onLaunch()
                }
            },
            modifier = Modifier.fillMaxWidth().height(50.dp)
        )
    }
}

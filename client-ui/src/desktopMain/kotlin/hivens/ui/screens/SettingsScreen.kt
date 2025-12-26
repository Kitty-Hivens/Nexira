package hivens.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Edit
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import hivens.core.api.interfaces.ISettingsService
import hivens.core.data.SeasonTheme
import hivens.ui.components.CelestiaButton
import hivens.ui.components.GlassCard
import hivens.ui.theme.CelestiaTheme
import org.koin.compose.koinInject
import java.io.File
import javax.swing.JFrame
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    isDarkTheme: Boolean = true,
    onToggleTheme: () -> Unit = {},
    onThemeChanged: (SeasonTheme) -> Unit = {}
) {
    // Внедряем сервис настроек
    val settingsService: ISettingsService = koinInject()

    // Загружаем текущие настройки при открытии экрана
    val currentSettings = remember { settingsService.getSettings() }

    // Локальное состояние для редактирования
    var memory by remember { mutableStateOf(currentSettings.memoryMB.toFloat()) }
    var autoClose by remember { mutableStateOf(currentSettings.closeAfterStart) }
    var javaPath by remember { mutableStateOf(currentSettings.javaPath ?: "") }

    // Состояние для сезонной темы
    var selectedTheme by remember { mutableStateOf(currentSettings.seasonalTheme) }
    var isThemeDropdownExpanded by remember { mutableStateOf(false) }

    val scrollState = rememberScrollState()

    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text(
            text = "ГЛОБАЛЬНЫЕ НАСТРОЙКИ",
            style = MaterialTheme.typography.h4,
            color = CelestiaTheme.colors.textPrimary
        )
        Spacer(Modifier.height(24.dp))

        GlassCard(Modifier.fillMaxWidth().weight(1f)) {
            Column(
                Modifier.fillMaxSize().padding(32.dp).verticalScroll(scrollState)
            ) {
                // --- СЕКЦИЯ ИНТЕРФЕЙСА ---
                Text("ИНТЕРФЕЙС", style = MaterialTheme.typography.subtitle2, color = CelestiaTheme.colors.primary)
                Spacer(Modifier.height(16.dp))

                // Переключатель темы
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Темная тема", color = CelestiaTheme.colors.textPrimary)
                    Switch(
                        checked = isDarkTheme,
                        onCheckedChange = { onToggleTheme() },
                        colors = SwitchDefaults.colors(checkedThumbColor = CelestiaTheme.colors.primary)
                    )
                }

                Spacer(Modifier.height(12.dp))

                // Выбор сезонного эффекта
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Сезонный эффект", color = CelestiaTheme.colors.textPrimary)
                        Text("Анимация на заднем фоне", style = MaterialTheme.typography.caption, color = CelestiaTheme.colors.textSecondary)
                    }

                    Box {
                        Row(
                            Modifier
                                .clickable { isThemeDropdownExpanded = true }
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(selectedTheme.title, color = CelestiaTheme.colors.primary)
                            Icon(Icons.Default.ArrowDropDown, null, tint = CelestiaTheme.colors.primary)
                        }

                        DropdownMenu(
                            expanded = isThemeDropdownExpanded,
                            onDismissRequest = { isThemeDropdownExpanded = false },
                            modifier = Modifier.background(CelestiaTheme.colors.surface)
                        ) {
                            SeasonTheme.entries.forEach { theme ->
                                DropdownMenuItem(onClick = {
                                    selectedTheme = theme
                                    isThemeDropdownExpanded = false
                                    onThemeChanged(theme)
                                }) {
                                    Text(theme.title, color = CelestiaTheme.colors.textPrimary)
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))
                Divider(color = CelestiaTheme.colors.border)
                Spacer(Modifier.height(24.dp))

                // --- СЕКЦИЯ JAVA ---
                Text("JAVA & ПАМЯТЬ", style = MaterialTheme.typography.subtitle2, color = CelestiaTheme.colors.primary)
                Spacer(Modifier.height(16.dp))

                // Выбор Java пути
                OutlinedTextField(
                    value = javaPath,
                    onValueChange = { javaPath = it },
                    label = { Text("Глобальный путь к Java (необязательно)") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = TextFieldDefaults.outlinedTextFieldColors(
                        textColor = CelestiaTheme.colors.textPrimary,
                        focusedBorderColor = CelestiaTheme.colors.primary,
                        unfocusedBorderColor = CelestiaTheme.colors.border
                    ),
                    trailingIcon = {
                        IconButton(onClick = {
                            val file = pickExecutable("Выберите Java Executable")
                            if (file != null) javaPath = file.absolutePath
                        }) {
                            Icon(Icons.Default.Edit, null, tint = CelestiaTheme.colors.textSecondary)
                        }
                    }
                )

                Spacer(Modifier.height(16.dp))

                // Слайдер памяти
                Text("Выделение памяти (RAM)", color = CelestiaTheme.colors.textSecondary)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${memory.roundToInt()} MB",
                        style = MaterialTheme.typography.subtitle1,
                        modifier = Modifier.width(80.dp),
                        color = CelestiaTheme.colors.textPrimary
                    )
                    Slider(
                        value = memory,
                        onValueChange = { memory = it },
                        valueRange = 1024f..16384f,
                        steps = 14,
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(
                            thumbColor = CelestiaTheme.colors.primary,
                            activeTrackColor = CelestiaTheme.colors.primary
                        )
                    )
                }

                Spacer(Modifier.height(24.dp))
                Divider(color = CelestiaTheme.colors.border)
                Spacer(Modifier.height(24.dp))

                // --- ПОВЕДЕНИЕ ЛАУНЧЕРА ---
                Text("ПОВЕДЕНИЕ", style = MaterialTheme.typography.subtitle2, color = CelestiaTheme.colors.primary)
                Spacer(Modifier.height(16.dp))

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Закрывать лаунчер после запуска игры", color = CelestiaTheme.colors.textPrimary)
                    Switch(
                        checked = autoClose,
                        onCheckedChange = { autoClose = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = CelestiaTheme.colors.primary)
                    )
                }

                Spacer(Modifier.height(32.dp))

                // --- КНОПКА СОХРАНИТЬ ---
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    CelestiaButton(
                        text = "СОХРАНИТЬ",
                        onClick = {
                            val newSettings = settingsService.getSettings()
                            newSettings.memoryMB = memory.roundToInt()
                            newSettings.closeAfterStart = autoClose
                            newSettings.javaPath = javaPath.ifBlank { null }
                            newSettings.seasonalTheme = selectedTheme

                            settingsService.saveSettings(newSettings)
                        },
                        modifier = Modifier.width(150.dp)
                    )
                }
            }
        }
    }
}

// Вспомогательная функция для выбора файла (дублируется в ServerSettingsScreen,
// но это допустимо для изоляции экранов).
private fun pickExecutable(title: String): File? {
    val dialog = java.awt.FileDialog(null as JFrame?, title, java.awt.FileDialog.LOAD)
    dialog.isVisible = true
    return if (dialog.directory != null && dialog.file != null) File(dialog.directory, dialog.file) else null
}

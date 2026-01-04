package hivens.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
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
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    isDarkTheme: Boolean,
    onToggleTheme: () -> Unit,
    onThemeChanged: (SeasonTheme) -> Unit
) {
    val settingsService: ISettingsService = koinInject()
    val currentSettings = remember { settingsService.getSettings() }

    var memory by remember { mutableStateOf(currentSettings.memoryMB.toFloat()) }
    var autoClose by remember { mutableStateOf(currentSettings.closeAfterStart) }
    var selectedTheme by remember { mutableStateOf(currentSettings.seasonalTheme) }

    var isThemeDropdownExpanded by remember { mutableStateOf(false) }
    var showSavedMessage by remember { mutableStateOf(false) }

    val scrollState = rememberScrollState()
    val dividerColor = CelestiaTheme.colors.textSecondary.copy(alpha = 0.2f)

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
                // --- ИНТЕРФЕЙС ---
                Text("ИНТЕРФЕЙС", style = MaterialTheme.typography.subtitle2, color = CelestiaTheme.colors.primary)
                Spacer(Modifier.height(16.dp))

                // ТЕМА
                var switchState by remember(isDarkTheme) { mutableStateOf(isDarkTheme) }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Темная тема", color = CelestiaTheme.colors.textPrimary)
                    Switch(
                        checked = switchState,
                        onCheckedChange = {
                            switchState = it
                            onToggleTheme()
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = CelestiaTheme.colors.primary,
                            checkedTrackColor = CelestiaTheme.colors.primary.copy(alpha = 0.5f)
                        )
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
                                    val s = settingsService.getSettings()
                                    s.seasonalTheme = theme
                                    settingsService.saveSettings(s)
                                }) {
                                    Text(theme.title, color = CelestiaTheme.colors.textPrimary)
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))
                Divider(color = dividerColor)
                Spacer(Modifier.height(24.dp))

                // --- ПАМЯТЬ ---
                Text("ПАМЯТЬ", style = MaterialTheme.typography.subtitle2, color = CelestiaTheme.colors.primary)
                Spacer(Modifier.height(16.dp))

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
                        steps = 30,
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(
                            thumbColor = CelestiaTheme.colors.primary,
                            activeTrackColor = CelestiaTheme.colors.primary,
                            inactiveTrackColor = dividerColor
                        )
                    )
                }

                Spacer(Modifier.height(24.dp))
                Divider(color = dividerColor)
                Spacer(Modifier.height(24.dp))

                // --- ПОВЕДЕНИЕ ---
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
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = CelestiaTheme.colors.primary,
                            checkedTrackColor = CelestiaTheme.colors.primary.copy(alpha = 0.5f)
                        )
                    )
                }

                Spacer(Modifier.height(32.dp))

                // --- FOOTER ---
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    if (showSavedMessage) {
                        Text("Настройки сохранены", color = CelestiaTheme.colors.success, modifier = Modifier.padding(end = 16.dp))
                    }

                    CelestiaButton(
                        text = "СОХРАНИТЬ",
                        onClick = {
                            val newSettings = settingsService.getSettings()
                            newSettings.memoryMB = memory.roundToInt()
                            newSettings.closeAfterStart = autoClose
                            newSettings.seasonalTheme = selectedTheme

                            settingsService.saveSettings(newSettings)
                            showSavedMessage = true
                        },
                        modifier = Modifier.width(150.dp)
                    )
                }
            }
        }
    }
}

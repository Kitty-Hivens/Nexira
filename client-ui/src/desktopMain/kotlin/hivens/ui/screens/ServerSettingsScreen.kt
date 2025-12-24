package hivens.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
// ИСПРАВЛЕНИЕ: Удален импорт core.animateColorAsState

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import hivens.core.api.model.ServerProfile
import hivens.core.data.InstanceProfile
import hivens.core.data.OptionalMod
import hivens.core.api.interfaces.IManifestProcessorService
import hivens.launcher.ProfileManager
import hivens.ui.components.CelestiaButton
import hivens.ui.components.GlassCard
import hivens.ui.theme.CelestiaTheme
import org.koin.compose.koinInject
import java.awt.Desktop
import java.io.File
import java.nio.file.Path
import javax.swing.JFrame
import kotlin.math.roundToInt

@Composable
fun ServerSettingsScreen(server: ServerProfile, onBack: () -> Unit) {
    val profileManager: ProfileManager = koinInject()
    val manifestProcessorService: IManifestProcessorService = koinInject()
    val dataDirectory: Path = koinInject()

    var mods by remember { mutableStateOf<List<OptionalMod>>(emptyList()) }
    var profile by remember { mutableStateOf<InstanceProfile?>(null) }
    var javaPath by remember { mutableStateOf("") }
    var memory by remember { mutableStateOf(4096f) }
    var useCustomJava by remember { mutableStateOf(false) }
    val modStates = remember { mutableStateMapOf<String, Boolean>() }

    var modsLoaded by remember { mutableStateOf(false) }

    LaunchedEffect(server) {
        val p = profileManager.getProfile(server.assetDir)
        profile = p
        if (!p.javaPath.isNullOrEmpty()) {
            javaPath = p.javaPath!!
            useCustomJava = true
        }
        if (p.memoryMb > 0) memory = p.memoryMb.toFloat()

        val loadedMods = manifestProcessorService.getOptionalModsForClient(server)
        mods = loadedMods
        loadedMods.forEach { mod ->
            modStates[mod.id] = p.optionalModsState.getOrDefault(mod.id, mod.isDefault)
        }
        modsLoaded = true
    }

    fun saveProfile() {
        profile?.let { p ->
            p.javaPath = if (useCustomJava && javaPath.isNotBlank()) javaPath else null
            p.memoryMb = memory.roundToInt()
            modStates.forEach { (id, state) -> p.optionalModsState[id] = state }
            profileManager.saveProfile(p)
        }
    }

    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { saveProfile(); onBack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = CelestiaTheme.colors.textPrimary)
            }
            Spacer(Modifier.width(8.dp))
            Column {
                Text(server.title?.uppercase() ?: "SERVER", style = MaterialTheme.typography.h5, color = CelestiaTheme.colors.textPrimary)
                Text("Настройки запуска", style = MaterialTheme.typography.caption, color = CelestiaTheme.colors.textSecondary)
            }
        }

        Spacer(Modifier.height(24.dp))

        Row(Modifier.fillMaxSize()) {
            GlassCard(Modifier.weight(1f).fillMaxHeight()) {
                Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState())) {
                    Text("СИСТЕМА", style = MaterialTheme.typography.subtitle2, color = CelestiaTheme.colors.primary)
                    Spacer(Modifier.height(16.dp))

                    Text("ОЗУ: ${memory.roundToInt()} MB", color = CelestiaTheme.colors.textSecondary)
                    Slider(
                        value = memory, onValueChange = { memory = it }, valueRange = 1024f..16384f, steps = 14,
                        colors = SliderDefaults.colors(thumbColor = CelestiaTheme.colors.primary, activeTrackColor = CelestiaTheme.colors.primary)
                    )

                    Spacer(Modifier.height(24.dp))
                    Divider(color = CelestiaTheme.colors.border)
                    Spacer(Modifier.height(24.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = useCustomJava, onCheckedChange = { useCustomJava = it },
                            colors = CheckboxDefaults.colors(checkedColor = CelestiaTheme.colors.primary)
                        )
                        Text("Своя версия Java", color = CelestiaTheme.colors.textPrimary)
                    }

                    if (useCustomJava) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = javaPath, onValueChange = { javaPath = it },
                            label = { Text("Путь к java.exe") }, modifier = Modifier.fillMaxWidth(),
                            trailingIcon = {
                                IconButton(onClick = {
                                    val file = pickExecutable("Выберите Java Executable")
                                    if (file != null) javaPath = file.absolutePath
                                }) { Icon(Icons.Default.Edit, null, tint = CelestiaTheme.colors.textSecondary) }
                            },
                            colors = TextFieldDefaults.outlinedTextFieldColors(
                                textColor = CelestiaTheme.colors.textPrimary,
                                focusedBorderColor = CelestiaTheme.colors.primary,
                                unfocusedBorderColor = CelestiaTheme.colors.border
                            )
                        )
                    } else {
                        val autoJavaLabel = server.version.let { ver ->
                            when {
                                ver.startsWith("1.2") -> "Java 21"
                                ver.startsWith("1.17") || ver.startsWith("1.18") || ver.startsWith("1.19") || ver.startsWith("1.20") -> "Java 17"
                                else -> "Java 8"
                            }
                        }
                        Text("Автоматическая Java ($autoJavaLabel)", style = MaterialTheme.typography.caption, color = CelestiaTheme.colors.textSecondary)
                    }

                    Spacer(Modifier.weight(1f))
                    CelestiaButton("Открыть папку", onClick = {
                        val path = dataDirectory.resolve("clients").resolve(server.assetDir)
                        if (!path.toFile().exists()) path.toFile().mkdirs()
                        Desktop.getDesktop().open(path.toFile())
                    }, modifier = Modifier.fillMaxWidth(), primary = false)

                    Spacer(Modifier.height(12.dp))
                    CelestiaButton("Сбросить клиент", onClick = {
                        val path = dataDirectory.resolve("clients").resolve(server.assetDir)
                        path.toFile().deleteRecursively()
                        saveProfile()
                        onBack()
                    }, modifier = Modifier.fillMaxWidth(), primary = false)
                }
            }

            Spacer(Modifier.width(24.dp))

            GlassCard(Modifier.weight(1f).fillMaxHeight()) {
                Column(Modifier.padding(24.dp)) {
                    Text("МОДИФИКАЦИИ", style = MaterialTheme.typography.subtitle2, color = CelestiaTheme.colors.primary)
                    Spacer(Modifier.height(16.dp))

                    if (mods.isEmpty() && modsLoaded) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Нет опциональных модов", color = CelestiaTheme.colors.textSecondary)
                        }
                    } else {
                        AnimatedVisibility(
                            visible = modsLoaded,
                            enter = slideInVertically(initialOffsetY = { 50 }, animationSpec = tween(500)) + fadeIn(tween(500))
                        ) {
                            LazyColumn(Modifier.fillMaxSize()) {
                                items(mods) { mod ->
                                    ModItemRow(
                                        mod = mod,
                                        isChecked = modStates[mod.id] ?: false,
                                        onToggle = { isChecked ->
                                            modStates[mod.id] = isChecked
                                            if (isChecked) mod.excludings.forEach { conflict -> modStates[conflict] = false }
                                            saveProfile()
                                        }
                                    )
                                    Spacer(Modifier.height(8.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ModItemRow(mod: OptionalMod, isChecked: Boolean, onToggle: (Boolean) -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    val backgroundColor by animateColorAsState(
        targetValue = if (isChecked) CelestiaTheme.colors.primary.copy(alpha = 0.2f) else CelestiaTheme.colors.background.copy(alpha = 0.5f),
        animationSpec = tween(300)
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor)
            .clickable { onToggle(!isChecked) }
            .padding(12.dp)
            .animateContentSize()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = isChecked,
                onCheckedChange = null,
                colors = CheckboxDefaults.colors(checkedColor = CelestiaTheme.colors.primary)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(mod.name, style = MaterialTheme.typography.body2, color = CelestiaTheme.colors.textPrimary)
            }

            if (!mod.description.isNullOrEmpty()) {
                IconButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Info",
                        tint = if (expanded) CelestiaTheme.colors.primary else CelestiaTheme.colors.textSecondary
                    )
                }
            }
        }

        if (expanded && !mod.description.isNullOrEmpty()) {
            Spacer(Modifier.height(8.dp))
            Divider(color = CelestiaTheme.colors.border.copy(alpha = 0.3f))
            Spacer(Modifier.height(8.dp))
            Text(
                text = mod.description!!,
                style = MaterialTheme.typography.caption,
                color = CelestiaTheme.colors.textSecondary,
                modifier = Modifier.padding(start = 32.dp)
            )
        }
    }
}

private fun pickExecutable(title: String): File? {
    val dialog = java.awt.FileDialog(null as JFrame?, title, java.awt.FileDialog.LOAD)
    dialog.isVisible = true
    return if (dialog.directory != null && dialog.file != null) File(dialog.directory, dialog.file) else null
}

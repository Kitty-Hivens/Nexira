package hivens.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import hivens.core.api.interfaces.IManifestProcessorService
import hivens.core.api.model.ServerProfile
import hivens.core.data.InstanceProfile
import hivens.core.data.OptionalMod
import hivens.launcher.ProfileManager
import hivens.ui.components.CelestiaButton
import hivens.ui.components.GlassCard
import hivens.ui.theme.CelestiaTheme
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.openFilePicker
import io.github.vinceglb.filekit.path
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.awt.Desktop
import java.nio.file.Path
import kotlin.math.roundToInt

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ServerSettingsScreen(server: ServerProfile, onBack: () -> Unit) {
    val profileManager: ProfileManager = koinInject()
    val manifestProcessorService: IManifestProcessorService = koinInject()
    val dataDirectory: Path = koinInject()

    var mods by remember { mutableStateOf<List<OptionalMod>>(emptyList()) }
    var profile by remember { mutableStateOf<InstanceProfile?>(null) }

    var javaPath by remember { mutableStateOf("") }
    var memory by remember { mutableStateOf(4096f) }

    val modStates = remember { mutableStateMapOf<String, Boolean>() }
    var modsLoaded by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope() // Нужен для запуска FileKit

    LaunchedEffect(server) {
        val p = profileManager.getProfile(server.assetDir)
        profile = p
        javaPath = p.javaPath ?: ""
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
            p.javaPath = javaPath.ifBlank { null }
            p.memoryMb = memory.roundToInt()
            modStates.forEach { (id, state) -> p.optionalModsState[id] = state }
            profileManager.saveProfile(p)
        }
    }

    val recommendedJavaLabel = remember(server.version) {
        val ver = server.version
        when {
            ver.startsWith("1.2") -> "Java 21"
            ver.startsWith("1.17") || ver.startsWith("1.18") || ver.startsWith("1.19") || ver.startsWith("1.20") -> "Java 17"
            else -> "Java 8"
        }
    }

    val borderColor = CelestiaTheme.colors.textSecondary.copy(alpha = 0.2f)

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
                        value = memory,
                        onValueChange = { memory = it },
                        valueRange = 1024f..16384f,
                        steps = 30,
                        colors = SliderDefaults.colors(
                            thumbColor = CelestiaTheme.colors.primary,
                            activeTrackColor = CelestiaTheme.colors.primary,
                            inactiveTrackColor = borderColor
                        )
                    )

                    Spacer(Modifier.height(24.dp))
                    Divider(color = borderColor)
                    Spacer(Modifier.height(24.dp))

                    Text("Версия Java", color = CelestiaTheme.colors.textPrimary, style = MaterialTheme.typography.body2)
                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = javaPath,
                        onValueChange = { javaPath = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { it.isFocused },
                        placeholder = {
                            Text("Автоматически ($recommendedJavaLabel)", color = CelestiaTheme.colors.textSecondary.copy(alpha = 0.5f))
                        },
                        singleLine = true,
                        colors = TextFieldDefaults.outlinedTextFieldColors(
                            textColor = CelestiaTheme.colors.textPrimary,
                            cursorColor = CelestiaTheme.colors.primary,
                            focusedBorderColor = CelestiaTheme.colors.primary,
                            unfocusedBorderColor = borderColor,
                            backgroundColor = Color.Transparent
                        ),
                        trailingIcon = {
                            IconButton(onClick = {
                                scope.launch {
                                    val file = FileKit.openFilePicker(
                                        type = FileKitType.File(extensions = listOf("exe", "bin")),
                                        mode = FileKitMode.Single,
                                        title = "Выберите Java"
                                    )
                                    file?.path?.let { javaPath = it }
                                }
                            }) {
                                Icon(Icons.Default.Folder, null, tint = CelestiaTheme.colors.primary)
                            }
                        }
                    )

                    if (javaPath.isEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Оставьте пустым для использования встроенной Java",
                            style = MaterialTheme.typography.caption,
                            color = CelestiaTheme.colors.textSecondary
                        )
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
                                    val currentState = modStates[mod.id] ?: mod.isDefault

                                    ModItemRow(
                                        mod = mod,
                                        isChecked = currentState,
                                        onToggle = { isChecked ->
                                            modStates[mod.id] = isChecked
                                            // Если включаем мод, выключаем конфликтующие
                                            if (isChecked) {
                                                mod.excludings.forEach { conflict -> modStates[conflict] = false }
                                            }
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ModItemRow(mod: OptionalMod, isChecked: Boolean, onToggle: (Boolean) -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    val backgroundColor by animateColorAsState(
        targetValue = if (isChecked) CelestiaTheme.colors.primary.copy(alpha = 0.15f) else CelestiaTheme.colors.background.copy(alpha = 0.3f),
        animationSpec = tween(300)
    )

    val borderColor = if (isChecked) CelestiaTheme.colors.primary.copy(alpha = 0.5f) else Color.Transparent

    TooltipArea(
        tooltip = {
            if (!mod.description.isNullOrEmpty()) {
                Surface(
                    color = Color.Black.copy(alpha = 0.9f),
                    shape = RoundedCornerShape(8.dp),
                    elevation = 4.dp,
                    modifier = Modifier.padding(10.dp).widthIn(max = 300.dp)
                ) {
                    Text(
                        text = mod.description!!,
                        color = Color.White,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.caption
                    )
                }
            }
        },
        delayMillis = 600
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(backgroundColor)
                .border(1.dp, borderColor, RoundedCornerShape(8.dp))
                .clickable { onToggle(!isChecked) }
                .padding(12.dp)
                .animateContentSize()
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = isChecked,
                    onCheckedChange = null,
                    colors = CheckboxDefaults.colors(
                        checkedColor = CelestiaTheme.colors.primary,
                        uncheckedColor = CelestiaTheme.colors.textSecondary.copy(alpha = 0.5f)
                    )
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
                            tint = if (expanded) CelestiaTheme.colors.primary else CelestiaTheme.colors.textSecondary.copy(alpha = 0.5f)
                        )
                    }
                }
            }

            if (expanded && !mod.description.isNullOrEmpty()) {
                Spacer(Modifier.height(8.dp))
                Divider(color = CelestiaTheme.colors.textSecondary.copy(alpha = 0.2f))
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
}

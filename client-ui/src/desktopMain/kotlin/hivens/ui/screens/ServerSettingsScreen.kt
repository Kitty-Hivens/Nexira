package hivens.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TooltipDefaults.rememberTooltipPositionProvider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import hivens.core.api.interfaces.IManifestProcessorService
import hivens.core.api.model.ServerProfile
import hivens.core.data.InstanceProfile
import hivens.core.data.OptionalMod
import hivens.launcher.ProfileManager
import hivens.ui.components.CelestiaButton
import hivens.ui.components.GlassCard
import hivens.ui.i18n.LocalStrings
import hivens.ui.theme.CelestiaTheme
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.openFilePicker
import io.github.vinceglb.filekit.path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.awt.Desktop
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.imageio.ImageIO
import kotlin.math.roundToInt

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ServerSettingsScreen(server: ServerProfile, onBack: () -> Unit) {
    val profileManager: ProfileManager               = koinInject()
    val manifestProcessorService: IManifestProcessorService = koinInject()
    val dataDirectory: Path                          = koinInject()
    val s = LocalStrings.current

    var mods       by remember { mutableStateOf<List<OptionalMod>>(emptyList()) }
    var profile    by remember { mutableStateOf<InstanceProfile?>(null) }
    var javaPath   by remember { mutableStateOf("") }
    var memory     by remember { mutableStateOf(4096f) }
    var jvmArgs    by remember { mutableStateOf("") }
    var winWidth   by remember { mutableStateOf("925") }
    var winHeight  by remember { mutableStateOf("530") }
    var fullScreen by remember { mutableStateOf(false) }
    var autoConnect by remember { mutableStateOf(true) }
    var serverIcon by remember { mutableStateOf<ImageBitmap?>(null) }

    val modStates  = remember { mutableStateMapOf<String, Boolean>() }
    var modsLoaded by remember { mutableStateOf(false) }
    val scope      = rememberCoroutineScope()

    // Load profile and icon
    LaunchedEffect(server) {
        val p = profileManager.getProfile(server.assetDir)
        profile  = p
        javaPath = p.javaPath ?: ""
        jvmArgs  = p.jvmArgs ?: ""
        winWidth = p.windowWidth.toString()
        winHeight = p.windowHeight.toString()
        fullScreen = p.fullScreen
        autoConnect = p.autoConnect
        if (p.memoryMb > 0) memory = p.memoryMb.toFloat()

        val loadedMods = manifestProcessorService.getOptionalModsForClient(server)
        mods = loadedMods
        loadedMods.forEach { mod ->
            modStates[mod.id] = p.optionalModsState.getOrDefault(mod.id, mod.isDefault)
        }
        modsLoaded = true

        // Load icon
        withContext(Dispatchers.IO) {
            val iconFile = getServerIconFile(dataDirectory, server)
            if (iconFile.exists()) {
                runCatching {
                    serverIcon = ImageIO.read(iconFile)?.toComposeImageBitmap()
                }
            }
        }
    }

    fun saveProfile() {
        profile?.let { p ->
            p.javaPath = javaPath.ifBlank { null }
            p.memoryMb = memory.roundToInt()
            p.jvmArgs = jvmArgs.ifBlank { null }
            p.windowWidth = winWidth.toIntOrNull() ?: 925
            p.windowHeight = winHeight.toIntOrNull() ?: 530
            p.fullScreen = fullScreen
            p.autoConnect = autoConnect
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
        // ── Header ────────────────────────────────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { saveProfile(); onBack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, s.navBack, tint = CelestiaTheme.colors.textPrimary)
            }
            Spacer(Modifier.width(8.dp))

            // Server icon preview + upload
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(CelestiaTheme.colors.surface)
                    .clickable {
                        scope.launch {
                            val file = FileKit.openFilePicker(
                                type = FileKitType.File(extensions = listOf("png", "jpg", "jpeg")),
                                mode = FileKitMode.Single,
                                title = s.serverSettingsPickIcon
                            )
                            file?.path?.let { selectedPath ->
                                withContext(Dispatchers.IO) {
                                    val targetFile = getServerIconFile(dataDirectory, server)
                                    targetFile.parentFile.mkdirs()
                                    Files.copy(
                                        Path.of(selectedPath),
                                        targetFile.toPath(),
                                        StandardCopyOption.REPLACE_EXISTING
                                    )
                                    runCatching {
                                        serverIcon = ImageIO.read(targetFile)?.toComposeImageBitmap()
                                    }
                                }
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                if (serverIcon != null) {
                    Image(
                        painter = BitmapPainter(serverIcon!!),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        Icons.Default.Image,
                        contentDescription = null,
                        tint = CelestiaTheme.colors.textSecondary.copy(alpha = 0.4f),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Column {
                Text(server.title?.uppercase() ?: "SERVER", style = MaterialTheme.typography.headlineSmall, color = CelestiaTheme.colors.textPrimary)
                Text(s.serverSettingsSubtitle, style = MaterialTheme.typography.bodySmall, color = CelestiaTheme.colors.textSecondary)
            }
        }

        Spacer(Modifier.height(24.dp))

        Row(Modifier.fillMaxSize()) {
            // ══════════════════════════════════════════════════════════════════
            // LEFT COLUMN — System settings
            // ══════════════════════════════════════════════════════════════════
            GlassCard(Modifier.weight(1f).fillMaxHeight()) {
                Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState())) {

                    // ── SYSTEM ────────────────────────────────────────────────
                    Text(s.serverSettingsSectionSystem, style = MaterialTheme.typography.titleSmall, color = CelestiaTheme.colors.primary)
                    Spacer(Modifier.height(16.dp))

                    // RAM slider
                    Text(s.serverSettingsRamValue(memory.roundToInt()), color = CelestiaTheme.colors.textSecondary)
                    Slider(
                        value         = memory,
                        onValueChange = { memory = it },
                        valueRange    = 1024f..16384f,
                        steps         = 30,
                        colors        = SliderDefaults.colors(
                            thumbColor         = CelestiaTheme.colors.primary,
                            activeTrackColor   = CelestiaTheme.colors.primary,
                            inactiveTrackColor = borderColor
                        )
                    )

                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider(color = borderColor)
                    Spacer(Modifier.height(16.dp))

                    // Java path
                    Text(s.serverSettingsJava, color = CelestiaTheme.colors.textPrimary, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value         = javaPath,
                        onValueChange = { javaPath = it },
                        modifier      = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { it.isFocused },
                        placeholder   = {
                            Text(
                                s.serverSettingsJavaAuto(recommendedJavaLabel),
                                color = CelestiaTheme.colors.textSecondary.copy(alpha = 0.5f)
                            )
                        },
                        singleLine = true,
                        colors = settingsFieldColors(),
                        trailingIcon = {
                            IconButton(onClick = {
                                scope.launch {
                                    val file = FileKit.openFilePicker(
                                        type  = FileKitType.File(extensions = listOf("exe", "bin")),
                                        mode  = FileKitMode.Single,
                                        title = s.serverSettingsPickJava
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
                        Text(s.serverSettingsJavaHint, style = MaterialTheme.typography.bodySmall, color = CelestiaTheme.colors.textSecondary)
                    }

                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider(color = borderColor)
                    Spacer(Modifier.height(16.dp))

                    // ── JVM ARGUMENTS ──────────────────────────────────────────
                    Text(s.serverSettingsJvmArgs, color = CelestiaTheme.colors.textPrimary, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = jvmArgs,
                        onValueChange = { jvmArgs = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = {
                            Text(
                                s.serverSettingsJvmArgsHint,
                                color = CelestiaTheme.colors.textSecondary.copy(alpha = 0.5f)
                            )
                        },
                        singleLine = false,
                        minLines = 2,
                        maxLines = 4,
                        colors = settingsFieldColors()
                    )

                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider(color = borderColor)
                    Spacer(Modifier.height(16.dp))

                    // ── WINDOW RESOLUTION ─────────────────────────────────────
                    Text(s.serverSettingsResolution, color = CelestiaTheme.colors.textPrimary, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = winWidth,
                            onValueChange = { winWidth = it.filter { c -> c.isDigit() } },
                            modifier = Modifier.weight(1f),
                            label = { Text(s.serverSettingsWidth) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            colors = settingsFieldColors()
                        )
                        Text("×", color = CelestiaTheme.colors.textSecondary, style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(
                            value           = winHeight,
                            onValueChange   = { winHeight = it.filter { c -> c.isDigit() } },
                            modifier        = Modifier.weight(1f),
                            label           = { Text(s.serverSettingsHeight) },
                            singleLine      = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            colors = settingsFieldColors()
                        )
                    }

                    Spacer(Modifier.height(16.dp))

                    // ── FULLSCREEN ─────────────────────────────────────────────
                    SettingsToggleRow(
                        title = s.serverSettingsFullscreen,
                        checked = fullScreen,
                        onCheckedChange = { fullScreen = it }
                    )

                    Spacer(Modifier.height(8.dp))

                    // ── AUTO CONNECT ───────────────────────────────────────────
                    SettingsToggleRow(
                        title = s.serverSettingsAutoConnect,
                        checked = autoConnect,
                        onCheckedChange = { autoConnect = it }
                    )

                    Spacer(Modifier.weight(1f))

                    // ── Actions ───────────────────────────────────────────────
                    CelestiaButton(s.serverSettingsOpenFolder, onClick = {
                        val path = dataDirectory.resolve("clients").resolve(server.assetDir)
                        if (!path.toFile().exists()) path.toFile().mkdirs()
                        Desktop.getDesktop().open(path.toFile())
                    }, modifier = Modifier.fillMaxWidth(), primary = false)

                    Spacer(Modifier.height(12.dp))

                    CelestiaButton(s.serverSettingsReset, onClick = {
                        val path = dataDirectory.resolve("clients").resolve(server.assetDir)
                        path.toFile().deleteRecursively()
                        saveProfile()
                        onBack()
                    }, modifier = Modifier.fillMaxWidth(), primary = false)
                }
            }

            Spacer(Modifier.width(24.dp))

            // ══════════════════════════════════════════════════════════════════
            // RIGHT COLUMN — Mods
            // ══════════════════════════════════════════════════════════════════
            GlassCard(Modifier.weight(1f).fillMaxHeight()) {
                Column(Modifier.padding(24.dp)) {
                    Text(s.serverSettingsSectionMods, style = MaterialTheme.typography.titleSmall, color = CelestiaTheme.colors.primary)
                    Spacer(Modifier.height(16.dp))

                    if (mods.isEmpty() && modsLoaded) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(s.serverSettingsNoMods, color = CelestiaTheme.colors.textSecondary)
                        }
                    } else {
                        AnimatedVisibility(
                            visible = modsLoaded,
                            enter   = slideInVertically(initialOffsetY = { 50 }, animationSpec = tween(500)) + fadeIn(tween(500))
                        ) {
                            LazyColumn(Modifier.fillMaxSize()) {
                                items(mods) { mod ->
                                    val currentState = modStates[mod.id] ?: mod.isDefault

                                    ModItemRow(
                                        mod       = mod,
                                        isChecked = currentState,
                                        onToggle  = { isChecked ->
                                            modStates[mod.id] = isChecked
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

// ── Helpers ──────────────────────────────────────────────────────────────────

private fun getServerIconFile(dataDirectory: Path, server: ServerProfile): File {
    return dataDirectory.resolve("clients/${server.assetDir}/icon.png").toFile()
}

@Composable
private fun settingsFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = CelestiaTheme.colors.textPrimary,
    unfocusedTextColor = CelestiaTheme.colors.textPrimary,
    cursorColor = CelestiaTheme.colors.primary,
    focusedBorderColor = CelestiaTheme.colors.primary,
    unfocusedBorderColor = CelestiaTheme.colors.textSecondary.copy(alpha = 0.2f),
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent
)

@Composable
private fun SettingsToggleRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, color = CelestiaTheme.colors.textPrimary)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = CelestiaTheme.colors.primary,
                checkedTrackColor = CelestiaTheme.colors.primary.copy(alpha = 0.5f)
            )
        )
    }
}

// ── ModItemRow ───────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ModItemRow(mod: OptionalMod, isChecked: Boolean, onToggle: (Boolean) -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    val backgroundColor by animateColorAsState(
        targetValue   = if (isChecked) CelestiaTheme.colors.primary.copy(alpha = 0.15f)
        else CelestiaTheme.colors.background.copy(alpha = 0.3f),
        animationSpec = tween(300)
    )

    val borderColor = if (isChecked) CelestiaTheme.colors.primary.copy(alpha = 0.5f) else Color.Transparent

    TooltipBox(
        positionProvider = rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = {
            if (!mod.description.isNullOrEmpty()) {
                PlainTooltip {
                    Text(mod.description!!, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        state = rememberTooltipState()
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
                    checked         = isChecked,
                    onCheckedChange = null,
                    colors          = CheckboxDefaults.colors(
                        checkedColor   = CelestiaTheme.colors.primary,
                        uncheckedColor = CelestiaTheme.colors.textSecondary.copy(alpha = 0.5f)
                    )
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(mod.name, style = MaterialTheme.typography.bodyMedium, color = CelestiaTheme.colors.textPrimary)
                }

                if (!mod.description.isNullOrEmpty()) {
                    IconButton(
                        onClick  = { expanded = !expanded },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector        = Icons.Default.Info,
                            contentDescription = null,
                            tint               = if (expanded) CelestiaTheme.colors.primary else CelestiaTheme.colors.textSecondary.copy(alpha = 0.5f)
                        )
                    }
                }
            }

            if (expanded && !mod.description.isNullOrEmpty()) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = CelestiaTheme.colors.textSecondary.copy(alpha = 0.2f))
                Spacer(Modifier.height(8.dp))
                Text(
                    text     = mod.description!!,
                    style    = MaterialTheme.typography.bodySmall,
                    color    = CelestiaTheme.colors.textSecondary,
                    modifier = Modifier.padding(start = 32.dp)
                )
            }
        }
    }
}

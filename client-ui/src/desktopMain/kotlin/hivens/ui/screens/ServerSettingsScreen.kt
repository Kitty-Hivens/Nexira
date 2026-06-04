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
import hivens.core.api.PlayerRepository
import hivens.core.api.interfaces.IManifestProcessorService
import hivens.core.api.interfaces.ISettingsService
import hivens.core.api.model.ServerProfile
import hivens.core.data.InstanceProfile
import hivens.core.data.OptionalMod
import hivens.core.jvm.AutomaticHeap
import hivens.core.jvm.SystemMemory
import hivens.launcher.CredentialsManager
import hivens.launcher.ProfileManager
import hivens.launcher.ProfilerProfileStore
import hivens.ui.components.CelestiaButton
import hivens.ui.components.GlassCard
import hivens.ui.components.JvmArgsBuilderDialog
import hivens.ui.components.ModItemCard
import hivens.ui.components.RamSelector
import hivens.ui.customization.glassSurfaceAlpha
import hivens.ui.debug.SkiaTracker
import hivens.ui.i18n.LocalStrings
import hivens.ui.puppet.PuppetClick
import hivens.ui.puppet.PuppetField
import hivens.ui.puppet.PuppetScreen
import hivens.ui.puppet.PuppetToggle
import hivens.ui.theme.CelestiaTheme
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.openFilePicker
import io.github.vinceglb.filekit.path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import hivens.ui.platform.SystemActions
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.imageio.ImageIO
import kotlin.time.Duration.Companion.milliseconds

private sealed class SpawnResetState {
    object Idle    : SpawnResetState()
    object Loading : SpawnResetState()
    object Success : SpawnResetState()
    object Error   : SpawnResetState()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ServerSettingsScreen(server: ServerProfile, onBack: () -> Unit) {
    val profileManager: ProfileManager               = koinInject()
    val manifestProcessorService: IManifestProcessorService = koinInject()
    val dataDirectory: Path                          = koinInject()
    val playerRepository: PlayerRepository           = koinInject()
    val credentialsManager: CredentialsManager       = koinInject()
    val settingsService: ISettingsService            = koinInject()
    val profilerStore: ProfilerProfileStore          = koinInject()
    val s = LocalStrings.current

    val jvmBuilderEnabled = remember { settingsService.getSettings().jvmBuilderEnabled }
    var showJvmBuilder by remember { mutableStateOf(false) }

    var mods       by remember { mutableStateOf<List<OptionalMod>>(emptyList()) }
    var profile    by remember { mutableStateOf<InstanceProfile?>(null) }
    var javaPath   by remember { mutableStateOf("") }
    var memory     by remember { mutableStateOf(4096) }
    var isAutoMode by remember { mutableStateOf(true) }
    var resolvedAutoMb by remember { mutableStateOf(AutomaticHeap.compute(SystemMemory.totalPhysicalMb())) }
    var jvmArgs    by remember { mutableStateOf("") }
    var winWidth   by remember { mutableStateOf("925") }
    var winHeight  by remember { mutableStateOf("530") }
    var fullScreen by remember { mutableStateOf(false) }
    var autoConnect by remember { mutableStateOf(true) }
    var serverIcon by remember { mutableStateOf<ImageBitmap?>(null) }
    var spawnResetState by remember { mutableStateOf<SpawnResetState>(SpawnResetState.Idle) }

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
        if (p.memoryMb > 0) memory = p.memoryMb

        // RAM mode: Auto unless the user pinned a value. The Auto chip shows what the next
        // launch will actually use -- the adaptive-derived heap when adaptive is on and has
        // data, otherwise the machine-aware Automatic baseline (mirrors LauncherService).
        isAutoMode = !p.fixedMemory
        val settings = settingsService.getSettings()
        val adaptiveOn = settings.experimentalFeaturesEnabled && settings.adaptiveMemoryEnabled
        val clientDir = dataDirectory.resolve("clients").resolve(server.assetDir)
        val derivedMb = if (adaptiveOn) withContext(Dispatchers.IO) { profilerStore.readProfile(clientDir)?.derivedHeapMb } else null
        resolvedAutoMb = derivedMb ?: AutomaticHeap.compute(SystemMemory.totalPhysicalMb())

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
            val updated = p.copy(
                javaPath     = javaPath.ifBlank { null },
                memoryMb     = memory,
                fixedMemory = !isAutoMode,
                jvmArgs      = jvmArgs.ifBlank { null },
                windowWidth  = winWidth.toIntOrNull() ?: 925,
                windowHeight = winHeight.toIntOrNull() ?: 530,
                fullScreen   = fullScreen,
                autoConnect  = autoConnect,
            )
            // optionalModsState is a MutableMap. `copy()` shares its reference,
            // so mutating either instance updates the same backing storage --
            // we touch the new instance for clarity.
            modStates.forEach { (id, state) -> updated.optionalModsState[id] = state }
            profileManager.saveProfile(updated)
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

    // Puppet ids prefixed with the server's assetDir so concurrent settings
    // dialogs (theoretical -- Nexira keeps only one open at a time today) stay
    // disambiguated, and tests can verify they're acting on the intended server.
    val pkey = "serverSettings.${server.assetDir}"
    PuppetScreen("ServerSettings.${server.assetDir}")
    PuppetClick("$pkey.backAndSave") { saveProfile(); onBack() }
    PuppetField("$pkey.javaPath", javaPath) { javaPath = it }
    PuppetField("$pkey.jvmArgs", jvmArgs) { jvmArgs = it }
    PuppetField("$pkey.winWidth", winWidth) { winWidth = it.filter { c -> c.isDigit() } }
    PuppetField("$pkey.winHeight", winHeight) { winHeight = it.filter { c -> c.isDigit() } }
    PuppetToggle("$pkey.fullScreen", fullScreen) { fullScreen = it }
    PuppetToggle("$pkey.autoConnect", autoConnect) { autoConnect = it }
    PuppetClick("$pkey.openFolder") {
        val path = dataDirectory.resolve("clients").resolve(server.assetDir)
        if (!path.toFile().exists()) path.toFile().mkdirs()
        SystemActions.openFile(path.toFile())
    }
    PuppetClick("$pkey.resetClient") {
        val path = dataDirectory.resolve("clients").resolve(server.assetDir)
        path.toFile().deleteRecursively()
        saveProfile()
        onBack()
    }
    PuppetClick("$pkey.openJvmBuilder", enabled = jvmBuilderEnabled) { showJvmBuilder = true }
    // Per-mod toggle. Mods load asynchronously -- registry mirrors what's
    // currently composed, so puppet calls before modsLoaded return 404.
    mods.forEach { mod ->
        PuppetToggle("$pkey.mod.${mod.id}", modStates[mod.id] ?: mod.isDefault) {
            modStates[mod.id] = it
        }
    }

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
                                dialogSettings = FileKitDialogSettings(title = s.serverSettingsPickIcon)
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
                                        serverIcon = ImageIO.read(targetFile)?.toComposeImageBitmap()?.also {
                                            SkiaTracker.track("Settings.icon[${server.assetDir}]", it)
                                        }
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
                Text(server.title ?: "Server", style = MaterialTheme.typography.headlineSmall, color = CelestiaTheme.colors.textPrimary)
                Text(s.serverSettingsSubtitle, style = MaterialTheme.typography.bodySmall, color = CelestiaTheme.colors.textSecondary)
            }
        }

        Spacer(Modifier.height(24.dp))

        Row(Modifier.fillMaxSize()) {
            // ══════════════════════════════════════════════════════════════════
            // LEFT COLUMN -- System settings
            // ══════════════════════════════════════════════════════════════════
            GlassCard(
                modifier        = Modifier.weight(1f).fillMaxHeight(),
                backgroundColor = glassSurfaceAlpha(0.7f),
            ) {
                Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState())) {

                    // ── SYSTEM ────────────────────────────────────────────────
                    Text(s.serverSettingsSectionSystem, style = MaterialTheme.typography.titleSmall, color = CelestiaTheme.colors.primary)
                    Spacer(Modifier.height(16.dp))

                    // ── RAM -- RamSelector replaces old Slider ─────────────────
                    RamSelector(
                        isAuto = isAutoMode,
                        resolvedAutoMb = resolvedAutoMb,
                        currentMb = memory,
                        // Auto un-pins (fixedMemory=false); picking a value pins (Fixed).
                        onAutoSelected = { isAutoMode = true },
                        onValueChanged = { memory = it; isAutoMode = false },
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
                        singleLine    = true,
                        colors        = settingsFieldColors(),
                        trailingIcon  = {
                            IconButton(onClick = {
                                scope.launch {
                                    val file = FileKit.openFilePicker(
                                        type  = FileKitType.File(extensions = listOf("exe", "bin")),
                                        dialogSettings = FileKitDialogSettings(title = s.serverSettingsPickJava)
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
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(s.serverSettingsJvmArgs, color = CelestiaTheme.colors.textPrimary, style = MaterialTheme.typography.bodyMedium)
                        if (jvmBuilderEnabled) {
                            TextButton(onClick = { showJvmBuilder = true }) {
                                Icon(Icons.Default.Tune, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(s.serverSettingsJvmBuildArgs)
                            }
                        }
                    }
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

                    // ── Open folder ───────────────────────────────────────────
                    // Was AprilFoolsButton with a colors-passthrough hack -- produced
                    // a flat filled chip that visually clashed with CelestiaButton's
                    // outlined style on Reset Client. Until AprilFools gets a proper
                    // CelestiaButton-based wrapper that can also host the future
                    // Atelier style variants, we use plain CelestiaButton here and
                    // accept losing this button's April Fools chaos integration.
                    CelestiaButton(
                        text     = s.serverSettingsOpenFolder,
                        onClick  = {
                            val path = dataDirectory.resolve("clients").resolve(server.assetDir)
                            if (!path.toFile().exists()) path.toFile().mkdirs()
                            SystemActions.openFile(path.toFile())
                        },
                        modifier = Modifier.fillMaxWidth(),
                        primary  = false,
                    )

                    Spacer(Modifier.height(12.dp))

                    // Reset client -- NOT chaos-wrapped (destructive action)
                    CelestiaButton(s.serverSettingsReset, onClick = {
                        val path = dataDirectory.resolve("clients").resolve(server.assetDir)
                        path.toFile().deleteRecursively()
                        saveProfile()
                        onBack()
                    }, modifier = Modifier.fillMaxWidth(), primary = false)

                    // ── Return to spawn (only 1.12.2) -- chaos target in Idle ──
                    if (server.version.startsWith("1.12")) {
                        Spacer(Modifier.height(12.dp))

                        if (spawnResetState == SpawnResetState.Idle) {
                            // Only chaos-wrap when idle -- Loading/Success/Error states need reliable feedback
                            // Same rationale as the Open Folder button above -- uses
                            // CelestiaButton until AprilFoolsButton gets a wrapper
                            // that respects the Celestia / Atelier visual systems.
                            CelestiaButton(
                                text     = s.spawnResetButton,
                                onClick  = {
                                    spawnResetState = SpawnResetState.Loading
                                    scope.launch {
                                        val credentials = withContext(Dispatchers.IO) {
                                            credentialsManager.load()
                                        }
                                        if (credentials == null) {
                                            spawnResetState = SpawnResetState.Error
                                            delay(3000.milliseconds)
                                            spawnResetState = SpawnResetState.Idle
                                            return@launch
                                        }
                                        val ok = withContext(Dispatchers.IO) {
                                            playerRepository.resetSpawn(credentials, server.name)
                                        }
                                        spawnResetState = if (ok) SpawnResetState.Success else SpawnResetState.Error
                                        delay(3000.milliseconds)
                                        spawnResetState = SpawnResetState.Idle
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                primary  = false,
                            )
                        } else {
                            // Loading / Success / Error -- plain reliable button, no chaos
                            CelestiaButton(
                                text    = when (spawnResetState) {
                                    SpawnResetState.Loading -> s.spawnResetLoading
                                    SpawnResetState.Success -> s.spawnResetSuccess
                                    SpawnResetState.Error   -> s.spawnResetError
                                    SpawnResetState.Idle    -> s.spawnResetButton
                                },
                                enabled  = spawnResetState != SpawnResetState.Loading,
                                onClick  = {},
                                modifier = Modifier.fillMaxWidth(),
                                primary  = false
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.width(24.dp))

            // ══════════════════════════════════════════════════════════════════
            // RIGHT COLUMN -- Mods
            // ══════════════════════════════════════════════════════════════════
            GlassCard(
                modifier        = Modifier.weight(1f).fillMaxHeight(),
                backgroundColor = glassSurfaceAlpha(0.7f),
            ) {
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

                                    ModItemCard(
                                        mod       = mod,
                                        isChecked = currentState,
                                        onToggle  = { isChecked ->
                                            modStates[mod.id] = isChecked
                                            if (isChecked) {
                                                mod.excludings.forEach { conflict -> modStates[conflict] = false }
                                            }
                                            saveProfile()
                                        },
                                        enabledModIds = modStates.filter { it.value }.keys
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

    // ── JVM args builder dialog (experimental, opt-in) ─────────────────
    if (showJvmBuilder) {
        JvmArgsBuilderDialog(
            initial = hivens.core.jvm.JvmArgsPresets.default.config,
            onDismiss = { showJvmBuilder = false },
            onApply = { newArgs ->
                jvmArgs = newArgs
                showJvmBuilder = false
                saveProfile()
            },
        )
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

package hivens.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import hivens.core.api.model.ServerProfile
import hivens.ui.components.CelestiaButton
import hivens.ui.components.DestructiveConfirmDialog
import hivens.ui.components.GlassCard
import hivens.ui.components.JvmArgsBuilderDialog
import hivens.ui.components.ModItemCard
import hivens.ui.components.RamSelector
import hivens.ui.customization.glassSurfaceAlpha
import hivens.ui.i18n.LocalStrings
import hivens.ui.puppet.PuppetClick
import hivens.ui.puppet.PuppetField
import hivens.ui.puppet.PuppetScreen
import hivens.ui.puppet.PuppetToggle
import hivens.ui.theme.CelestiaTheme

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ServerSettingsScreen(server: ServerProfile, onBack: () -> Unit) {
    val s = LocalStrings.current
    val state = rememberServerSettingsState(server)

    var showJvmBuilder by remember { mutableStateOf(false) }
    var pendingReset by remember { mutableStateOf(false) }

    // Load profile + icon when the screen (re)binds to a server.
    LaunchedEffect(state) { state.load() }

    val recommendedJavaLabel = remember(server.version) {
        val ver = server.version
        when {
            ver.startsWith("1.2") -> "Java 21"
            ver.startsWith("1.17") || ver.startsWith("1.18") || ver.startsWith("1.19") || ver.startsWith("1.20") -> "Java 17"
            else -> "Java 8"
        }
    }

    val borderColor = CelestiaTheme.colors.textSecondary.copy(alpha = 0.2f)

    // Wipes clients/<assetDir> irrecoverably. The visible button gates this on a
    // confirm dialog; the puppet hook runs it directly (automation bypass).
    val performResetClient = { state.resetClient(onBack) }

    // Puppet ids prefixed with the server's assetDir so concurrent settings
    // dialogs (theoretical -- Nexira keeps only one open at a time today) stay
    // disambiguated, and tests can verify they're acting on the intended server.
    val pkey = "serverSettings.${server.assetDir}"
    PuppetScreen("ServerSettings.${server.assetDir}")
    PuppetClick("$pkey.backAndSave") { state.save(); onBack() }
    PuppetField("$pkey.javaPath", state.javaPath) { state.javaPath = it }
    PuppetField("$pkey.jvmArgs", state.jvmArgs) { state.jvmArgs = it }
    PuppetField("$pkey.winWidth", state.winWidth) { state.winWidth = it.filter { c -> c.isDigit() } }
    PuppetField("$pkey.winHeight", state.winHeight) { state.winHeight = it.filter { c -> c.isDigit() } }
    PuppetToggle("$pkey.fullScreen", state.fullScreen) { state.fullScreen = it }
    PuppetToggle("$pkey.autoConnect", state.autoConnect) { state.autoConnect = it }
    PuppetClick("$pkey.openFolder") { state.openClientFolder() }
    PuppetClick("$pkey.resetClient") { performResetClient() }
    PuppetClick("$pkey.openJvmBuilder", enabled = state.jvmBuilderEnabled) { showJvmBuilder = true }
    // Per-mod toggle. Mods load asynchronously -- registry mirrors what's
    // currently composed, so puppet calls before modsLoaded return 404.
    state.mods.forEach { mod ->
        PuppetToggle("$pkey.mod.${mod.id}", state.modStates[mod.id] ?: mod.isDefault) {
            state.modStates[mod.id] = it
        }
    }

    Column(Modifier.fillMaxSize().padding(24.dp)) {
        // ── Header ────────────────────────────────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { state.save(); onBack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, s.navBack, tint = CelestiaTheme.colors.textPrimary)
            }
            Spacer(Modifier.width(8.dp))

            // Server icon preview + upload
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(CelestiaTheme.colors.surface)
                    .clickable { state.pickIcon(s.serverSettingsPickIcon) },
                contentAlignment = Alignment.Center
            ) {
                val icon = state.serverIcon
                if (icon != null) {
                    Image(
                        painter = BitmapPainter(icon),
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
                        isAuto = state.isAutoMode,
                        resolvedAutoMb = state.resolvedAutoMb,
                        currentMb = state.memoryMb,
                        // Auto un-pins (fixedMemory=false); picking a value pins (Fixed).
                        onAutoSelected = { state.isAutoMode = true },
                        onValueChanged = { state.memoryMb = it; state.isAutoMode = false },
                    )

                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider(color = borderColor)
                    Spacer(Modifier.height(16.dp))

                    // Java path
                    Text(s.serverSettingsJava, color = CelestiaTheme.colors.textPrimary, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value         = state.javaPath,
                        onValueChange = { state.javaPath = it },
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
                            IconButton(onClick = { state.pickJava(s.serverSettingsPickJava) }) {
                                Icon(Icons.Default.Folder, null, tint = CelestiaTheme.colors.primary)
                            }
                        }
                    )

                    if (state.javaPath.isEmpty()) {
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
                        if (state.jvmBuilderEnabled) {
                            TextButton(onClick = { showJvmBuilder = true }) {
                                Icon(Icons.Default.Tune, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(s.serverSettingsJvmBuildArgs)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = state.jvmArgs,
                        onValueChange = { state.jvmArgs = it },
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
                            value = state.winWidth,
                            onValueChange = { state.winWidth = it.filter { c -> c.isDigit() } },
                            modifier = Modifier.weight(1f),
                            label = { Text(s.serverSettingsWidth) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            colors = settingsFieldColors()
                        )
                        Text("×", color = CelestiaTheme.colors.textSecondary, style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(
                            value           = state.winHeight,
                            onValueChange   = { state.winHeight = it.filter { c -> c.isDigit() } },
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
                        checked = state.fullScreen,
                        onCheckedChange = { state.fullScreen = it }
                    )

                    Spacer(Modifier.height(8.dp))

                    // ── AUTO CONNECT ───────────────────────────────────────────
                    SettingsToggleRow(
                        title = s.serverSettingsAutoConnect,
                        checked = state.autoConnect,
                        onCheckedChange = { state.autoConnect = it }
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
                        onClick  = { state.openClientFolder() },
                        modifier = Modifier.fillMaxWidth(),
                        primary  = false,
                    )

                    Spacer(Modifier.height(12.dp))

                    // Reset client -- NOT chaos-wrapped (destructive action)
                    CelestiaButton(
                        s.serverSettingsReset,
                        onClick  = { pendingReset = true },
                        modifier = Modifier.fillMaxWidth(),
                        primary  = false,
                    )

                    if (pendingReset) {
                        DestructiveConfirmDialog(
                            title        = s.serverSettingsResetConfirmTitle,
                            body         = s.serverSettingsResetConfirmBody,
                            confirmLabel = s.serverSettingsReset,
                            onConfirm    = performResetClient,
                            onDismiss    = { pendingReset = false },
                        )
                    }

                    // ── Return to spawn (only 1.12.2) -- chaos target in Idle ──
                    if (server.version.startsWith("1.12")) {
                        Spacer(Modifier.height(12.dp))

                        if (state.spawnResetState == SpawnResetState.Idle) {
                            // Only chaos-wrap when idle -- Loading/Success/Error states need reliable feedback
                            // Same rationale as the Open Folder button above -- uses
                            // CelestiaButton until AprilFoolsButton gets a wrapper
                            // that respects the Celestia / Atelier visual systems.
                            CelestiaButton(
                                text     = s.spawnResetButton,
                                onClick  = { state.resetSpawn() },
                                modifier = Modifier.fillMaxWidth(),
                                primary  = false,
                            )
                        } else {
                            // Loading / Success / Error -- plain reliable button, no chaos
                            CelestiaButton(
                                text    = when (state.spawnResetState) {
                                    SpawnResetState.Loading -> s.spawnResetLoading
                                    SpawnResetState.Success -> s.spawnResetSuccess
                                    SpawnResetState.Error   -> s.spawnResetError
                                    SpawnResetState.Idle    -> s.spawnResetButton
                                },
                                enabled  = state.spawnResetState != SpawnResetState.Loading,
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

                    if (state.mods.isEmpty() && state.modsLoaded) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(s.serverSettingsNoMods, color = CelestiaTheme.colors.textSecondary)
                        }
                    } else {
                        AnimatedVisibility(
                            visible = state.modsLoaded,
                            enter   = slideInVertically(initialOffsetY = { 50 }, animationSpec = tween(500)) + fadeIn(tween(500))
                        ) {
                            LazyColumn(Modifier.fillMaxSize()) {
                                items(state.mods) { mod ->
                                    val currentState = state.modStates[mod.id] ?: mod.isDefault

                                    ModItemCard(
                                        mod       = mod,
                                        isChecked = currentState,
                                        onToggle  = { isChecked -> state.toggleMod(mod, isChecked) },
                                        enabledModIds = state.modStates.filter { it.value }.keys
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
                state.jvmArgs = newArgs
                showJvmBuilder = false
                state.save()
            },
        )
    }
}

// ── Helpers ──────────────────────────────────────────────────────────────────

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

package hivens.ui.screens.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hivens.core.api.interfaces.IPackRepository
import hivens.core.api.interfaces.ISettingsService
import hivens.core.data.InstanceRuntime
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.core.jvm.AutomaticHeap
import hivens.core.jvm.SystemMemory
import hivens.launcher.ProfilerProfileStore
import hivens.launcher.launch.LauncherController
import hivens.launcher.platform.PlatformPaths
import hivens.ui.AppState
import hivens.ui.components.RamSelector
import hivens.ui.customization.glassSurfaceAlpha
import hivens.ui.notifications.LaunchTarget
import hivens.ui.notifications.drivers.LaunchDriver
import hivens.ui.i18n.LocalStrings
import hivens.ui.puppet.PuppetClick
import hivens.ui.puppet.PuppetScreen
import hivens.ui.screens.CenteredProgress
import hivens.ui.screens.ConsoleContent
import hivens.ui.screens.ConsoleSource
import hivens.ui.screens.library.FileBrowserPane
import hivens.ui.screens.library.PackMetaChip
import hivens.ui.screens.library.content.ContentTabPane
import hivens.ui.screens.library.worlds.WorldsTabPane
import hivens.ui.theme.CelestiaTheme
import hivens.ui.utils.ConsoleSettingsManager
import hivens.ui.utils.GameConsoleService
import hivens.ui.utils.LogEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.koin.compose.koinInject
import java.io.File
import java.nio.file.Path

/**
 * Library PackDetail. Hero header + Play bar + tabs (Content / Files /
 * Worlds). Resolves the instance lazily via [IPackRepository] from
 * the [Screen.PackDetail.instanceId] in the navigation entry so the
 * sealed Screen class stays small.
 *
 * Tabs are scoped to a single per-instance dir (`<dataDir>/instances/
 * <instance.instanceDirName>`) for Files / Worlds; the Content tab
 * uses [PackInstance.packRef] to fetch the mirror manifest on each
 * open. Logs tab deferred per [[project_logs_tab_open_question]].
 */
@Composable
fun PackDetailScreen(
    instanceId: String,
    appState: AppState,
    onBack: () -> Unit,
) {
    PuppetScreen("PackDetail.$instanceId")
    PuppetClick("packDetail.back") { onBack() }

    val repo: IPackRepository = koinInject()
    val paths: PlatformPaths = koinInject()
    val controller: LauncherController = koinInject()
    val launchDriver: LaunchDriver = koinInject()
    var instance by remember { mutableStateOf<PackInstance?>(null) }
    var resolved by remember { mutableStateOf(false) }
    LaunchedEffect(instanceId) {
        instance = repo.observe().firstOrNull()?.firstOrNull { it.id == instanceId }
            ?: repo.get(instanceId)
        resolved = true
    }

    if (!resolved) {
        CenteredProgress(Modifier.fillMaxSize())
        return
    }
    val pack = instance
    if (pack == null) {
        NotFound(onBack = onBack)
        return
    }

    val instanceDir = remember(pack.instanceDirName) {
        paths.dataDir.resolve("instances").resolve(pack.instanceDirName)
    }

    var tabIndex by remember(pack.id) { mutableIntStateOf(0) }
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        Hero(pack = pack, onBack = onBack)

        Column(
            modifier            = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MetaRow(pack)
            val authedSession = (appState as? AppState.Authenticated)?.session
            PlayBar(
                pack    = pack,
                enabled = authedSession != null,
                onPlay  = {
                    val session = authedSession ?: return@PlayBar
                    // Observer first, then launch: the first-non-Idle
                    // await needs to subscribe before Prepare fires.
                    launchDriver.observe(LaunchTarget.Pack(pack))
                    controller.launchPackInstance(session, pack)
                },
            )
        }

        PrimaryTabRow(
            selectedTabIndex = tabIndex,
            containerColor   = Color.Transparent,
            contentColor     = CelestiaTheme.colors.textPrimary,
        ) {
            Tab(
                selected = tabIndex == 0,
                onClick  = { tabIndex = 0 },
                text     = { Text(s.packDetailTabContent, fontWeight = if (tabIndex == 0) FontWeight.Bold else FontWeight.Normal) },
            )
            Tab(
                selected = tabIndex == 1,
                onClick  = { tabIndex = 1 },
                text     = { Text(s.packDetailTabFiles, fontWeight = if (tabIndex == 1) FontWeight.Bold else FontWeight.Normal) },
            )
            Tab(
                selected = tabIndex == 2,
                onClick  = { tabIndex = 2 },
                text     = { Text(s.packDetailTabWorlds, fontWeight = if (tabIndex == 2) FontWeight.Bold else FontWeight.Normal) },
            )
            Tab(
                selected = tabIndex == 3,
                onClick  = { tabIndex = 3 },
                text     = { Text(s.packDetailTabLogs, fontWeight = if (tabIndex == 3) FontWeight.Bold else FontWeight.Normal) },
            )
            Tab(
                selected = tabIndex == 4,
                onClick  = { tabIndex = 4 },
                text     = { Text(s.packDetailTabSettings, fontWeight = if (tabIndex == 4) FontWeight.Bold else FontWeight.Normal) },
            )
        }

        Box(modifier = Modifier.fillMaxSize().padding(top = 4.dp)) {
            when (tabIndex) {
                0 -> ContentTabPane(instance = pack)
                1 -> FileBrowserPane(rootDir = instanceDir, modifier = Modifier.padding(16.dp))
                2 -> WorldsTabPane(instanceDir = instanceDir)
                3 -> PackLogsTab(packId = pack.id, instanceDir = instanceDir, dataDir = paths.dataDir)
                4 -> PackSettingsTab(
                    runtime = pack.runtime,
                    instanceDir = instanceDir,
                    onRuntimeChange = { rt ->
                        val updated = pack.copy(runtime = rt)
                        instance = updated
                        scope.launch { repo.put(updated) }
                    },
                )
            }
        }
    }
}

/**
 * Logs tab body: a "General" live console plus a picker over every log
 * file relevant to this pack.
 *
 * - "General" -> the launcher's live console buffer (the same one the
 *   standalone window tails). Default selection.
 * - The instance's own logs (logs/latest.log, dated logs, crash
 *   reports) -- these are the logs that were "already in the pack", and
 *   they are what a user reaches for. Listed by full filename.
 * - The launcher's redacted stdout captures for this pack.
 *
 * Picking any file opens a read-only file-backed view. Files are read
 * off-thread and run through the redactor so an external latest.log is
 * as safe to screenshot as our own capture.
 *
 * ConsoleSettings load through the same manager AppShell + the
 * standalone window use, so font / wrap / gutter / timestamps stay one
 * source of truth across every console surface.
 */
@Composable
private fun PackLogsTab(packId: String, instanceDir: Path, dataDir: Path) {
    val gameConsole: GameConsoleService = koinInject()
    val consoleJson = remember { Json { ignoreUnknownKeys = true; encodeDefaults = true } }
    val consoleSettingsManager = remember { ConsoleSettingsManager(dataDir, consoleJson) }
    var consoleSettings by remember { mutableStateOf(consoleSettingsManager.load()) }

    // Re-list when a session starts: a new captured file appears and the
    // instance's latest.log gets rewritten. Keyed on the monotonic
    // session counter, NOT the pack id -- relaunching the SAME pack
    // keeps the id but must still refresh the list.
    val sessionEpoch = gameConsole.sessionStartCount
    val logFiles = remember(packId, instanceDir, sessionEpoch) {
        // Instance's own logs first (latest.log pinned to the top), then
        // the launcher's redacted captures for this pack.
        listInstanceLogs(instanceDir) + gameConsole.capturedSessionFiles(packId)
    }

    // null = the "General" live launcher console; a File = a read-only view.
    var selectedFile by remember(packId) { mutableStateOf<File?>(null) }

    val fileEntries by produceState<List<LogEntry>?>(null, selectedFile) {
        val f = selectedFile
        value = if (f == null) null
                else withContext(Dispatchers.IO) { gameConsole.readLogFile(f) }
    }

    val source: ConsoleSource? = when {
        selectedFile == null -> ConsoleSource.Live
        else                 -> fileEntries?.let { ConsoleSource.FileBacked(it) }  // null while loading
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        // Glass tint, not solid: a solid fill broke the app's translucent
        // aesthetic and left a hard seam against the right panel. The
        // wallpaper stays softly visible while the tint keeps dense
        // monospace readable.
        color    = glassSurfaceAlpha(0.85f),
    ) {
        Column(Modifier.fillMaxSize()) {
            LogSessionPicker(
                files           = logFiles,
                selectedFile    = selectedFile,
                onSelectGeneral = { selectedFile = null },
                onSelectFile    = { selectedFile = it },
            )
            HorizontalDivider(color = CelestiaTheme.colors.outline.copy(alpha = 0.3f))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (source == null) {
                    // A file is selected but still reading -- show the spinner
                    // rather than a blank pane (a slow disk makes this visible).
                    CenteredProgress(Modifier.fillMaxSize())
                } else {
                    ConsoleContent(
                        settings = consoleSettings,
                        onSettingsChange = { new ->
                            consoleSettings = new
                            consoleSettingsManager.save(new)
                        },
                        source = source,
                    )
                }
            }
        }
    }
}

/**
 * Settings tab: per-instance runtime. RAM only for now -- Auto (the machine-aware
 * Automatic baseline, refined by the adaptive sizer) vs a pinned value. Persists each
 * change through [onRuntimeChange]; the Auto chip shows what the next launch will use.
 */
@Composable
private fun PackSettingsTab(
    runtime: InstanceRuntime,
    instanceDir: Path,
    onRuntimeChange: (InstanceRuntime) -> Unit,
) {
    val profilerStore: ProfilerProfileStore = koinInject()
    val settingsService: ISettingsService = koinInject()

    // Keyed on the stable instanceDir, not runtime: the tab's own edits mutate runtime,
    // and re-seeding on those would fight an in-progress edit. Reset only when the
    // displayed instance changes (which also remounts the screen via the nav Crossfade).
    var isAutoMode by remember(instanceDir) { mutableStateOf(!runtime.fixedMemory) }
    var memory by remember(instanceDir) { mutableStateOf(if (runtime.memoryMb > 0) runtime.memoryMb else 4096) }
    var resolvedAutoMb by remember { mutableStateOf(AutomaticHeap.compute(SystemMemory.totalPhysicalMb())) }

    LaunchedEffect(instanceDir) {
        val settings = settingsService.getSettings()
        val adaptiveOn = settings.experimentalFeaturesEnabled && settings.adaptiveMemoryEnabled
        val derivedMb = if (adaptiveOn) withContext(Dispatchers.IO) { profilerStore.readProfile(instanceDir)?.derivedHeapMb } else null
        resolvedAutoMb = derivedMb ?: AutomaticHeap.compute(SystemMemory.totalPhysicalMb())
    }

    Surface(modifier = Modifier.fillMaxSize(), color = glassSurfaceAlpha(0.85f)) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            RamSelector(
                isAuto = isAutoMode,
                resolvedAutoMb = resolvedAutoMb,
                currentMb = memory,
                onAutoSelected = {
                    isAutoMode = true
                    onRuntimeChange(runtime.copy(fixedMemory = false))
                },
                onValueChanged = {
                    memory = it
                    isAutoMode = false
                    onRuntimeChange(runtime.copy(memoryMb = it, fixedMemory = true))
                },
                modifier = Modifier.widthIn(max = 560.dp),
            )
        }
    }
}

// List the instance's own log files, latest.log pinned first, then by
// recency: the logs dir's .log files plus crash-reports .txt / .log.
// These are the logs the game itself wrote -- the ones a user expects
// to find here.
private fun listInstanceLogs(instanceDir: Path): List<File> {
    val out = mutableListOf<File>()
    runCatching {
        instanceDir.resolve("logs").toFile()
            .listFiles { f -> f.isFile && f.name.endsWith(".log") }
            ?.let { out.addAll(it) }
    }
    runCatching {
        instanceDir.resolve("crash-reports").toFile()
            .listFiles { f -> f.isFile && (f.name.endsWith(".txt") || f.name.endsWith(".log")) }
            ?.let { out.addAll(it) }
    }
    return out.sortedWith(
        compareByDescending<File> { it.name == "latest.log" }
            .thenByDescending { it.lastModified() },
    )
}

/**
 * Compact log selector for the Logs tab. The collapsed button shows the
 * current selection ("General" or a full filename); the dropdown lists
 * General + every file by full name, newest first, with the active
 * entry tinted in the accent colour (no ambiguous asterisk).
 */
@Composable
private fun LogSessionPicker(
    files: List<File>,
    selectedFile: File?,
    onSelectGeneral: () -> Unit,
    onSelectFile: (File) -> Unit,
) {
    val s = LocalStrings.current
    val colors = CelestiaTheme.colors
    var open by remember { mutableStateOf(false) }

    val currentLabel = selectedFile?.name ?: s.consoleSessionLive

    Box(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(
            modifier = Modifier
                .clip(MaterialTheme.shapes.small)
                .clickable { open = true }
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text       = s.consoleSessionPickerLabel(currentLabel),
                color      = colors.textSecondary,
                fontSize   = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
            Icon(
                imageVector        = Icons.Default.ArrowDropDown,
                contentDescription = null,
                tint               = colors.textSecondary,
                modifier           = Modifier.size(16.dp),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text    = {
                    Text(
                        s.consoleSessionLive,
                        fontFamily = FontFamily.Monospace,
                        fontSize   = 11.sp,
                        color      = if (selectedFile == null) colors.primary else colors.textPrimary,
                    )
                },
                onClick = { onSelectGeneral(); open = false },
            )
            files.forEach { f ->
                DropdownMenuItem(
                    text    = {
                        Text(
                            f.name,
                            fontFamily = FontFamily.Monospace,
                            fontSize   = 11.sp,
                            color      = if (f == selectedFile) colors.primary else colors.textPrimary,
                        )
                    },
                    onClick = { onSelectFile(f); open = false },
                )
            }
        }
    }
}

@Composable
private fun Hero(pack: PackInstance, onBack: () -> Unit) {
    val bg = originGradient(pack.packRef.origin)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .background(bg),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))

        IconButton(
            onClick  = onBack,
            modifier = Modifier.padding(12.dp),
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = Color.White)
        }

        Column(
            modifier              = Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 20.dp),
            verticalArrangement   = Arrangement.Bottom,
        ) {
            Text(
                text       = pack.displayName,
                style      = MaterialTheme.typography.headlineMedium,
                color      = Color.White,
                fontWeight = FontWeight.Bold,
            )
            pack.forkedFrom?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    text  = "Fork: ${it.origin.name} / ${it.id}" + (it.version?.let { v -> " @ $v" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.85f),
                )
            }
        }
    }
}

@Composable
private fun MetaRow(pack: PackInstance) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment     = Alignment.CenterVertically,
    ) {
        PackMetaChip(pack.packRef.origin.name)
        PackMetaChip(pack.packRef.version ?: "—")
        PackMetaChip(pack.instanceDirName, emphasis = false)
    }
}

@Composable
private fun PlayBar(
    pack: PackInstance,
    enabled: Boolean,
    onPlay: () -> Unit,
) {
    val s = LocalStrings.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(glassSurfaceAlpha(0.7f))
            .padding(16.dp),
    ) {
        Row(
            modifier              = Modifier.fillMaxWidth(),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(
                    text       = if (enabled) s.packDetailReadyTitle else s.packDetailPlayLoginRequired,
                    style      = MaterialTheme.typography.titleMedium,
                    color      = CelestiaTheme.colors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text  = s.packDetailInstanceDirHint(pack.instanceDirName),
                    style = MaterialTheme.typography.bodySmall,
                    color = CelestiaTheme.colors.textSecondary,
                )
            }
            Button(
                onClick        = onPlay,
                enabled        = enabled,
                shape          = MaterialTheme.shapes.small,
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                colors         = ButtonDefaults.buttonColors(
                    containerColor = CelestiaTheme.colors.primary,
                    contentColor   = Color.White,
                ),
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(22.dp))
                Spacer(Modifier.size(8.dp))
                Text(s.packDetailPlay, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun NotFound(onBack: () -> Unit) {
    val s = LocalStrings.current
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(s.packDetailNotFoundTitle, style = MaterialTheme.typography.titleLarge, color = CelestiaTheme.colors.textPrimary)
            Text(s.packDetailNotFoundHint, style = MaterialTheme.typography.bodyMedium, color = CelestiaTheme.colors.textSecondary)
            Button(onClick = onBack) { Text(s.packDetailNotFoundBack) }
        }
    }
}

private fun originGradient(origin: PackOrigin): Brush {
    val pair = when (origin) {
        PackOrigin.Smartycraft -> Color(0xFF4C1D95) to Color(0xFF6D28D9)
        PackOrigin.Mirror      -> Color(0xFF1E3A8A) to Color(0xFF1D4ED8)
        PackOrigin.Modrinth    -> Color(0xFF14532D) to Color(0xFF15803D)
        PackOrigin.Local       -> Color(0xFF374151) to Color(0xFF4B5563)
        PackOrigin.Unknown     -> Color(0xFF374151) to Color(0xFF4B5563)
    }
    return Brush.linearGradient(listOf(pair.first, pair.second))
}

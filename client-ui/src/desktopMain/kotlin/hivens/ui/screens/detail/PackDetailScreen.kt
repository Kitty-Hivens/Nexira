package hivens.ui.screens.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.SubcomposeAsyncImage
import dev.hivens.skinema.compose.VideoScale
import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.CachedManifestSnapshot
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.core.update.PackUpdateStatus
import hivens.core.update.PackUpdateStatusHub
import hivens.core.update.UpdateDirection
import hivens.launcher.launch.LauncherController
import hivens.launcher.platform.PlatformPaths
import hivens.ui.AppState
import hivens.ui.components.FullscreenVideo
import hivens.ui.components.VideoMedia
import hivens.ui.components.isVideoUrl
import hivens.ui.customization.glassSurfaceAlpha
import hivens.ui.effects.pixelArtBackground
import hivens.ui.i18n.AppStrings
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.IconKey
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.core.launch.LaunchControlMode
import hivens.ui.notifications.IndicationCenter
import hivens.ui.notifications.IndicationCenter.Companion.controlMode
import hivens.ui.notifications.IndicationCenter.LaunchIndication
import hivens.ui.notifications.LaunchTarget
import hivens.ui.notifications.drivers.LaunchDriver
import hivens.ui.nx.CenteredProgress
import hivens.ui.nx.NxButton
import hivens.ui.nx.NxButtonStyle
import hivens.ui.nx.NxCalloutBanner
import hivens.ui.nx.NxCalloutTone
import hivens.ui.nx.NxContextMenu
import hivens.ui.nx.NxMenuItem
import hivens.ui.nx.NxRow
import hivens.ui.nx.PlayButton
import hivens.ui.platform.SystemActions
import hivens.ui.puppet.PuppetClick
import hivens.ui.puppet.PuppetScreen
import hivens.ui.screens.ConsoleContent
import hivens.ui.screens.ConsoleSource
import hivens.ui.screens.detail.settings.PackSettingsWindow
import hivens.ui.screens.library.FileBrowserPane
import hivens.ui.screens.library.content.ContentTabPane
import hivens.ui.screens.library.rememberPackArt
import hivens.ui.screens.library.worlds.WorldsTabPane
import hivens.ui.theme.LocalStyle
import hivens.ui.theme.NxTheme
import hivens.ui.theme.decorativePair
import hivens.ui.theme.origin
import hivens.ui.utils.ConsoleSettingsManager
import hivens.ui.utils.GameConsoleService
import hivens.ui.utils.LogEntry
import java.io.File
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.koin.compose.koinInject

/**
 * Library PackDetail. Hero header + Play bar + tabs (Content / Files /
 * Worlds). Resolves the instance lazily via [IPackRepository] from
 * the [Screen.PackDetail.instanceId] in the navigation entry so the
 * sealed Screen class stays small.
 *
 * Tabs are scoped to a single per-instance dir (`<dataDir>/instances/
 * <instance.instanceDirName>`) for Files / Worlds; the Content tab
 * uses [PackInstance.packRef] to fetch the mirror manifest on each
 * open.
 */
@Composable
fun PackDetailScreen(
    instanceId: String,
    appState: AppState,
    onBack: () -> Unit,
    initialShowSettings: Boolean = false,
    onOpenVersions: (fromSettings: Boolean) -> Unit = {},
) {
    PuppetScreen("PackDetail.$instanceId")
    PuppetClick("packDetail.back") { onBack() }

    val repo: IPackRepository = koinInject()
    val paths: PlatformPaths = koinInject()
    val controller: LauncherController = koinInject()
    val launchDriver: LaunchDriver = koinInject()
    val indications: IndicationCenter = koinInject()
    val updateHub: PackUpdateStatusHub = koinInject()
    val autoUpdateStatuses by updateHub.statuses.collectAsState()
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

    var showSettings by remember(pack.id) { mutableStateOf(initialShowSettings) }
    val authedSession = (appState as? AppState.Authenticated)?.session
    val launchIndication by indications.launchIndication(pack.id).collectAsState()

    // The hero's play/abort are the only way to drive a pack launch, so the control
    // surface has to reach them -- a scenario that cannot start a launch cannot check
    // what a launch does to the instance.
    PuppetClick("packDetail.play", enabled = authedSession != null) {
        authedSession?.let { session ->
            launchDriver.observe(LaunchTarget.Pack(pack))
            controller.launchPackInstance(session, pack)
        }
    }
    PuppetClick("packDetail.abort") { controller.abort() }

    Column(Modifier.fillMaxSize()) {
        Hero(
            pack           = pack,
            playEnabled    = authedSession != null,
            indication     = launchIndication,
            onBack         = onBack,
            onPlay         = {
                authedSession?.let { session ->
                    // Observer first, then launch: the first-non-Idle await must
                    // subscribe before Prepare fires.
                    launchDriver.observe(LaunchTarget.Pack(pack))
                    controller.launchPackInstance(session, pack)
                }
            },
            onAbort        = { controller.abort() },
            onOpenSettings = { showSettings = true },
            onOpenFolder   = { SystemActions.openFolder(instanceDir.toString()) },
            versionLabel   = if (pack.packRef.origin == PackOrigin.Mirror) (pack.pinnedPackVersion ?: pack.packRef.version) else null,
            pending        = autoUpdateStatuses[pack.id] as? PackUpdateStatus.Pending,
            onOpenVersions = { onOpenVersions(false) },
        )

        // Import/provenance notice (e.g. a CurseForge import whose project/file-id
        // mods need a manual download). It was only ever written to the instance and
        // never shown, so a half-populated import read as an empty success.
        if (pack.notes.isNotBlank()) {
            NxCalloutBanner(
                body     = pack.notes,
                tone     = NxCalloutTone.Info,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
            )
        }

        PackTabBar(selected = tabIndex, onSelect = { tabIndex = it })

        Box(modifier = Modifier.fillMaxSize().padding(top = 4.dp)) {
            when (tabIndex) {
                0 -> ContentTabPane(instance = pack)
                1 -> FileBrowserPane(rootDir = instanceDir, modifier = Modifier.padding(16.dp))
                2 -> WorldsTabPane(instanceDir = instanceDir)
                3 -> PackLogsTab(packId = pack.id, instanceDir = instanceDir, dataDir = paths.dataDir)
            }
        }
    }

    if (showSettings) {
        PackSettingsWindow(
            pack             = pack,
            instanceDir      = instanceDir,
            onInstanceChange = { instance = it },
            onDismiss        = { showSettings = false },
            onOpenVersions   = { onOpenVersions(true) },
        )
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
        // Cleared before the read: the previous file's lines would otherwise stay on
        // screen under the new file's name for as long as the new one takes to load.
        value = null
        val f = selectedFile
        value = if (f == null) null
                else withContext(Dispatchers.IO) { gameConsole.readLogFile(f) }
    }

    val source: ConsoleSource? = when {
        selectedFile == null -> ConsoleSource.Live
        else                 -> fileEntries?.let { ConsoleSource.FileBacked(it) }  // null while loading
    }

    Surface(
        // Floated card, same treatment as the hero above: full-bleed square
        // edges read as a foreign element next to the rounded cards the rest
        // of the screen is built from.
        modifier = Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        shape    = RoundedCornerShape(LocalStyle.current.cardCorner),
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
            HorizontalDivider(color = NxTheme.colors.outline.copy(alpha = 0.3f))
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
    val colors = NxTheme.colors
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
                text     = s.consoleSessionPickerLabel(currentLabel),
                color    = colors.textSecondary,
                fontSize = 11.sp,
            )
            Symbol(icon = NxIcon.ArrowDropDown,
                contentDescription = null,
                tint               = colors.textSecondary,
                modifier           = Modifier.size(16.dp),
            )
        }
        NxContextMenu(expanded = open, onDismissRequest = { open = false }) {
            NxMenuItem(
                label    = s.consoleSessionLive,
                selected = selectedFile == null,
                onClick  = { onSelectGeneral(); open = false },
            )
            files.forEach { f ->
                NxMenuItem(
                    label    = f.name,
                    selected = f == selectedFile,
                    onClick  = { onSelectFile(f); open = false },
                )
            }
        }
    }
}

@Composable
private fun Hero(
    pack: PackInstance,
    playEnabled: Boolean,
    indication: LaunchIndication?,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onAbort: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenFolder: () -> Unit,
    versionLabel: String?,
    pending: PackUpdateStatus.Pending?,
    onOpenVersions: () -> Unit,
) {
    val s = LocalStrings.current
    val art = rememberPackArt(pack)
    val bannerUrl = art.bannerUrl
    val bannerIsVideo = bannerUrl != null && isVideoUrl(bannerUrl)
    var bannerFullscreen by remember(bannerUrl) { mutableStateOf(false) }
    val (hueA, hueB) = NxTheme.colors.decorativePair(pack.id)
    // Floated card treatment: the app's cards round via the cardCorner token,
    // and a full-bleed square banner read as a foreign element next to them.
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 8.dp, end = 16.dp)
            .height(196.dp)
            .clip(RoundedCornerShape(LocalStyle.current.cardCorner)),
    ) {
        // Pixel-art base -> real banner -> scrim, same layering as the cards.
        Box(Modifier.fillMaxSize().pixelArtBackground(pack.id, hueA, hueB))
        if (bannerUrl != null) {
            if (bannerIsVideo) {
                VideoMedia(
                    url          = bannerUrl,
                    modifier     = Modifier.fillMaxSize(),
                    autoPlay     = true,
                    loop         = true,
                    audio        = false,
                    startMuted   = true,
                    showControls = false,
                    scale        = VideoScale.Cover,
                )
            } else {
                AsyncImage(
                    model              = bannerUrl,
                    contentDescription = null,
                    contentScale       = ContentScale.Crop,
                    modifier           = Modifier.fillMaxSize(),
                )
            }
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (bannerUrl != null) 0.5f else 0.4f)))

        // Top row: folder + settings (end). Back lives in the top-bar breadcrumb
        // now, so the hero no longer draws its own arrow.
        Row(
            modifier              = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment     = Alignment.CenterVertically,
        ) {
            Row {
                if (bannerIsVideo) HeroAction(NxIcon.OpenInFull, s.videoFullscreen) { bannerFullscreen = true }
                HeroAction(NxIcon.FolderOpen, null, onOpenFolder)
                HeroAction(NxIcon.Settings, s.packCardSettings, onOpenSettings)
            }
        }

        // Bottom row: avatar + name + chips, Play pinned to the end. Sheds the
        // hours, then the source chip, and collapses Play to an icon as the hero
        // narrows (right panel open / small window).
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val showPlaytime = maxWidth >= 560.dp
            val showSource   = maxWidth >= 460.dp
            val playIconOnly = maxWidth < 500.dp
            Row(
                modifier              = Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 52.dp, bottom = 20.dp),
                verticalAlignment     = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                HeroAvatar(iconUrl = art.iconUrl, displayName = pack.displayName, hue = hueA)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text       = pack.displayName,
                        style      = MaterialTheme.typography.headlineMedium,
                        color      = Color.White,
                        fontWeight = FontWeight.Bold,
                        maxLines   = 2,
                        overflow   = TextOverflow.Ellipsis,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (showSource) SourceChip(pack.packRef.origin)
                        pack.cachedManifest?.let { HeroChip(loaderMcLabel(it)) }
                        versionLabel?.let { HeroChip("v$it") }
                        pending?.let { p ->
                            val rollback = p.direction == UpdateDirection.Older
                            HeroUpdateBadge(
                                text     = if (rollback) s.packVersionRollbackBadge else s.packVersionUpdateBadge,
                                rollback = rollback,
                                onClick  = onOpenVersions,
                            )
                        }
                        if (showPlaytime && pack.playtimeSeconds > 0L) HeroChip(playtimeLabel(pack.playtimeSeconds))
                        HeroChip(lastPlayedShort(pack.lastPlayedEpochOrZero, s))
                    }
                }
                // The pill walks the launch: Play -> wait (prepare/sync, inert)
                // -> Exit (stop the running game) -> Play again. Failed falls
                // back to Play -- the error toast carries the diagnosis.
                val mode = indication.controlMode()
                PlayButton(
                    label    = when (mode) {
                        LaunchControlMode.Stop -> s.packPlayExit
                        LaunchControlMode.Wait -> s.packPlayWait
                        LaunchControlMode.Play -> s.packDetailPlay
                    },
                    icon     = if (mode == LaunchControlMode.Stop) NxIcon.Stop else NxIcon.PlayArrow,
                    busy     = mode == LaunchControlMode.Wait,
                    onClick  = if (mode == LaunchControlMode.Stop) onAbort else onPlay,
                    enabled  = if (mode == LaunchControlMode.Stop) true else playEnabled,
                    iconOnly = playIconOnly,
                )
            }
        }
    }
    if (bannerFullscreen && bannerUrl != null) {
        FullscreenVideo(url = bannerUrl, onDismiss = { bannerFullscreen = false })
    }
}

@Composable
private fun HeroAction(icon: IconKey, contentDescription: String?, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Symbol(icon, contentDescription = contentDescription, tint = Color.White)
    }
}

/**
 * Compact pill tab strip, left-aligned -- Material's PrimaryTabRow read too big
 * and stretched. FlowRow (not Row) so a narrow content area (the right panel
 * eats width) wraps the trailing pills to a second line instead of clipping
 * Worlds / Logs off the right edge.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PackTabBar(selected: Int, onSelect: (Int) -> Unit) {
    val s = LocalStrings.current
    val tabs = listOf(
        NxIcon.Widgets to s.packDetailTabContent,
        NxIcon.FolderOpen to s.packDetailTabFiles,
        NxIcon.Public to s.packDetailTabWorlds,
        NxIcon.Description to s.packDetailTabLogs,
    )
    FlowRow(
        modifier              = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement   = Arrangement.spacedBy(6.dp),
    ) {
        tabs.forEachIndexed { i, (icon, label) ->
            val active = i == selected
            val tint = if (active) Color.White else NxTheme.colors.textSecondary
            Row(
                modifier = Modifier
                    .clip(MaterialTheme.shapes.small)
                    .background(if (active) NxTheme.colors.primary else glassSurfaceAlpha(0.5f))
                    .clickable { onSelect(i) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Symbol(icon, contentDescription = null, tint = tint, size = 16.dp)
                Text(
                    text       = label,
                    style      = MaterialTheme.typography.labelLarge,
                    color      = tint,
                    maxLines   = 1,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun SourceChip(origin: PackOrigin) {
    val label = when (origin) {
        PackOrigin.Mirror      -> "Mirror"
        PackOrigin.Modrinth    -> "Modrinth"
        PackOrigin.Smartycraft -> "SmartyCraft"
        PackOrigin.Local       -> "Local"
        PackOrigin.Unknown     -> "Other"
    }
    Box(
        modifier = Modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .background(NxTheme.colors.origin(origin).copy(alpha = 0.9f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun HeroChip(text: String) {
    Box(
        modifier = Modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .background(Color.Black.copy(alpha = 0.4f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.9f))
    }
}

@Composable
private fun HeroUpdateBadge(text: String, rollback: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .background(if (rollback) NxTheme.colors.warnAccent else NxTheme.colors.primary)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = Color.White, fontWeight = FontWeight.Bold)
    }
}

private fun loaderMcLabel(m: CachedManifestSnapshot): String {
    val loader = m.loaderName
        .takeIf { it.isNotBlank() && !it.equals("vanilla", ignoreCase = true) }
        ?.replaceFirstChar(Char::uppercase)
    return listOfNotNull(loader, m.minecraftVersion).joinToString(" ")
}

private fun playtimeLabel(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return if (hours > 0) "${hours}h" else "${minutes}m"
}

private fun lastPlayedShort(epoch: Long, s: AppStrings): String {
    if (epoch <= 0L) return s.packCardNeverPlayed
    val dur = Duration.between(Instant.ofEpochSecond(epoch), Instant.now())
    return when {
        dur.toMinutes() < 1  -> s.packCardPlayedJustNow
        dur.toHours()   < 1  -> s.packCardPlayedMinutesAgo(dur.toMinutes())
        dur.toDays()    < 1  -> s.packCardPlayedHoursAgo(dur.toHours())
        dur.toDays()    < 14 -> s.packCardPlayedDaysAgo(dur.toDays())
        else                 -> s.packCardPlayedLongAgo
    }
}

@Composable
private fun HeroAvatar(iconUrl: String?, displayName: String, hue: Color) {
    val initials = displayName
        .split(' ', '-', '_')
        .filter { it.isNotBlank() }
        .take(2)
        .joinToString("") { it.first().uppercaseChar().toString() }
        .ifEmpty { "?" }
    SubcomposeAsyncImage(
        model              = iconUrl,
        contentDescription = null,
        contentScale       = ContentScale.Crop,
        modifier           = Modifier.size(72.dp).clip(RoundedCornerShape(14.dp)),
        loading            = { Box(Modifier.fillMaxSize().background(hue)) },
        error              = {
            Box(Modifier.fillMaxSize().background(hue), contentAlignment = Alignment.Center) {
                Text(initials, color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
        },
    )
}

@Composable
private fun NotFound(onBack: () -> Unit) {
    val s = LocalStrings.current
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(s.packDetailNotFoundTitle, style = MaterialTheme.typography.titleLarge, color = NxTheme.colors.textPrimary)
            Text(s.packDetailNotFoundHint, style = MaterialTheme.typography.bodyMedium, color = NxTheme.colors.textSecondary)
            NxButton(label = s.packDetailNotFoundBack, onClick = onBack)
        }
    }
}

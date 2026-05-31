package hivens.ui.screens.detail

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.launcher.launch.LauncherController
import hivens.launcher.platform.PlatformPaths
import hivens.ui.AppState
import hivens.ui.customization.glassSurfaceAlpha
import hivens.ui.notifications.LaunchTarget
import hivens.ui.notifications.drivers.LaunchDriver
import hivens.ui.i18n.LocalStrings
import hivens.ui.puppet.PuppetClick
import hivens.ui.puppet.PuppetScreen
import hivens.ui.screens.ConsoleContent
import hivens.ui.screens.library.FileBrowserPane
import hivens.ui.screens.library.PackMetaChip
import hivens.ui.screens.library.content.ContentTabPane
import hivens.ui.screens.library.worlds.WorldsTabPane
import hivens.ui.theme.CelestiaTheme
import hivens.ui.utils.ConsoleSettingsManager
import kotlinx.coroutines.flow.firstOrNull
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
        Box(Modifier.fillMaxSize())
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
        }

        // Logs tab loads its own ConsoleSettings via the same manager
        // pattern AppShell uses, so the on-disk console.json stays the
        // single source of truth for font / wrap / gutter / timestamps
        // / max-in-memory-lines whether the user opens the tab here or
        // the standalone ConsoleWindow.
        val consoleJson = remember { Json { ignoreUnknownKeys = true; encodeDefaults = true } }
        val consoleSettingsManager = remember { ConsoleSettingsManager(paths.dataDir, consoleJson) }
        var consoleSettings by remember { mutableStateOf(consoleSettingsManager.load()) }

        Box(modifier = Modifier.fillMaxSize().padding(top = 4.dp)) {
            when (tabIndex) {
                0 -> ContentTabPane(instance = pack)
                1 -> FileBrowserPane(rootDir = instanceDir, modifier = Modifier.padding(16.dp))
                2 -> WorldsTabPane(instanceDir = instanceDir)
                3 -> ConsoleContent(
                    settings = consoleSettings,
                    onSettingsChange = { new ->
                        consoleSettings = new
                        consoleSettingsManager.save(new)
                    },
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
            .clip(RoundedCornerShape(14.dp))
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
                shape          = RoundedCornerShape(12.dp),
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
    }
    return Brush.linearGradient(listOf(pair.first, pair.second))
}

package hivens.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.core.api.interfaces.ISettingsService
import hivens.core.api.interfaces.IUpdateApplicator
import hivens.core.data.ReleaseChannel
import hivens.core.data.ReleaseEntry
import hivens.launcher.update.DesktopIntegration
import hivens.launcher.update.SourceBuildService
import hivens.launcher.update.UpdateService
import hivens.ui.i18n.LocalStrings
import hivens.ui.puppet.PuppetClick
import hivens.ui.puppet.PuppetScreen
import hivens.ui.theme.CelestiaTheme
import hivens.ui.theme.LocalStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

/**
 * Update manager modal: pick a release channel, see the available versions,
 * update or roll back to any, build from source (Dev/Git), and install the
 * .desktop entry. Opened from the "i" next to the current version in
 * AboutUpdatePanelWidget. Reuses UpdateService (list/prepare/download), the
 * per-OS applicator, SourceBuildService, and DesktopIntegration.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateManagerDialog(onDismiss: () -> Unit) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    val updateService: UpdateService = koinInject()
    val applicator: IUpdateApplicator = koinInject()
    val sourceBuild: SourceBuildService = koinInject()
    val desktop: DesktopIntegration = koinInject()
    val settingsService: ISettingsService = koinInject()
    val logger = remember { LoggerFactory.getLogger("UpdateManager") }

    val experimentalOn = remember { settingsService.getSettings().experimentalFeaturesEnabled }
    val toolchain = remember { sourceBuild.detectToolchain() }
    val toolchainReady = remember { sourceBuild.isSupported() && toolchain.ready }
    val missingTools = remember { toolchain.missing.joinToString(", ") }

    var channel by remember { mutableStateOf(settingsService.getSettings().updateChannel) }
    var allReleases by remember { mutableStateOf<List<ReleaseEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var progressText by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var desktopDone by remember { mutableStateOf(false) }
    val buildLog = remember { mutableStateListOf<String>() }

    // One request, cached: fetch everything releasable (<= Alpha) once, then
    // filter by channel locally. Switching channels must not re-hit GitHub --
    // its unauthenticated API is rate-limited.
    LaunchedEffect(Unit) {
        loading = true
        allReleases = updateService.listReleases(ReleaseChannel.Alpha)
        loading = false
    }

    fun selectChannel(c: ReleaseChannel) {
        if (busy) return
        channel = c
        settingsService.saveSettings(settingsService.getSettings().copy(updateChannel = c))
    }

    fun install(version: String) {
        if (busy) return
        busy = true; error = null; progressText = s.updateDownloading
        scope.launch {
            try {
                val upd = updateService.prepareUpdate(version) ?: throw IllegalStateException(s.updateErrorUnknown)
                val path = updateService.downloadUpdate(upd) { dl, total, _ ->
                    val pct = if (total > 0) (dl * 100 / total) else 0
                    progressText = "${s.updateDownloading} $pct%"
                }
                applicator.scheduleUpdate(path)
                exitProcess(0)
            } catch (e: Exception) {
                logger.warn("manager install failed", e)
                error = e.message ?: s.updateErrorUnknown
                busy = false; progressText = null
            }
        }
    }

    fun buildFromSource() {
        if (busy) return
        busy = true; error = null; buildLog.clear(); progressText = s.updateManagerBuilding
        scope.launch {
            // buildAndApply emits progress from its IO worker, but buildLog is
            // snapshot state the composition reads on the UI thread -- funnel
            // every event through a channel drained here (scope = Main).
            val progress = Channel<SourceBuildService.Progress>(Channel.UNLIMITED)
            val drainer = launch {
                for (p in progress) when (p) {
                    is SourceBuildService.Progress.Phase -> progressText = p.message
                    is SourceBuildService.Progress.Line -> {
                        buildLog.add(p.text)
                        if (buildLog.size > 200) buildLog.removeAt(0)
                    }
                }
            }
            val result = sourceBuild.buildAndApply(channel) { progress.trySend(it) }
            progress.close()
            drainer.join()
            result
                .onSuccess { exitProcess(0) }
                .onFailure {
                    error = it.message ?: s.updateErrorUnknown
                    busy = false; progressText = null
                }
        }
    }

    fun installDesktop() {
        scope.launch {
            withContext(Dispatchers.IO) { desktop.installEntry() }
                .onSuccess { desktopDone = true }
                .onFailure { error = it.message ?: s.updateErrorUnknown }
        }
    }

    PuppetScreen("UpdateManager")
    PuppetClick("updateManager.dismiss", enabled = !busy) { onDismiss() }

    BasicAlertDialog(onDismissRequest = { if (!busy) onDismiss() }) {
        GlassCard(
            // A modal stays readable regardless of the glass-intensity knob, so
            // this is a fixed near-opaque dark panel, not glassIntensity-scaled
            // (BasicAlertDialog draws no scrim behind it).
            modifier = Modifier.width(560.dp).wrapContentHeight(),
            backgroundColor = CelestiaTheme.colors.background.copy(alpha = 0.97f),
        ) {
            Column(Modifier.padding(24.dp)) {
                // ── Header ───────────────────────────────────────────────────────
                Text(
                    s.updateManagerTitle,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = CelestiaTheme.colors.textPrimary,
                )

                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = CelestiaTheme.colors.textSecondary.copy(alpha = 0.2f))
                Spacer(Modifier.height(16.dp))

                // ── Channel picker (chips sit to the right of the label) ─────────
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.updateManagerChannel, style = MaterialTheme.typography.labelMedium, color = CelestiaTheme.colors.textSecondary)
                    Spacer(Modifier.width(8.dp))
                    FlowRow(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        // Git sits left of Dev (dev is the more bleeding-edge one --
                        // it also pulls the dev branch).
                        listOf(
                            ReleaseChannel.Release, ReleaseChannel.Beta, ReleaseChannel.Alpha,
                            ReleaseChannel.Git, ReleaseChannel.Dev,
                        ).forEach { c ->
                            val locked = c.isSourceBuild && !(experimentalOn && toolchainReady)
                            ChannelChip(channel = c, selected = c == channel, enabled = !busy && !locked, onClick = { selectChannel(c) })
                        }
                    }
                }

                if (channel.isSourceBuild) {
                    Spacer(Modifier.height(8.dp))
                    val hint = when {
                        !experimentalOn -> s.updateManagerNeedsExperimental
                        !toolchainReady -> s.updateManagerNeedsTools(missingTools)
                        else -> null
                    }
                    if (hint != null) {
                        Text(hint, style = MaterialTheme.typography.bodySmall, color = CelestiaTheme.colors.warnAccent)
                    }
                }

                Spacer(Modifier.height(16.dp))

                // ── Body: version list, or build action for source channels ──────
                // Filtered locally from the single cached fetch (cumulative).
                val releases = allReleases.filter { it.channel.ordinal <= channel.ordinal }
                if (channel.isSourceBuild) {
                    Button(
                        onClick = { buildFromSource() },
                        enabled = !busy && experimentalOn && toolchainReady,
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small,
                        colors = ButtonDefaults.buttonColors(containerColor = CelestiaTheme.colors.primary),
                    ) {
                        Text(s.updateManagerBuild, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                    if (buildLog.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Box(
                            Modifier.fillMaxWidth().heightIn(max = 180.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(CelestiaTheme.colors.background.copy(alpha = 0.4f))
                                .padding(8.dp),
                        ) {
                            Column(Modifier.verticalScroll(rememberScrollState())) {
                                buildLog.takeLast(60).forEach {
                                    Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = CelestiaTheme.colors.textSecondary)
                                }
                            }
                        }
                    }
                } else if (loading) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = CelestiaTheme.colors.primary)
                        Spacer(Modifier.width(12.dp))
                        Text(s.aboutChecking, color = CelestiaTheme.colors.textSecondary)
                    }
                } else if (releases.isEmpty()) {
                    Text(s.updateManagerEmpty, color = CelestiaTheme.colors.textSecondary)
                } else {
                    val currentIdx = releases.indexOfFirst { it.isCurrent }
                    // Tighten the gap as the list grows so more versions stay
                    // visible before the 300dp cap starts scrolling.
                    val rowGap = if (releases.size > 6) 3.dp else 6.dp
                    Column(
                        Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(rowGap),
                    ) {
                        releases.forEachIndexed { idx, entry ->
                            VersionRow(
                                entry = entry,
                                isOlder = currentIdx in 0 until idx,
                                enabled = !busy && entry.installable && !entry.isCurrent,
                                onAction = { install(entry.version) },
                            )
                        }
                    }
                }

                progressText?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = CelestiaTheme.colors.primary)
                }
                error?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = CelestiaTheme.colors.error)
                }

                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = CelestiaTheme.colors.textSecondary.copy(alpha = 0.2f))
                Spacer(Modifier.height(12.dp))

                // ── Footer: install .desktop (Linux) + close ─────────────────────
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (desktop.isSupported()) {
                        TextButton(onClick = { installDesktop() }, enabled = !busy && !desktopDone) {
                            Text(
                                if (desktopDone) s.updateManagerDesktopDone else s.updateManagerInstallDesktop,
                                color = CelestiaTheme.colors.textSecondary,
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss, enabled = !busy) {
                        Text(s.updateLater, color = CelestiaTheme.colors.textSecondary)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelChip(channel: ReleaseChannel, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val style = LocalStyle.current
    val shape = RoundedCornerShape(style.cardCorner)
    // Dim the unselected channels so the picker reads as "one chosen" rather than
    // a wall of equally-loud chips; disabled ones dim further.
    val alpha = when {
        !enabled  -> 0.4f
        !selected -> 0.55f
        else      -> 1f
    }
    Box(
        Modifier
            .clip(shape)
            .then(if (selected) Modifier.border(1.dp, CelestiaTheme.colors.primary, shape) else Modifier)
            .alpha(alpha)
            .clickable(enabled = enabled) { onClick() }
            .padding(2.dp),
    ) {
        ChannelBadge(channel)
    }
}

@Composable
private fun VersionRow(entry: ReleaseEntry, isOlder: Boolean, enabled: Boolean, onAction: () -> Unit) {
    val s = LocalStrings.current
    val style = LocalStyle.current
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(style.cardCorner))
            .background(CelestiaTheme.colors.background.copy(alpha = 0.3f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (entry.isCurrent) {
            Icon(Icons.Default.CheckCircle, null, tint = CelestiaTheme.colors.success, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(entry.version, style = MaterialTheme.typography.bodyMedium, color = CelestiaTheme.colors.textPrimary)
        Spacer(Modifier.width(8.dp))
        ChannelBadge(entry.channel)
        if (entry.isCurrent) {
            Spacer(Modifier.width(8.dp))
            Text("(${s.updateManagerCurrentTag})", style = MaterialTheme.typography.labelSmall, color = CelestiaTheme.colors.textSecondary)
        }
        Spacer(Modifier.weight(1f))
        if (!entry.isCurrent) {
            TextButton(onClick = onAction, enabled = enabled) {
                Text(
                    if (isOlder) s.updateManagerRollback else s.updateManagerInstall,
                    color = if (isOlder) CelestiaTheme.colors.warnAccent else CelestiaTheme.colors.primary,
                )
            }
        }
    }
}

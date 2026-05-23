package hivens.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown
import hivens.core.api.interfaces.IUpdateApplicator
import hivens.core.data.LauncherUpdate
import hivens.launcher.update.UpdateService
import hivens.ui.i18n.LocalStrings
import hivens.ui.puppet.PuppetClick
import hivens.ui.puppet.PuppetScreen
import hivens.ui.theme.CelestiaTheme
import hivens.ui.platform.SystemActions
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.slf4j.LoggerFactory
import java.nio.file.Paths
import kotlin.math.roundToInt
import kotlin.system.exitProcess

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateDialog(
    update: LauncherUpdate,
    updateService: UpdateService,
    onDismiss: () -> Unit
) {
    val s              = LocalStrings.current
    val logger         = LoggerFactory.getLogger("UpdateDialog")
    val scope          = rememberCoroutineScope()
    val updateApplicator: IUpdateApplicator = koinInject()

    var downloadState by remember { mutableStateOf<DownloadState>(DownloadState.Idle) }
    var errorMessage  by remember { mutableStateOf<String?>(null) }

    // Critical and mandatory both block the backdrop dismiss, but a mandatory
    // update goes further: it also replaces the "Later" button with a hard
    // "Quit" so the user has *some* exit path that isn't "click outside the
    // dialog and pretend it didn't happen". Treat them together as `isBlocking`
    // for everything that's about non-dismissability, and keep `isMandatory`
    // separate for the visual + button changes.
    val isBlocking = update.isCritical || update.isMandatory

    // Both PuppetClick("update.download") and the Idle-state Button onClick
    // run the exact same coroutine flow. Local function captures the state
    // delegates from the enclosing composable so each call site shrinks to
    // a one-liner.
    fun launchDownload() {
        scope.launch {
            downloadState = DownloadState.Downloading(0L, 0L, 0.0)
            errorMessage  = null
            try {
                val path = updateService.downloadUpdate(update) { dl, total, speed ->
                    downloadState = DownloadState.Downloading(dl, total, speed)
                }
                downloadState = DownloadState.Ready(path.toString())
            } catch (e: Exception) {
                logger.error("Download failed", e)
                errorMessage  = e.message ?: s.updateErrorUnknown
                downloadState = DownloadState.Failed
            }
        }
    }

    // Same shape as launchDownload: PuppetClick("update.install") and the
    // Ready-state Button onClick both schedule + exit identically.
    fun installUpdate(installerPath: String) {
        try {
            updateApplicator.scheduleUpdate(Paths.get(installerPath))
            logger.info("Update scheduled, exiting...")
            exitProcess(0)
        } catch (e: Exception) {
            logger.error("Failed to schedule update", e)
            errorMessage  = "${s.updateScheduleFailed}: ${e.message}"
            downloadState = DownloadState.Failed
        }
    }

    // Puppet: dialog action set. Marker screen helps drivers detect the
    // dialog is open; ids map to the buttons rendered below.
    PuppetScreen("UpdateDialog")
    PuppetClick("update.viewOnGithub", enabled = downloadState !is DownloadState.Downloading) {
        SystemActions.openUrl(update.releasePageUrl)
    }
    PuppetClick("update.dismiss", enabled = !isBlocking && downloadState !is DownloadState.Downloading) {
        onDismiss()
    }
    PuppetClick("update.exitForMandatory", enabled = update.isMandatory && downloadState !is DownloadState.Downloading) {
        exitProcess(0)
    }
    PuppetClick("update.download", enabled = downloadState is DownloadState.Idle) {
        launchDownload()
    }
    PuppetClick("update.install", enabled = downloadState is DownloadState.Ready) {
        val path = (downloadState as? DownloadState.Ready)?.installerPath ?: return@PuppetClick
        installUpdate(path)
    }
    PuppetClick("update.retry", enabled = downloadState is DownloadState.Failed) {
        errorMessage = null
        downloadState = DownloadState.Idle
    }

    BasicAlertDialog(
        onDismissRequest = { if (!isBlocking) onDismiss() }
    ) {
        Surface(
            modifier  = Modifier.width(700.dp).wrapContentHeight(),
            shape     = MaterialTheme.shapes.large,
            color     = CelestiaTheme.colors.surface,
            tonalElevation = 8.dp
        ) {
            Column(Modifier.padding(24.dp)) {

                // ── Header ────────────────────────────────────────────────────
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier          = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector        = if (isBlocking) Icons.Default.Warning else Icons.Default.CloudDownload,
                        contentDescription = null,
                        tint               = if (isBlocking) CelestiaTheme.colors.error else CelestiaTheme.colors.primary,
                        modifier           = Modifier.size(32.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            text       = when {
                                update.isMandatory -> s.updateTitleMandatory
                                update.isCritical  -> s.updateTitleCritical
                                else               -> s.updateTitle
                            },
                            style      = MaterialTheme.typography.titleLarge,
                            color      = if (isBlocking) CelestiaTheme.colors.error else CelestiaTheme.colors.textPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text  = update.version,
                            style = MaterialTheme.typography.bodySmall,
                            color = CelestiaTheme.colors.textSecondary
                        )
                    }
                }

                // ── Critical / Mandatory banner ──────────────────────────────
                if (isBlocking) {
                    Spacer(Modifier.height(16.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(CelestiaTheme.colors.error.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                            .padding(12.dp)
                    ) {
                        val bannerText = when {
                            update.isMandatory -> {
                                val reason = update.mandatoryReason
                                if (!reason.isNullOrBlank()) s.updateMandatoryBannerWithReason(reason)
                                else s.updateMandatoryBanner
                            }
                            else -> s.updateCriticalBanner
                        }
                        Text(
                            bannerText,
                            style      = MaterialTheme.typography.bodyMedium,
                            color      = CelestiaTheme.colors.error,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = CelestiaTheme.colors.textSecondary.copy(alpha = 0.2f))
                Spacer(Modifier.height(16.dp))

                // ── Highlights or full changelog ──────────────────────────────
                val hasHighlights = !update.highlights.isNullOrBlank()
                val bodyContent = update.highlights?.takeIf { it.isNotBlank() } ?: update.changelog

                Text(
                    if (hasHighlights) s.updateHighlights else s.updateChangelog,
                    style      = MaterialTheme.typography.titleSmall,
                    color      = CelestiaTheme.colors.textPrimary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))

                // Highlights are short prose, full changelog can be paragraphs of
                // commit-flavored text. Cap height in both cases -- the dialog is
                // 700dp wide and would dominate the screen otherwise.
                val bodyMaxHeight = if (hasHighlights) 200.dp else 350.dp
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = bodyMaxHeight)
                        .background(CelestiaTheme.colors.background.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                        .padding(12.dp)
                ) {
                    Markdown(
                        content  = bodyContent,
                        modifier = Modifier.verticalScroll(rememberScrollState())
                    )
                }

                Spacer(Modifier.height(16.dp))

                // ── Download progress ─────────────────────────────────────────
                AnimatedVisibility(
                    visible = downloadState is DownloadState.Downloading,
                    enter   = expandVertically() + fadeIn(),
                    exit    = shrinkVertically() + fadeOut()
                ) {
                    if (downloadState is DownloadState.Downloading) {
                        DownloadProgress(downloadState as DownloadState.Downloading)
                    }
                }

                // ── Error ─────────────────────────────────────────────────────
                errorMessage?.let { error ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(CelestiaTheme.colors.error.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                            .padding(12.dp)
                    ) {
                        Column {
                            Text(
                                s.updateErrorTitle,
                                style      = MaterialTheme.typography.titleSmall,
                                color      = CelestiaTheme.colors.error,
                                fontWeight = FontWeight.Bold
                            )
                            Text(error, style = MaterialTheme.typography.bodyMedium, color = CelestiaTheme.colors.error)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }

                // ── Actions ───────────────────────────────────────────────────
                Row(
                    modifier          = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // "View on GitHub" sits left, doesn't compete for attention with the
                    // primary install button on the right. Hidden during download so the
                    // user can't accidentally yank focus mid-progress.
                    if (downloadState !is DownloadState.Downloading) {
                        TextButton(onClick = { SystemActions.openUrl(update.releasePageUrl) }) {
                            Icon(
                                imageVector        = Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = null,
                                tint               = CelestiaTheme.colors.textSecondary,
                                modifier           = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(s.updateViewOnGitHub, color = CelestiaTheme.colors.textSecondary)
                        }
                    }

                    Spacer(Modifier.weight(1f))

                    if (!isBlocking && downloadState !is DownloadState.Downloading) {
                        TextButton(onClick = onDismiss) {
                            Text(s.updateLater, color = CelestiaTheme.colors.textSecondary)
                        }
                        Spacer(Modifier.width(8.dp))
                    }

                    // Mandatory updates need a hard exit path -- without it the
                    // user's only choices are "install now" or "kill the
                    // process from outside". Hidden mid-download to avoid the
                    // user yanking themselves out of an installation in progress.
                    if (update.isMandatory && downloadState !is DownloadState.Downloading) {
                        TextButton(onClick = { exitProcess(0) }) {
                            Text(s.updateExit, color = CelestiaTheme.colors.textSecondary)
                        }
                        Spacer(Modifier.width(8.dp))
                    }

                    when (downloadState) {
                        is DownloadState.Idle -> {
                            Button(
                                onClick = { launchDownload() },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isBlocking) CelestiaTheme.colors.error else CelestiaTheme.colors.primary
                                ),
                                shape  = MaterialTheme.shapes.small
                            ) {
                                Text(
                                    if (isBlocking) s.updateDownloadNow else s.updateDownload,
                                    color      = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        is DownloadState.Downloading -> {
                            Button(
                                onClick  = {},
                                enabled  = false,
                                colors   = ButtonDefaults.buttonColors(
                                    disabledContainerColor = CelestiaTheme.colors.primary.copy(alpha = 0.5f)
                                ),
                                shape = MaterialTheme.shapes.small
                            ) {
                                CircularProgressIndicator(
                                    modifier    = Modifier.size(16.dp),
                                    color       = Color.White,
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(s.updateDownloading, color = Color.White)
                            }
                        }

                        is DownloadState.Ready -> {
                            val path = (downloadState as DownloadState.Ready).installerPath
                            Button(
                                onClick = { installUpdate(path) },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                                shape  = MaterialTheme.shapes.small
                            ) {
                                Text(s.updateInstall, color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }

                        is DownloadState.Failed -> {
                            Button(
                                onClick = { errorMessage = null; downloadState = DownloadState.Idle },
                                colors  = ButtonDefaults.buttonColors(containerColor = CelestiaTheme.colors.primary),
                                shape   = MaterialTheme.shapes.small
                            ) {
                                Text(s.updateRetry, color = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadProgress(state: DownloadState.Downloading) {
    Column {
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val dlMB    = state.downloaded / 1024.0 / 1024.0
            val totalMB = state.total      / 1024.0 / 1024.0
            val speedMB = state.speed      / 1024.0 / 1024.0

            Text(
                text  = if (state.total > 0) "%.1f / %.1f MB".format(dlMB, totalMB)
                else "%.1f MB".format(dlMB),
                style = MaterialTheme.typography.bodySmall,
                color = CelestiaTheme.colors.textSecondary
            )

            if (state.speed > 0) {
                Text(
                    "%.2f MB/s".format(speedMB),
                    style      = MaterialTheme.typography.bodySmall,
                    color      = CelestiaTheme.colors.primary,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        val progress = if (state.total > 0)
            (state.downloaded.toFloat() / state.total).coerceIn(0f, 1f)
        else 0f

        LinearProgressIndicator(
            progress        = { progress },
            modifier        = Modifier.fillMaxWidth().height(8.dp),
            color           = CelestiaTheme.colors.primary,
            trackColor      = CelestiaTheme.colors.surface,
            gapSize         = 0.dp,
            drawStopIndicator = {}
        )

        if (state.total > 0) {
            Spacer(Modifier.height(4.dp))
            Text(
                "${(progress * 100).roundToInt()}%",
                style    = MaterialTheme.typography.bodySmall,
                color    = CelestiaTheme.colors.textSecondary,
                modifier = Modifier.align(Alignment.End)
            )
        }

        Spacer(Modifier.height(16.dp))
    }
}

private sealed class DownloadState {
    object Idle : DownloadState()
    data class Downloading(val downloaded: Long, val total: Long, val speed: Double) : DownloadState()
    data class Ready(val installerPath: String) : DownloadState()
    object Failed : DownloadState()
}

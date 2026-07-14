package hivens.ui.screens.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import hivens.config.Branding
import hivens.launcher.diag.DiagnosticBundle
import hivens.launcher.diag.IssueReporter
import hivens.launcher.platform.AppRelauncher
import hivens.launcher.platform.PlatformPaths
import hivens.ui.bootstrap.RecoveryEntry
import hivens.ui.easter.LocalAprilFools
import hivens.ui.flexible.Flexible
import hivens.ui.flexible.FlexibleKind
import hivens.ui.i18n.LocalStrings
import hivens.ui.nx.NxButton
import hivens.ui.nx.NxButtonStyle
import hivens.ui.nx.NxNavRow
import hivens.ui.nx.NxSection
import hivens.ui.icons.NxIcon
import hivens.ui.platform.SystemActions
import hivens.ui.puppet.PuppetClick
import hivens.ui.theme.NxTheme
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.nio.file.Path
import kotlin.system.exitProcess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/**
 * Beacon diagnostic surface + About link.
 *
 * Diagnostics plane: open logs / crash-reports folders, generate a redacted
 * diagnostic bundle ZIP (lazy-busy gated so a double-tap doesn't fire two parallel
 * writes), and after one exists, open the GitHub issue template prefilled with bundle
 * metadata. Each button is wrapped in AprilFools.ChaosButton -- the chaos layer
 * intercepts during the Apr 1-14 window, otherwise renders as a plain button.
 *
 * Hidden gesture: tap the DIAGNOSTICS section title 5 times to reveal the AprilFools
 * debug panel; tap 5 more to hide it. Lets dev exercise the chaos layer without
 * changing the system date.
 *
 * About link at the bottom: a standalone shortcut to the AboutScreen, self-describing
 * with version + license, so it carries no section header of its own.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DiagnosticsSection(
    paths: PlatformPaths,
    onOpenAbout: () -> Unit,
) {
    val s  = LocalStrings.current
    val af = LocalAprilFools.current

    // April Fools debug panel -- secret unlock. Only wire the 5-tap gesture when the
    // active impl actually renders a panel. Production builds without
    // `-PauraAprilFools=true` ship NoOpAprilFools whose DebugPanel() is empty, so a
    // 5-tap there would toggle a hidden state that renders to nothing.
    var debugTapCount  by remember { mutableStateOf(0) }
    var showAprilDebug by remember { mutableStateOf(false) }
    val debugPanelBringIntoView = remember { BringIntoViewRequester() }
    val titleModifier = if (af.providesDebugPanel) {
        Modifier.clickable {
            debugTapCount++
            if (debugTapCount >= 5) {
                debugTapCount  = 0
                showAprilDebug = !showAprilDebug
            }
        }
    } else {
        Modifier
    }
    LaunchedEffect(showAprilDebug) {
        if (showAprilDebug) {
            // Wait one frame so the panel is laid out before the scroll request;
            // bringIntoView() against a not-yet-positioned target is a no-op.
            yield()
            runCatching { debugPanelBringIntoView.bringIntoView() }
        }
    }

    // Beacon: one-click ZIP for support -- bundles redacted logs, crash reports, action
    // ring and system info. Generation runs off the Compose UI thread (a 200 MB log cap
    // is enough to freeze Settings for a beat); while busy the button is disabled so a
    // double click doesn't fire two parallel writes. The GitHub-Issue button enables
    // only after a bundle exists this session.
    var lastBundlePath by remember { mutableStateOf<Path?>(null) }
    var bundleBusy     by remember { mutableStateOf(false) }
    val bundleScope    = rememberCoroutineScope()

    NxSection(s.settingsSectionDiagnostics, titleModifier = titleModifier) {
        if (af.providesDebugPanel && showAprilDebug) {
            Box(Modifier.bringIntoViewRequester(debugPanelBringIntoView)) {
                af.DebugPanel()
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.weight(1f)) {
                Flexible("settings_open_logs_btn", FlexibleKind.Button) {
                    NxButton(
                        label    = s.settingsOpenLogs,
                        onClick  = { SystemActions.openFolder(paths.logsDir.toString()) },
                        modifier = Modifier.fillMaxWidth(),
                        style    = NxButtonStyle.Secondary,
                    )
                }
            }
            Box(Modifier.weight(1f)) {
                Flexible("settings_crash_reports_btn", FlexibleKind.Button) {
                    NxButton(
                        label    = s.settingsOpenCrashReports,
                        onClick  = { SystemActions.openFolder(paths.crashDir.toString()) },
                        modifier = Modifier.fillMaxWidth(),
                        style    = NxButtonStyle.Secondary,
                    )
                }
            }
            PuppetClick("settings.openLogsDir")      { SystemActions.openFolder(paths.logsDir.toString()) }
            PuppetClick("settings.openCrashReports") { SystemActions.openFolder(paths.crashDir.toString()) }
        }

        // Bundle + GitHub button row, with the hint kept tight to it.
        Column {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) {
                    Flexible("settings_create_diag_bundle_btn", FlexibleKind.Button) {
                        NxButton(
                            label    = s.settingsCreateDiagnosticBundle,
                            onClick  = {
                                if (bundleBusy) return@NxButton
                                bundleBusy = true
                                bundleScope.launch {
                                    val zip = withContext(Dispatchers.IO) {
                                        runCatching { DiagnosticBundle.create(paths) }.getOrNull()
                                    }
                                    if (zip != null) {
                                        lastBundlePath = zip
                                        SystemActions.openFile(zip.parent.toFile())
                                    }
                                    bundleBusy = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            style    = NxButtonStyle.Secondary,
                            enabled  = !bundleBusy,
                        )
                    }
                }
                Box(Modifier.weight(1f)) {
                    Flexible("settings_report_github_btn", FlexibleKind.Button) {
                        NxButton(
                            label    = s.settingsReportOnGithub,
                            onClick  = {
                                val zip = lastBundlePath ?: return@NxButton
                                runCatching {
                                    // Copy the path so the user can drag-attach from the
                                    // file manager OR paste it into a comment.
                                    Toolkit.getDefaultToolkit().systemClipboard
                                        .setContents(StringSelection(zip.toString()), null)
                                    SystemActions.openUrl(IssueReporter.bundleIssueUrl(zip))
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            style    = NxButtonStyle.Secondary,
                            enabled  = lastBundlePath != null,
                        )
                    }
                }
                PuppetClick("settings.createDiagBundle", enabled = !bundleBusy) {
                    bundleBusy = true
                    bundleScope.launch {
                        val zip = withContext(Dispatchers.IO) {
                            runCatching { DiagnosticBundle.create(paths) }.getOrNull()
                        }
                        if (zip != null) lastBundlePath = zip
                        bundleBusy = false
                    }
                }
                PuppetClick("settings.reportOnGithub", enabled = lastBundlePath != null) {
                    val zip = lastBundlePath ?: return@PuppetClick
                    runCatching {
                        Toolkit.getDefaultToolkit().systemClipboard
                            .setContents(StringSelection(zip.toString()), null)
                        SystemActions.openUrl(IssueReporter.bundleIssueUrl(zip))
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text     = s.settingsDiagnosticBundleHint,
                style    = MaterialTheme.typography.bodySmall,
                color    = NxTheme.colors.textSecondary,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        // Restart into the boot recovery surface -- disable a broken module or
        // reset corrupted state when the shell starts but misbehaves.
        Flexible("settings_restart_recovery_btn", FlexibleKind.Button) {
            NxButton(
                label    = s.recoveryRestartInApp,
                onClick  = { restartIntoRecovery(paths.dataDir) },
                modifier = Modifier.fillMaxWidth(),
                style    = NxButtonStyle.Secondary,
            )
        }
        PuppetClick("settings.restartRecovery") { restartIntoRecovery(paths.dataDir) }
    }

    Spacer(Modifier.height(16.dp))

    PuppetClick("settings.openAbout") { onOpenAbout() }
    NxNavRow(
        icon     = NxIcon.Info,
        title    = Branding.TITLE,
        subtitle = "v${Branding.VERSION.removePrefix("v")} — GPLv3",
        onClick  = onOpenAbout,
    )
}

/**
 * Arm the recovery marker, relaunch, and exit so the fresh process boots into the
 * recovery surface. On a dev / unsupported run relaunch returns false: the marker is
 * still armed, so the next manual start enters recovery.
 */
private fun restartIntoRecovery(dataDir: Path) {
    RecoveryEntry.requestOnNextBoot(dataDir)
    if (AppRelauncher.relaunch()) exitProcess(0)
}

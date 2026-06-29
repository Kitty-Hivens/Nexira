package hivens.ui.screens.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import hivens.config.Branding
import hivens.launcher.diag.DiagnosticBundle
import hivens.launcher.diag.IssueReporter
import hivens.launcher.platform.PlatformPaths
import hivens.ui.easter.LocalAprilFools
import hivens.ui.flexible.Flexible
import hivens.ui.flexible.FlexibleKind
import hivens.ui.i18n.LocalStrings
import hivens.ui.nx.NxButton
import hivens.ui.nx.NxButtonStyle
import hivens.ui.nx.NxNavRow
import hivens.ui.icons.NxIcon
import hivens.ui.platform.SystemActions
import hivens.ui.puppet.PuppetClick
import hivens.ui.theme.NxTheme
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/**
 * Beacon diagnostic surface + About card.
 *
 * Diagnostics: open logs / crash-reports folders, generate a redacted
 * diagnostic bundle ZIP (lazy-busy gated so a double-tap doesn't fire
 * two parallel writes), and after one exists, open the GitHub issue
 * template prefilled with bundle metadata. Each button is wrapped in
 * AprilFools.ChaosButton -- the chaos layer intercepts during the
 * Apr 1-14 window, otherwise renders as a plain button.
 *
 * Hidden gesture: tap the DIAGNOSTICS section title 5 times to reveal
 * the AprilFools debug panel; tap 5 more to hide it. Lets dev exercise
 * the chaos layer without changing the system date.
 *
 * About card at the bottom: link out to the AboutScreen with version
 * label and license tag. Kept in the same section because they share
 * the "meta about the launcher" register.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DiagnosticsSection(
    paths: PlatformPaths,
    onOpenAbout: () -> Unit,
) {
    val s  = LocalStrings.current
    val af = LocalAprilFools.current

    // ── April Fools debug panel -- secret unlock ────────────────────
    // Only wire the 5-tap gesture when the active impl actually renders
    // a debug panel. Production builds without `-PauraAprilFools=true`
    // ship NoOpAprilFools whose DebugPanel() is empty, so a 5-tap on
    // those builds would toggle a hidden state that renders to a couple
    // of invisible Spacers -- exact symptom of "tap the title, list
    // twitches a tiny bit, panel never shows" from the 2.3.2 report.
    if (af.providesDebugPanel) {
        var debugTapCount  by remember { mutableStateOf(0) }
        var showAprilDebug by remember { mutableStateOf(false) }
        val debugPanelBringIntoView = remember { BringIntoViewRequester() }

        Box(
            Modifier.clickable {
                debugTapCount++
                if (debugTapCount >= 5) {
                    debugTapCount  = 0
                    showAprilDebug = !showAprilDebug
                }
            }
        ) {
            SettingsSectionTitle(s.settingsSectionDiagnostics)
        }

        LaunchedEffect(showAprilDebug) {
            if (showAprilDebug) {
                // Wait one frame so the panel has been laid out before
                // the scroll request is sent. bringIntoView() against a
                // not-yet-positioned target is a no-op.
                yield()
                runCatching { debugPanelBringIntoView.bringIntoView() }
            }
        }

        if (showAprilDebug) {
            Spacer(Modifier.height(2.dp))
            Box(Modifier.bringIntoViewRequester(debugPanelBringIntoView)) {
                af.DebugPanel()
            }
            Spacer(Modifier.height(2.dp))
        }
    } else {
        SettingsSectionTitle(s.settingsSectionDiagnostics)
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.weight(1f)) {
            Flexible("settings_open_logs_btn", FlexibleKind.Button) {
                NxButton(
                    label = s.settingsOpenLogs,
                    onClick = { SystemActions.openFolder(paths.logsDir.toString()) },
                    modifier = Modifier.fillMaxWidth(),
                    style = NxButtonStyle.Secondary,
                )
            }
        }
        PuppetClick("settings.openLogsDir") { SystemActions.openFolder(paths.logsDir.toString()) }
        Box(Modifier.weight(1f)) {
            Flexible("settings_crash_reports_btn", FlexibleKind.Button) {
                NxButton(
                    label = s.settingsOpenCrashReports,
                    onClick = { SystemActions.openFolder(paths.crashDir.toString()) },
                    modifier = Modifier.fillMaxWidth(),
                    style = NxButtonStyle.Secondary,
                )
            }
        }
        PuppetClick("settings.openCrashReports") { SystemActions.openFolder(paths.crashDir.toString()) }
    }

    Spacer(Modifier.height(2.dp))

    // Beacon: one-click ZIP for support -- bundles redacted logs,
    // crash reports, action ring and system info. The companion
    // GitHub-Issue button is enabled only after a bundle exists in
    // this session.
    //
    // Generation runs off the Compose UI thread: filesystem reads +
    // ZIP compression of a 200 MB launcher.log cap is enough to freeze
    // Settings for a noticeable beat. While generating, the button is
    // disabled so a double click doesn't fire two parallel writes to
    // the same data dir.
    var lastBundlePath by remember { mutableStateOf<Path?>(null) }
    var bundleBusy     by remember { mutableStateOf(false) }
    val bundleScope    = rememberCoroutineScope()

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.weight(1f)) {
            Flexible("settings_create_diag_bundle_btn", FlexibleKind.Button) {
                NxButton(
                    label = s.settingsCreateDiagnosticBundle,
                    onClick = {
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
                    style = NxButtonStyle.Secondary,
                    enabled = !bundleBusy,
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
        Box(Modifier.weight(1f)) {
            Flexible("settings_report_github_btn", FlexibleKind.Button) {
                NxButton(
                    label = s.settingsReportOnGithub,
                    onClick = {
                        val zip = lastBundlePath ?: return@NxButton
                        runCatching {
                            // Copy path so the user can drag-attach from the file
                            // manager OR paste the path into a comment.
                            Toolkit.getDefaultToolkit().systemClipboard
                                .setContents(StringSelection(zip.toString()), null)
                            SystemActions.openUrl(IssueReporter.bundleIssueUrl(zip))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    style = NxButtonStyle.Secondary,
                    enabled = lastBundlePath != null,
                )
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
    Text(
        text     = s.settingsDiagnosticBundleHint,
        color    = NxTheme.colors.textSecondary,
        fontSize = TextUnit(11f, TextUnitType.Sp),
        modifier = Modifier.padding(start = 8.dp, top = 2.dp)
    )

    Spacer(Modifier.height(8.dp))

    // ── About link ───────────────────────────────────────────────────
    SettingsSectionTitle(s.settingsSectionAbout)
    PuppetClick("settings.openAbout") { onOpenAbout() }

    NxNavRow(
        icon     = NxIcon.Info,
        title    = Branding.TITLE,
        subtitle = "v${Branding.VERSION.removePrefix("v")} — GPLv3",
        onClick  = onOpenAbout,
    )
}

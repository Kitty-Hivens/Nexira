package hivens.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import hivens.config.Branding
import hivens.launcher.diag.DiagnosticBundle
import hivens.launcher.diag.IssueReporter
import hivens.launcher.platform.PlatformPaths
import hivens.ui.easter.LocalAprilFools
import hivens.ui.i18n.LocalStrings
import hivens.ui.platform.SystemActions
import hivens.ui.puppet.PuppetClick
import hivens.ui.theme.CelestiaTheme
import hivens.ui.theme.LocalStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.nio.file.Path

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
@Composable
internal fun DiagnosticsSection(
    paths: PlatformPaths,
    onOpenAbout: () -> Unit,
) {
    val s  = LocalStrings.current
    val af = LocalAprilFools.current
    val style = LocalStyle.current

    // ── April Fools debug panel -- secret unlock ────────────────────
    var debugTapCount  by remember { mutableStateOf(0) }
    var showAprilDebug by remember { mutableStateOf(false) }

    // Tap the diagnostics title 5 times to toggle the April Fools debug panel
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

    // April Fools debug panel (hidden by default)
    if (showAprilDebug) {
        Spacer(Modifier.height(8.dp))
        af.DebugPanel()
        Spacer(Modifier.height(8.dp))
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        // Open logs -- chaos target
        af.ChaosButton(
            id       = "settings_open_logs_btn",
            text     = s.settingsOpenLogs,
            onClick  = { SystemActions.openFolder(paths.logsDir.toString()) },
            modifier = Modifier.weight(1f),
            colors   = ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                contentColor   = CelestiaTheme.colors.textPrimary,
            ),
        )
        PuppetClick("settings.openLogsDir") { SystemActions.openFolder(paths.logsDir.toString()) }
        // Open crash reports -- chaos target
        af.ChaosButton(
            id       = "settings_crash_reports_btn",
            text     = s.settingsOpenCrashReports,
            onClick  = { SystemActions.openFolder(paths.crashDir.toString()) },
            modifier = Modifier.weight(1f),
            colors   = ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                contentColor   = CelestiaTheme.colors.textPrimary,
            ),
        )
        PuppetClick("settings.openCrashReports") { SystemActions.openFolder(paths.crashDir.toString()) }
    }

    Spacer(Modifier.height(8.dp))

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
        af.ChaosButton(
            id       = "settings_create_diag_bundle_btn",
            text     = s.settingsCreateDiagnosticBundle,
            onClick  = {
                if (bundleBusy) return@ChaosButton
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
            modifier = Modifier.weight(1f),
            colors   = ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                contentColor   = CelestiaTheme.colors.textPrimary.copy(
                    alpha = if (bundleBusy) 0.45f else 1f
                ),
            ),
            enabled  = !bundleBusy,
        )
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
        af.ChaosButton(
            id       = "settings_report_github_btn",
            text     = s.settingsReportOnGithub,
            onClick  = {
                val zip = lastBundlePath ?: return@ChaosButton
                runCatching {
                    // Copy path so the user can drag-attach from the file
                    // manager OR paste the path into a comment.
                    Toolkit.getDefaultToolkit().systemClipboard
                        .setContents(StringSelection(zip.toString()), null)
                    SystemActions.openUrl(IssueReporter.bundleIssueUrl(zip))
                }
            },
            modifier = Modifier.weight(1f),
            colors   = ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                contentColor   = CelestiaTheme.colors.textPrimary.copy(
                    alpha = if (lastBundlePath != null) 1f else 0.35f
                ),
            ),
            enabled  = lastBundlePath != null,
        )
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
        color    = CelestiaTheme.colors.textSecondary,
        fontSize = TextUnit(11f, TextUnitType.Sp),
        modifier = Modifier.padding(start = 8.dp, top = 2.dp)
    )

    Spacer(Modifier.height(24.dp))

    // ── About link ───────────────────────────────────────────────────
    SettingsSectionTitle(s.settingsSectionAbout)
    PuppetClick("settings.openAbout") { onOpenAbout() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(style.cardCorner))
            .clickable(onClick = onOpenAbout)
            .background(CelestiaTheme.colors.surface.copy(alpha = 0.4f))
            .padding(16.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Info, null, tint = CelestiaTheme.colors.primary, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(16.dp))
            Column {
                Text(Branding.TITLE, color = CelestiaTheme.colors.textPrimary, fontWeight = FontWeight.Bold)
                Text(
                    "v${Branding.VERSION.removePrefix("v")} -- GPLv3",
                    style = MaterialTheme.typography.bodySmall,
                    color = CelestiaTheme.colors.textSecondary
                )
            }
        }
        Icon(Icons.Default.ChevronRight, null, tint = CelestiaTheme.colors.textSecondary)
    }
}

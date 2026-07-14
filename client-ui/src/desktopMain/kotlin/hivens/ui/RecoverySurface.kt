package hivens.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import hivens.config.Branding
import hivens.core.data.ModuleId
import hivens.launcher.platform.AppRelauncher
import hivens.ui.bootstrap.RecoveryIo
import hivens.ui.diag.UiRecoverySignal
import hivens.ui.i18n.AppLocale
import hivens.ui.i18n.stringsFor
import java.nio.file.Path
import java.util.Locale

/**
 * Boot recovery surface, shown instead of the shell when the entry point resolves
 * a recovery request (see hivens.ui.diag.UiRecoverySignal + Main.runShellWithRecovery):
 * either a user asked for it (env / --recovery / marker / hold-key) or the crash
 * loop latched it.
 *
 * Deliberately self-contained: it touches NO Koin, NxTheme, widget kernel, or
 * settings service -- those are exactly what a recovery boot distrusts (and, on a
 * user-requested recovery, boot is skipped entirely so none of them exist). It
 * reads and writes settings.json through the raw [RecoveryIo] and speaks the OS
 * locale via a pure stringsFor() lookup.
 *
 * It offers what recovers a launcher an environment breaks: toggle a system module
 * off (tray on a DE without SNI, skinema when its natives fail, ...), reset a
 * corrupted layout / customization / settings, then continue -- which relaunches
 * the process, since cached-at-boot settings only take effect in a fresh one.
 */
@Composable
fun RecoveryWindow(
    dataDir: Path,
    reason: UiRecoverySignal.RecoveryReason,
    onExit: () -> Unit,
) {
    val s = remember { stringsFor(AppLocale.fromTag(Locale.getDefault().language)) }
    var disabled by remember { mutableStateOf(RecoveryIo.readDisabledModules(dataDir)) }
    var relaunchFailed by remember { mutableStateOf(false) }
    val windowState = rememberWindowState(position = WindowPosition(Alignment.Center))

    // A crash-latched entry keeps the failure-framed copy; a user request gets the
    // neutral recovery copy. The controls are identical either way.
    val crash = reason == UiRecoverySignal.RecoveryReason.CrashLoop
    val title = if (crash) s.recoverySafeModeTitle else s.recoveryTitle
    val body = if (crash) s.recoverySafeModeBody else s.recoveryBody

    fun setDisabled(id: String, off: Boolean) {
        disabled = if (off) disabled + id else disabled - id
        RecoveryIo.writeDisabledModules(dataDir, disabled)
    }

    Window(onCloseRequest = onExit, state = windowState, title = Branding.TITLE) {
        MaterialTheme(colorScheme = darkColorScheme()) {
            Surface(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.widthIn(max = 560.dp),
                    )

                    Text(s.recoveryModulesHeading, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                    ModuleId.entries.forEach { module ->
                        val label = when (module) {
                            ModuleId.Tray -> s.recoveryModuleTray
                            ModuleId.Notify -> s.recoveryModuleNotify
                            ModuleId.Skinema -> s.recoveryModuleSkinema
                            ModuleId.Keyring -> s.recoveryModuleKeyring
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Switch(checked = module.id in disabled, onCheckedChange = { setDisabled(module.id, it) })
                            Text(label, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }

                    Text(s.recoveryResetsHeading, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { RecoveryIo.resetLayout(dataDir) }) { Text(s.recoveryResetLayout) }
                        OutlinedButton(onClick = { RecoveryIo.resetCustomization(dataDir) }) { Text(s.recoveryResetCustomization) }
                        OutlinedButton(onClick = {
                            RecoveryIo.resetSettings(dataDir)
                            disabled = RecoveryIo.readDisabledModules(dataDir)
                        }) { Text(s.recoveryResetSettings) }
                    }

                    if (relaunchFailed) {
                        Text(s.recoveryRelaunchFailed, color = MaterialTheme.colorScheme.error)
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { if (AppRelauncher.relaunch()) onExit() else relaunchFailed = true }) {
                            Text(s.recoveryContinue)
                        }
                        OutlinedButton(onClick = onExit) { Text(s.recoverySafeModeQuit) }
                    }
                }
            }
        }
    }
}

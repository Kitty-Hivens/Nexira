package hivens.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.core.diag.ActionRing
import hivens.launcher.platform.DataDirMover
import hivens.launcher.platform.PlatformPaths
import hivens.ui.i18n.LocalStrings
import hivens.ui.theme.CelestiaTheme
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import io.github.vinceglb.filekit.dialogs.openDirectoryPicker
import io.github.vinceglb.filekit.path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess

/**
 * Advanced surface -- currently houses the data-directory mover.
 *
 * Schedule-on-restart move via DataDirMover. The actual file copy
 * happens on next launcher start (BEFORE PlatformPaths is consulted)
 * so we don't have to fight Windows lock semantics or coordinate with
 * background tasks. The UI here just persists the intent and prompts
 * the user to restart.
 */
@Composable
internal fun AdvancedSection(paths: PlatformPaths) {
    val s = LocalStrings.current

    SettingsSectionTitle(s.settingsSectionDataDir)

    var pendingTarget by remember { mutableStateOf<Path?>(null) }
    var showError     by remember { mutableStateOf<String?>(null) }
    val moveScope     = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CelestiaTheme.colors.background.copy(alpha = 0.4f))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text  = s.settingsDataDirCurrent,
            style = MaterialTheme.typography.bodySmall,
            color = CelestiaTheme.colors.textSecondary,
        )
        Text(
            text       = paths.dataDir.toAbsolutePath().toString(),
            color      = CelestiaTheme.colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
            style      = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(
            onClick = {
                showError = null
                moveScope.launch {
                    // filekit uses xdg-desktop-portal on Linux (native
                    // Hyprland / KDE / GNOME picker), IFileDialog on
                    // Windows, NSOpenPanel on macOS. No Metal LAF eyesore.
                    //
                    // dialogSettings(title=...) is the key bit -- without
                    // it FileKit hands the portal a blank-title request
                    // and some backends render a less-styled fallback
                    // chrome (no titlebar text, generic icon). Match what
                    // ProfileScreen + ServerSettingsScreen pass here.
                    val pickedFile = runCatching {
                        FileKit.openDirectoryPicker(
                            directory      = PlatformFile(paths.dataDir.toFile()),
                            dialogSettings = FileKitDialogSettings(title = s.settingsDataDirMove),
                        )
                    }.getOrNull() ?: return@launch

                    val picked = Paths.get(pickedFile.path)

                    if (picked.toAbsolutePath().normalize() == paths.dataDir.toAbsolutePath().normalize()) {
                        showError = s.settingsDataDirErrorSamePath
                        return@launch
                    }
                    val populated = withContext(Dispatchers.IO) {
                        runCatching {
                            Files.exists(picked) &&
                                Files.list(picked).use { it.findAny().isPresent }
                        }.getOrDefault(false)
                    }
                    if (populated) {
                        showError = s.settingsDataDirErrorNotEmpty
                        return@launch
                    }
                    pendingTarget = picked
                }
            },
            shape = RoundedCornerShape(6.dp),
        ) {
            Text(s.settingsDataDirMove, color = CelestiaTheme.colors.textPrimary)
        }
        if (showError != null) {
            Text(
                text  = showError!!,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFEF4444),
            )
        }
    }

    if (pendingTarget != null) {
        val target = pendingTarget!!
        AlertDialog(
            onDismissRequest = { pendingTarget = null },
            title = { Text(s.settingsDataDirConfirmTitle) },
            text  = {
                Text(s.settingsDataDirConfirmBody(
                    paths.dataDir.toAbsolutePath().toString(),
                    target.toAbsolutePath().toString(),
                ))
            },
            confirmButton = {
                Button(onClick = {
                    val ok = DataDirMover.schedule(
                        source = paths.dataDir,
                        target = target,
                    )
                    if (ok) {
                        ActionRing.record(
                            "Data-dir move scheduled: ${paths.dataDir} -> $target -- quitting for restart",
                        )
                        // Hard exit -- user explicitly clicked "Quit now".
                        // Avoids the tray-shutdown path that might re-show
                        // the window if a game is mid-launch. The pending
                        // move only applies AFTER the launcher restarts,
                        // so a clean process termination is the right move.
                        exitProcess(0)
                    } else {
                        // Schedule was refused (target validations failed
                        // at the mover layer -- e.g., race with another
                        // process touching the target between our UI
                        // check and DataDirMover.schedule). Close the
                        // dialog so the user can pick a different target.
                        pendingTarget = null
                    }
                }) { Text(s.settingsDataDirQuitNow) }
            },
            dismissButton = {
                OutlinedButton(onClick = { pendingTarget = null }) {
                    Text(s.sslWarningCancel)
                }
            },
            containerColor = CelestiaTheme.colors.surface,
        )
    }
}

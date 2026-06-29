package hivens.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.core.diag.ActionRing
import hivens.launcher.platform.DataDirMover
import hivens.launcher.platform.PlatformPaths
import hivens.ui.i18n.LocalStrings
import hivens.ui.nx.NxButton
import hivens.ui.nx.NxButtonStyle
import hivens.ui.nx.NxSection
import hivens.ui.theme.NxTheme
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import io.github.vinceglb.filekit.dialogs.openDirectoryPicker
import io.github.vinceglb.filekit.path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess

private val log = LoggerFactory.getLogger("AdvancedSection")

/**
 * Advanced surface -- currently the data-directory mover. The move is scheduled
 * on restart via DataDirMover (the copy runs on next start, before PlatformPaths
 * is consulted), so this screen only persists the intent and prompts to restart.
 */
@Composable
internal fun AdvancedSection(paths: PlatformPaths) {
    val s = LocalStrings.current

    var pendingTarget by remember { mutableStateOf<Path?>(null) }
    var showError     by remember { mutableStateOf<String?>(null) }
    val moveScope     = rememberCoroutineScope()

    NxSection(s.settingsSectionDataDir) {
        Text(s.settingsDataDirCurrent, style = MaterialTheme.typography.bodySmall, color = NxTheme.colors.textSecondary)
        Text(
            text       = paths.dataDir.toAbsolutePath().toString(),
            color      = NxTheme.colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
            style      = MaterialTheme.typography.bodyMedium,
        )
        NxButton(
            label   = s.settingsDataDirMove,
            style   = NxButtonStyle.Secondary,
            compact = true,
            onClick = {
                showError = null
                moveScope.launch {
                    // filekit uses xdg-desktop-portal on Linux (native Hyprland / KDE /
                    // GNOME picker), IFileDialog on Windows, NSOpenPanel on macOS. The
                    // ProGuard keep on io.github.vinceglb.filekit.** is what makes the
                    // directory-hint overload survive shrinking (the 2.3.1 dead-button bug).
                    val pickResult = runCatching {
                        FileKit.openDirectoryPicker(
                            directory      = PlatformFile(paths.dataDir.toFile()),
                            dialogSettings = FileKitDialogSettings(title = s.settingsDataDirMove),
                        )
                    }
                    val pickedFile = pickResult.getOrElse { ex ->
                        // Surface the failure so a "dead button" stops looking dead.
                        log.warn("openDirectoryPicker failed", ex)
                        showError = s.settingsDataDirErrorPickerFailed(ex.message ?: ex.javaClass.simpleName)
                        return@launch
                    } ?: return@launch

                    val picked = Paths.get(pickedFile.path)
                    if (picked.toAbsolutePath().normalize() == paths.dataDir.toAbsolutePath().normalize()) {
                        showError = s.settingsDataDirErrorSamePath
                        return@launch
                    }
                    val populated = withContext(Dispatchers.IO) {
                        runCatching {
                            Files.exists(picked) && Files.list(picked).use { it.findAny().isPresent }
                        }.getOrDefault(false)
                    }
                    if (populated) {
                        showError = s.settingsDataDirErrorNotEmpty
                        return@launch
                    }
                    pendingTarget = picked
                }
            },
        )
        if (showError != null) {
            Text(showError!!, style = MaterialTheme.typography.bodySmall, color = NxTheme.colors.error)
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
                Button(shape = MaterialTheme.shapes.small, onClick = {
                    val ok = DataDirMover.schedule(source = paths.dataDir, target = target)
                    if (ok) {
                        ActionRing.record("Data-dir move scheduled: ${paths.dataDir} -> $target -- quitting for restart")
                        // Hard exit -- user explicitly clicked "Quit now". The pending move
                        // applies only after restart, so a clean process termination is right.
                        exitProcess(0)
                    } else {
                        // Schedule refused (target validation raced); let the user re-pick.
                        pendingTarget = null
                    }
                }) { Text(s.settingsDataDirQuitNow) }
            },
            dismissButton = {
                OutlinedButton(onClick = { pendingTarget = null }, shape = MaterialTheme.shapes.small) {
                    Text(s.sslWarningCancel)
                }
            },
            containerColor = NxTheme.colors.surface,
        )
    }
}

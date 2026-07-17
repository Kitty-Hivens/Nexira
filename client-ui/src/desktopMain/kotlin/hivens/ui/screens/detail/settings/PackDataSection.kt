package hivens.ui.screens.detail.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.launcher.instance.PackInstanceService
import hivens.ui.components.DestructiveConfirmDialog
import hivens.ui.i18n.LocalStrings
import hivens.ui.nx.NxButton
import hivens.ui.nx.NxButtonStyle
import hivens.ui.nx.NxRow
import hivens.ui.nx.NxSection
import hivens.ui.platform.SystemActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.nio.file.Files
import java.nio.file.Path

/**
 * Storage + lifecycle: open the instance folder (with its on-disk size), detach a
 * remote instance into a Local copy, and the destructive delete. The delete and
 * detach both go through [PackInstanceService] so file/registry ordering lives in
 * one place; deleting closes the window since the pack is gone.
 */
@Composable
internal fun PackDataSection(
    pack: PackInstance,
    instanceDir: Path,
    onInstanceChange: (PackInstance) -> Unit,
    onDismiss: () -> Unit,
) {
    val s = LocalStrings.current
    val service: PackInstanceService = koinInject()
    val scope = rememberCoroutineScope()

    var sizeText by remember(pack.id) { mutableStateOf<String?>(null) }
    var pendingDelete by remember(pack.id) { mutableStateOf(false) }

    LaunchedEffect(instanceDir) {
        sizeText = withContext(Dispatchers.IO) { runCatching { humanSize(dirSizeBytes(instanceDir)) }.getOrNull() }
    }

    NxSection(s.packSettingsStorage) {
        NxRow(title = s.packSettingsFolder, subtitle = sizeText ?: s.packSettingsSizeComputing) {
            NxButton(
                s.packSettingsOpenFolder,
                onClick = { SystemActions.openFolder(instanceDir.toString()) },
                style = NxButtonStyle.Secondary,
                compact = true,
            )
        }
        if (pack.packRef.origin != PackOrigin.Local) {
            NxRow(title = s.packSettingsDetach, subtitle = s.packSettingsDetachDesc) {
                NxButton(
                    s.packSettingsDetachAction,
                    onClick = { scope.launch { onInstanceChange(service.detachToLocal(pack)) } },
                    style = NxButtonStyle.Secondary,
                    compact = true,
                )
            }
        }
    }

    NxSection(s.packSettingsDangerZone) {
        NxRow(title = s.packSettingsDelete, subtitle = s.packSettingsDeleteDesc) {
            NxButton(
                s.packSettingsDelete,
                onClick = { pendingDelete = true },
                style = NxButtonStyle.Destructive,
                compact = true,
            )
        }
    }

    if (pendingDelete) {
        DestructiveConfirmDialog(
            title = s.packCardDeleteTitle,
            body = s.packCardDeleteBody,
            confirmLabel = s.packSettingsDelete,
            onConfirm = {
                pendingDelete = false
                scope.launch { if (service.deleteCompletely(pack)) onDismiss() }
            },
            onDismiss = { pendingDelete = false },
        )
    }
}

private fun dirSizeBytes(dir: Path): Long {
    if (!Files.exists(dir)) return 0L
    Files.walk(dir).use { stream ->
        return stream.filter { Files.isRegularFile(it) }
            .mapToLong { runCatching { Files.size(it) }.getOrDefault(0L) }
            .sum()
    }
}

private fun humanSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024.0
    var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    return "%.1f %s".format(value, units[unit])
}

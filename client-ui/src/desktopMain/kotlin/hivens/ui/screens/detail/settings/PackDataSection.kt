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
import hivens.launcher.update.PackUpdateService
import hivens.ui.components.DestructiveConfirmDialog
import hivens.ui.i18n.LocalStrings
import hivens.ui.nx.NxButton
import hivens.ui.nx.NxButtonStyle
import hivens.ui.nx.NxRow
import hivens.ui.nx.NxSection
import hivens.ui.platform.SystemActions
import hivens.ui.puppet.PuppetClick
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.nio.file.Files
import java.nio.file.Path

/**
 * Storage + lifecycle: open the instance folder (with its on-disk size), verify
 * and repair its files, detach a remote instance into a Local copy, and the
 * destructive delete. The delete and detach both go through [PackInstanceService]
 * so file/registry ordering lives in one place; deleting closes the window since
 * the pack is gone.
 *
 * Repair sits here rather than under Version because it is an operation on the
 * files, not on which build is installed: it measures the instance against the
 * build it is already pinned to and restores only what does not match. Files the
 * manifest never named -- a jar the user dropped in, a config they edited -- are
 * not touched, so it is a repair rather than a reset.
 */
@Composable
internal fun PackDataSection(
    pack: PackInstance,
    instanceDir: Path,
    onInstanceChange: (PackInstance) -> Unit,
    onDismiss: () -> Unit,
    onOpState: (PackSettingsOp) -> Unit = {},
) {
    val s = LocalStrings.current
    val service: PackInstanceService = koinInject()
    val updates: PackUpdateService = koinInject()
    val scope = rememberCoroutineScope()
    // A repair walks the whole pack and outlives the window, exactly like an
    // update apply -- see the note on PackVersionSection.applyLatest.
    val appScope: CoroutineScope = koinInject()

    var sizeText by remember(pack.id) { mutableStateOf<String?>(null) }
    var pendingDelete by remember(pack.id) { mutableStateOf(false) }
    var repairing by remember(pack.id) { mutableStateOf(false) }

    LaunchedEffect(instanceDir) {
        sizeText = withContext(Dispatchers.IO) { runCatching { humanSize(dirSizeBytes(instanceDir)) }.getOrNull() }
    }

    fun runRepair() {
        if (repairing) return
        appScope.launch {
            repairing = true
            onOpState(PackSettingsOp.Running(PackSettingsOpKind.Repair, 0, 0, ""))
            runCatching {
                updates.verifyAndRepair(pack) { current, total, path ->
                    onOpState(PackSettingsOp.Running(PackSettingsOpKind.Repair, current, total, path.substringAfterLast('/')))
                }
            }.onSuccess { report ->
                // The folder size is stale the moment anything was replaced.
                sizeText = withContext(Dispatchers.IO) {
                    runCatching { humanSize(dirSizeBytes(instanceDir)) }.getOrNull()
                }
                onOpState(PackSettingsOp.Repaired(report.checked, report.repaired.size))
            }.onFailure {
                onOpState(PackSettingsOp.Failed(it.message ?: it::class.simpleName.orEmpty()))
            }
            repairing = false
        }
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
        // Mirror-only: a repair measures the instance against a published manifest,
        // and a local or imported pack has none to measure against.
        if (pack.packRef.origin == PackOrigin.Mirror) {
            NxRow(title = s.packSettingsRepair, subtitle = s.packSettingsRepairDesc) {
                PuppetClick("packSettings.data.repair") { runRepair() }
                NxButton(
                    s.packSettingsRepairAction,
                    onClick = { runRepair() },
                    style = NxButtonStyle.Secondary,
                    enabled = !repairing,
                    compact = true,
                )
            }
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

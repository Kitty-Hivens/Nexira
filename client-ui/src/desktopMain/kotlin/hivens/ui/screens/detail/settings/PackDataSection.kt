package hivens.ui.screens.detail.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.launcher.PackOperation
import hivens.launcher.PackOperationKind
import hivens.launcher.PackOperationPhase
import hivens.launcher.PackOperationService
import hivens.launcher.instance.InstanceSizeService
import hivens.launcher.instance.PackInstanceService
import hivens.launcher.update.PackUpdateService
import hivens.ui.utils.humanSize
import hivens.ui.components.DestructiveConfirmDialog
import hivens.ui.components.rememberRunningPackGuard
import hivens.ui.i18n.LocalStrings
import hivens.ui.nx.NxButton
import hivens.ui.nx.NxButtonStyle
import hivens.ui.nx.NxRow
import hivens.ui.nx.NxSection
import hivens.ui.platform.SystemActions
import hivens.ui.puppet.PuppetClick
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
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
 *
 * It runs through [PackOperationService], which owns it for as long as it takes:
 * a repair walks the whole pack and outlives the window that asked for it, and
 * only one operation at a time may rewrite an instance.
 */
@Composable
internal fun PackDataSection(
    pack: PackInstance,
    instanceDir: Path,
    operation: PackOperation?,
    onDismiss: () -> Unit,
) {
    val s = LocalStrings.current
    val service: PackInstanceService = koinInject()
    val updates: PackUpdateService = koinInject()
    val operations: PackOperationService = koinInject()
    val sizes: InstanceSizeService = koinInject()
    // Both actions here rewrite the instance and outlive this window: deleting it
    // makes the screen behind resolve to a dead end, which disposes the window --
    // and on the composition's scope that cancelled the delete's own tail, so the
    // size entry for an instance that no longer exists was left behind.
    val scope: CoroutineScope = koinInject()

    // A repair rewrites whatever does not match, so it is one of the operations
    // that must not surprise a live session.
    val runningGuard = rememberRunningPackGuard(pack.id)

    var pendingDelete by remember(pack.id) { mutableStateOf(false) }
    val busy = operation?.isRunning == true

    // Asks for a measurement rather than taking one: the service walks the tree
    // only when what it holds is too old to serve, so moving between sections
    // costs nothing.
    val measured by sizes.sizes.collectAsState()
    LaunchedEffect(pack.id) { sizes.measure(pack) }
    val sizeText = measured[pack.id]?.let { humanSize(it.bytes, s) }

    fun runRepair() {
        operations.start(pack, PackOperationKind.Repair) { progress ->
            val report = updates.verifyAndRepair(pack) { current, total, path ->
                progress(current, total, path.substringAfterLast('/'))
            }
            PackOperationPhase.Repaired(report.checked, report.repaired.size)
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
                PuppetClick("packSettings.data.repair") { runningGuard.run(::runRepair) }
                NxButton(
                    s.packSettingsRepairAction,
                    onClick = { runningGuard.run(::runRepair) },
                    style = NxButtonStyle.Secondary,
                    enabled = !busy,
                    compact = true,
                )
            }
        }
        if (pack.packRef.origin != PackOrigin.Local) {
            // Detaching costs the instance its update source and cannot be undone
            // from here. The browsing surface already refuses to carry the action
            // for that reason; one click and no question was not much better.
            var confirmDetach by remember { mutableStateOf(false) }
            if (confirmDetach) {
                DestructiveConfirmDialog(
                    title        = s.packSettingsDetach,
                    body         = s.packSettingsDetachDesc,
                    confirmLabel = s.packSettingsDetachAction,
                    onConfirm    = { scope.launch { service.detachToLocal(pack) } },
                    onDismiss    = { confirmDetach = false },
                )
            }
            NxRow(title = s.packSettingsDetach, subtitle = s.packSettingsDetachDesc) {
                NxButton(
                    s.packSettingsDetachAction,
                    onClick = { confirmDetach = true },
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

    runningGuard.Dialog()

    if (pendingDelete) {
        DestructiveConfirmDialog(
            title = s.packCardDeleteTitle,
            body = s.packCardDeleteBody,
            confirmLabel = s.packSettingsDelete,
            onConfirm = {
                pendingDelete = false
                scope.launch {
                    if (service.deleteCompletely(pack)) {
                        sizes.forget(pack.id)
                        onDismiss()
                    }
                }
            },
            onDismiss = { pendingDelete = false },
        )
    }
}

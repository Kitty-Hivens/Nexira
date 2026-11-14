package hivens.ui.screens.detail.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.launcher.PackOperation
import hivens.launcher.PackOperationKind
import hivens.launcher.PackOperationPhase
import hivens.launcher.PackOperationService
import hivens.core.launch.InstanceWorkRegistry
import hivens.launcher.instance.InstanceBusyException
import hivens.launcher.instance.InstanceSizeService
import hivens.launcher.instance.PackInstanceService
import hivens.launcher.update.PackUpdateService
import hivens.ui.utils.humanSize
import hivens.ui.components.DestructiveConfirmDialog
import hivens.ui.components.rememberPackDeleteBlock
import hivens.ui.components.label
import hivens.ui.components.rememberRunningPackGuard
import hivens.ui.i18n.LocalStrings
import hivens.ui.nx.NxButton
import hivens.ui.nx.NxButtonStyle
import hivens.ui.nx.NxSettingGroup
import hivens.ui.nx.NxSettingRow
import hivens.ui.packs.startReattach
import hivens.ui.platform.SystemActions
import hivens.ui.puppet.PuppetClick
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.nio.file.Path

/**
 * Storage + lifecycle: open the instance folder (with its on-disk size), verify
 * and repair its files, detach a remote instance into a Local copy or reattach a
 * detached one to its source, and the destructive delete. The delete, detach and
 * reattach all go through [PackInstanceService] so file/registry ordering lives in
 * one place; deleting closes the sheet since the pack is gone.
 *
 * Repair sits here rather than under Version because it is an operation on the
 * files, not on which build is installed: it measures the instance against the
 * build it is already pinned to and restores only what does not match. Files the
 * manifest never named -- a jar the user dropped in, a config they edited -- are
 * not touched, so it is a repair rather than a reset.
 *
 * It runs through [PackOperationService], which owns it for as long as it takes:
 * a repair walks the whole pack and outlives the sheet that asked for it, and
 * only one operation at a time may rewrite an instance.
 */
@Composable
internal fun PackDataSection(
    pack: PackInstance,
    instanceDir: Path,
    operation: PackOperation?,
    onDismiss: () -> Unit,
    /** Says something in the sheet's footer, for a refusal that has no operation of its own. */
    onNotice: (String) -> Unit = {},
) {
    val s = LocalStrings.current
    val service: PackInstanceService = koinInject()
    val updates: PackUpdateService = koinInject()
    val operations: PackOperationService = koinInject()
    val sizes: InstanceSizeService = koinInject()
    // Both actions here rewrite the instance and outlive this sheet: deleting it
    // makes the screen behind resolve to a dead end, which disposes the sheet --
    // and on the composition's scope that cancelled the delete's own tail, so the
    // size entry for an instance that no longer exists was left behind.
    val scope: CoroutineScope = koinInject()

    // A repair rewrites whatever does not match, so it is one of the operations
    // that must not surprise a live session.
    val runningGuard = rememberRunningPackGuard(pack.id)

    var pendingDelete by remember(pack.id) { mutableStateOf(false) }
    val busy = operation?.isRunning == true
    // Everything that holds the pack, not only the operations this sheet starts: an
    // auto-update, a content install or update, a recovery at start all claim it
    // through the registry, and a detach is refused while any of them does.
    val registry: InstanceWorkRegistry = koinInject()
    val works by registry.current.collectAsState()
    val held = busy || works[pack.id] != null
    val deleteBlock = rememberPackDeleteBlock(pack.id)

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
            PackOperationPhase.Repaired(report.checked, report.repaired.size, report.failed.keys.toList())
        }
    }

    // Detaching costs the instance its update source until it is reattached, and the
    // browsing surface refuses to carry the action at all. One click and no question
    // was not much better.
    var confirmDetach by remember { mutableStateOf(false) }
    // Reattaching is promised only where a reattach can happen. In practice the one
    // origin without a source that takes a pack back is an old record from the
    // former SmartyCraft server sync: packs for a game server now come from the mirror.
    val detachDesc = if (service.canReattachAfterDetach(pack)) s.packSettingsDetachDesc else s.packSettingsDetachDescFinal

    // Reattaching drops what the player changed in the pack's mods, so it asks with
    // the list of what goes and what comes back. It rewrites files like an update
    // does, so it runs as an operation and must not surprise a live session.
    val reattachSource = pack.forkedFrom?.takeIf { service.canReattach(pack) }
    var confirmReattach by remember(pack.id) { mutableStateOf(false) }
    // The source as its source names it. A Modrinth project id reads as a code, so
    // the pack's own name stands in until the source answers, if it ever does.
    val sourceName by produceState(pack.displayName.ifBlank { reattachSource?.id.orEmpty() }, pack.id, reattachSource) {
        if (reattachSource == null) return@produceState
        withContext(Dispatchers.IO) { service.reattachSourceName(pack) }?.let { value = it }
    }

    NxSettingGroup(s.packSettingsStorage) {
        NxSettingRow(s.packSettingsFolder, detail = sizeText ?: s.packSettingsSizeComputing) {
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
            NxSettingRow(s.packSettingsRepair, detail = s.packSettingsRepairDesc) {
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
        reattachSource?.let { source ->
            NxSettingRow(s.packSettingsReattach(sourceName.ifBlank { source.id }), detail = s.packSettingsReattachDesc) {
                PuppetClick("packSettings.data.reattach") { runningGuard.run { confirmReattach = true } }
                NxButton(
                    s.packSettingsReattachAction,
                    onClick = { runningGuard.run { confirmReattach = true } },
                    style = NxButtonStyle.Secondary,
                    enabled = !held,
                    compact = true,
                )
            }
        }
        if (pack.packRef.origin != PackOrigin.Local) {
            NxSettingRow(s.packSettingsDetach, detail = detachDesc) {
                NxButton(
                    s.packSettingsDetachAction,
                    onClick = { confirmDetach = true },
                    style = NxButtonStyle.Secondary,
                    // Not while other work holds the pack: the launcher refuses a
                    // detach then, and a row that did nothing on a click would read
                    // as broken.
                    enabled = !held,
                    compact = true,
                )
            }
        }
    }

    NxSettingGroup(s.packSettingsDangerZone, danger = true) {
        NxSettingRow(s.packSettingsDelete, detail = deleteBlock ?: s.packSettingsDeleteDesc) {
            NxButton(
                s.packSettingsDelete,
                onClick = { pendingDelete = true },
                style = NxButtonStyle.Destructive,
                enabled = deleteBlock == null,
                compact = true,
            )
        }
    }

    runningGuard.Dialog()

    if (confirmDetach) {
        DestructiveConfirmDialog(
            title        = s.packSettingsDetach,
            body         = detachDesc,
            confirmLabel = s.packSettingsDetachAction,
            onConfirm    = {
                scope.launch {
                    // Work that started between the click and the write holds the
                    // pack, and the launcher refuses with nothing changed. Said the
                    // way any refused operation is said, in the footer.
                    try {
                        service.detachToLocal(pack)
                    } catch (e: InstanceBusyException) {
                        onNotice(s.packOperationRefused(e.work.label(s)))
                    }
                }
            },
            onDismiss    = { confirmDetach = false },
        )
    }

    reattachSource?.let { source ->
        if (confirmReattach) {
            ReattachConfirmDialog(
                pack       = pack,
                sourceName = sourceName.ifBlank { source.id },
                onConfirm  = { confirmed -> val _ = operations.startReattach(pack, service, confirmed) },
                onDismiss  = { confirmReattach = false },
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
                scope.launch {
                    if (service.deleteCompletely(pack) == PackInstanceService.DeleteOutcome.Deleted) onDismiss()
                }
            },
            onDismiss = { pendingDelete = false },
        )
    }
}

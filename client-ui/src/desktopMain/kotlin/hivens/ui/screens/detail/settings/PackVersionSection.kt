package hivens.ui.screens.detail.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.core.api.interfaces.IMirrorPackClient
import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.PackInstance
import hivens.core.update.PackUpdater
import hivens.core.update.UpdateCheck
import hivens.core.update.UpdateOutcome
import hivens.core.update.VersionChannel
import hivens.ui.components.ChannelChip
import hivens.ui.components.formatBuildTimestamp
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.nx.NxButton
import hivens.ui.nx.NxButtonStyle
import hivens.ui.nx.NxCalloutBanner
import hivens.ui.nx.NxCalloutTone
import hivens.ui.nx.NxSection
import hivens.ui.nx.NxToggle
import hivens.ui.puppet.PuppetClick
import hivens.ui.theme.NxTheme
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Compact version panel for a mirror instance: the installed build with its
 * channel, the mirror's latest-build line, a manual check, follow-latest, and
 * the available-update banner. A green update applies right here (progress in
 * the window's footer strip via [onOpState]); an amber one routes to the
 * versions screen where the full diff and the snapshot story live. The build
 * list and restore points themselves moved to that screen -- a narrow settings
 * pane is no place for a history table.
 */
@Composable
internal fun PackVersionSection(
    pack: PackInstance,
    onInstanceChange: (PackInstance) -> Unit,
    onOpenVersions: () -> Unit = {},
    onOpState: (PackSettingsOp) -> Unit = {},
) {
    val s = LocalStrings.current
    val colors = NxTheme.colors
    val updater: PackUpdater = koinInject()
    val repo: IPackRepository = koinInject()
    val mirror: IMirrorPackClient = koinInject()
    val scope = rememberCoroutineScope()

    var busy by remember(pack.id) { mutableStateOf(false) }
    var check by remember(pack.id) { mutableStateOf<UpdateCheck?>(null) }

    val current = pack.pinnedPackVersion ?: pack.packRef.version ?: "?"

    // Both reads are best-effort: offline settings stay usable, the lines just
    // do not render.
    val installedChannel by produceState<VersionChannel?>(null, pack.id, current) {
        value = runCatching { mirror.fetchManifestVersion(pack.packRef.id, current).versionChannel }.getOrNull()
    }
    val latestLine by produceState<String?>(null, pack.id) {
        value = runCatching {
            val summary = mirror.fetchSummary(pack.packRef.id)
            val built = formatBuildTimestamp(summary.latestBuiltAt)
            if (built != null) s.packVersionLatestBuilt(summary.latestPackVersion, built) else null
        }.getOrNull()
    }

    fun runCheck() {
        if (busy) return
        scope.launch {
            busy = true
            check = runCatching { updater.checkForUpdate(pack) }
                .onFailure { onOpState(PackSettingsOp.Failed(it.message ?: s.packVersionCheckFailed)) }
                .getOrNull()
            busy = false
        }
    }

    fun applyLatest() {
        if (busy) return
        scope.launch {
            busy = true
            onOpState(PackSettingsOp.Running(0, 0, ""))
            runCatching {
                updater.applyUpdate(pack, null) { c, t, p ->
                    onOpState(PackSettingsOp.Running(c, t, p.substringAfterLast('/')))
                }
            }.onSuccess { outcome ->
                repo.get(pack.id)?.let(onInstanceChange)
                check = null
                onOpState(PackSettingsOp.Done((outcome as? UpdateOutcome.Applied)?.toVersion ?: current))
            }.onFailure {
                onOpState(PackSettingsOp.Failed(it.message ?: s.packVersionCheckFailed))
            }
            busy = false
        }
    }

    NxSection(s.packVersionSection) {
        Row(
            modifier          = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(s.packVersionInstalled, style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(current, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
                    installedChannel?.let { ChannelChip(it) }
                }
                latestLine?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PuppetClick("packSettings.version.check") { runCheck() }
                NxButton(
                    label   = s.packVersionCheck,
                    onClick = { runCheck() },
                    style   = NxButtonStyle.Secondary,
                    enabled = !busy,
                    compact = true,
                )
                PuppetClick("packSettings.version.all") { onOpenVersions() }
                NxButton(
                    label   = s.packVersionsAllVersions,
                    onClick = onOpenVersions,
                    style   = NxButtonStyle.Tertiary,
                    compact = true,
                )
            }
        }
        NxToggle(
            s.packVersionFollowLatest,
            pack.followLatest,
            description = s.packVersionFollowLatestDesc,
            icon = NxIcon.Sync,
        ) { enabled ->
            val updated = pack.copy(followLatest = enabled)
            onInstanceChange(updated)
            scope.launch { repo.put(updated) }
        }
        // Up-to-date is a quiet one-liner inside the section, not a banner block.
        if (check == UpdateCheck.UpToDate) {
            Text(s.packVersionUpToDate, style = MaterialTheme.typography.bodySmall, color = colors.success)
        }
    }

    // Only an actual available update earns a prominent banner. Green applies in
    // place; amber (structural) opens the versions screen where the full diff,
    // the snapshot notice and the confirm flow live.
    (check as? UpdateCheck.Available)?.let { c ->
        NxCalloutBanner(
            title = s.packVersionAvailable(c.toVersion),
            body = if (c.compat.isSafe) s.packVersionSafe else s.packVersionNeedsCare,
            tone = if (c.compat.isSafe) NxCalloutTone.Info else NxCalloutTone.Warning,
        ) {
            if (c.hasFileChanges) {
                Text(
                    s.packVersionsPlanCounts(c.plan.toAdd.size, c.plan.toUpdate.size, c.plan.toDelete.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary,
                )
            }
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (c.compat.isSafe) {
                    PuppetClick("packSettings.version.updateNow") { applyLatest() }
                    NxButton(s.packVersionUpdateNow, onClick = { applyLatest() }, enabled = !busy, compact = true)
                } else {
                    NxButton(s.packVersionsAllVersions, onClick = onOpenVersions, compact = true)
                }
            }
        }
    }
}

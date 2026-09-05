package hivens.ui.screens.detail.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import hivens.core.data.PackInstance
import hivens.core.update.PackUpdateStatus
import hivens.core.update.PackUpdateStatusHub
import hivens.core.update.PackUpdater
import hivens.core.update.UpdateCheck
import hivens.core.update.UpdateDirection
import hivens.core.update.UpdateOutcome
import hivens.core.update.VersionChannel
import hivens.launcher.PackOperation
import hivens.launcher.PackOperationKind
import hivens.launcher.PackOperationPhase
import hivens.launcher.PackOperationService
import hivens.ui.components.ChannelChip
import hivens.ui.components.formatBuildTimestamp
import hivens.ui.components.rememberRunningPackGuard
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
 * the window's footer strip, narrated from [operation]); an amber one routes to
 * the versions screen where the full diff and the snapshot story live. The build
 * list and restore points themselves moved to that screen -- a narrow settings
 * pane is no place for a history table.
 */
@Composable
internal fun PackVersionSection(
    pack: PackInstance,
    operation: PackOperation?,
    save: (PackInstance) -> Unit,
    onOpenVersions: () -> Unit = {},
    onNotice: (String?) -> Unit = {},
) {
    val s = LocalStrings.current
    val colors = NxTheme.colors
    val updater: PackUpdater = koinInject()
    val mirror: IMirrorPackClient = koinInject()
    val hub: PackUpdateStatusHub = koinInject()
    val operations: PackOperationService = koinInject()
    // A check is cheap and belongs to the window; an apply outlives it and is the
    // registry's. See applyLatest.
    val scope = rememberCoroutineScope()

    var checking by remember(pack.id) { mutableStateOf(false) }
    var check by remember(pack.id) { mutableStateOf<UpdateCheck?>(null) }
    val busy = checking || operation?.isRunning == true

    val current = pack.pinnedPackVersion ?: pack.packRef.version ?: "?"

    // The banner offers a build that is now installed. The instance record itself
    // is refreshed a level up, by whoever holds it -- see PackDetailScreen.
    LaunchedEffect(operation?.phase) {
        if (operation?.phase is PackOperationPhase.Updated) check = null
    }

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
            checking = true
            onNotice(null)
            // Explicit action: go past the cache. Answering "check now" out of a
            // four-minute-old entry is what made this button feel like a coin flip.
            check = runCatching { updater.checkForUpdate(pack, forceRefresh = true) }
                .onFailure { onNotice(s.packVersionsFailed(it.message ?: s.packVersionCheckFailed)) }
                .getOrNull()
            // Feed the shared hub so the ambient badges (card, hero) reflect what
            // this manual check just learned.
            when (val c = check) {
                is UpdateCheck.Available -> hub.report(pack.id, PackUpdateStatus.Pending(c.toVersion, c.direction, c.compat))
                UpdateCheck.UpToDate -> hub.report(pack.id, PackUpdateStatus.UpToDate)
                null -> Unit
            }
            checking = false
        }
    }

    // An apply rewrites the instance's files, so it is warned about when that
    // instance is the one currently playing.
    val runningGuard = rememberRunningPackGuard(pack.id)

    fun applyLatest() {
        if (busy) return
        onNotice(null)
        // The registry's scope, not the composition's: this window is dismissed by
        // Esc, by the scrim and by its own close button, and on the composition
        // scope every one of those cancels the apply mid-flight. The rollback does
        // run (it is blocking, so cancellation cannot stop it) and the files come
        // back, but the user is left with an update that silently did not happen
        // and a snapshot nobody cleans up. The same lesson is already written down
        // on LauncherController.setOptionalModsAsync.
        operations.start(pack, PackOperationKind.Update) { progress ->
            val outcome = updater.applyUpdate(pack, null) { c, t, p ->
                progress(c, t, p.substringAfterLast('/'))
            }
            // Reported from inside the operation, not from the section: the badges
            // this feeds have to agree with reality whether or not the window that
            // started the apply is still open.
            hub.report(pack.id, PackUpdateStatus.UpToDate)
            PackOperationPhase.Updated((outcome as? UpdateOutcome.Applied)?.toVersion ?: current)
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
        ) { enabled -> save(pack.copy(followLatest = enabled)) }
        // Up-to-date is a quiet one-liner inside the section, not a banner block.
        if (check == UpdateCheck.UpToDate) {
            Text(s.packVersionUpToDate, style = MaterialTheme.typography.bodySmall, color = colors.success)
        }
    }

    // Only an actual available update earns a prominent banner. Green applies in
    // place; amber (structural) opens the versions screen where the full diff,
    // the snapshot notice and the confirm flow live.
    (check as? UpdateCheck.Available)?.let { c ->
        val isRollback = c.direction == UpdateDirection.Older
        NxCalloutBanner(
            // A mirror-side rollback of latest arrives through the same check as
            // a release; calling it "available build" would be true but reads as
            // an update, and the target is older than what is installed.
            title = if (isRollback) s.packVersionRolledBack(c.toVersion) else s.packVersionAvailable(c.toVersion),
            body = if (c.compat.isSafe) s.packVersionSafe else s.packVersionNeedsCare,
            tone = if (c.compat.isSafe && !isRollback) NxCalloutTone.Info else NxCalloutTone.Warning,
        ) {
            // No line at all when the plan is absent: the source could not say
            // what would change without handing over the whole pack, and a
            // count of zero would read as "nothing changes".
            c.plan?.takeIf { !it.isEmpty }?.let { plan ->
                Text(
                    s.packVersionsPlanCounts(plan.toAdd.size, plan.toUpdate.size, plan.toDelete.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary,
                )
            }
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (c.compat.isSafe) {
                    PuppetClick("packSettings.version.updateNow") { runningGuard.run(::applyLatest) }
                    NxButton(
                        label   = if (isRollback) s.packVersionSwitchNow else s.packVersionUpdateNow,
                        onClick = { runningGuard.run(::applyLatest) },
                        enabled = !busy,
                        compact = true,
                    )
                } else {
                    NxButton(s.packVersionsAllVersions, onClick = onOpenVersions, compact = true)
                }
            }
        }
    }

    runningGuard.Dialog()
}

package hivens.ui.screens.detail.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.PackInstance
import hivens.core.update.PackSnapshot
import hivens.core.update.UpdateCheck
import hivens.launcher.update.PackUpdateService
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.nx.NxButton
import hivens.ui.nx.NxButtonStyle
import hivens.ui.nx.NxCalloutBanner
import hivens.ui.nx.NxCalloutTone
import hivens.ui.nx.NxRow
import hivens.ui.nx.NxSection
import hivens.ui.nx.NxToggle
import hivens.ui.theme.NxTheme
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val SNAPSHOT_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/**
 * Version + updates for a mirror instance: the installed build with a manual
 * check, follow-latest vs pinned, the compat-graded available update, the
 * retained build list for a switch/rollback, and the byte-exact restore points.
 * All actions run on the app-scoped [PackUpdateService]; a committed instance
 * flows back through [onInstanceChange].
 */
@Composable
internal fun PackVersionSection(pack: PackInstance, onInstanceChange: (PackInstance) -> Unit) {
    val s = LocalStrings.current
    val colors = NxTheme.colors
    val updater: PackUpdateService = koinInject()
    val repo: IPackRepository = koinInject()
    val scope = rememberCoroutineScope()

    var busy by remember(pack.id) { mutableStateOf(false) }
    var check by remember(pack.id) { mutableStateOf<UpdateCheck?>(null) }
    var message by remember(pack.id) { mutableStateOf<String?>(null) }
    var versions by remember(pack.id) { mutableStateOf<List<String>>(emptyList()) }
    var snapshots by remember(pack.id) { mutableStateOf<List<PackSnapshot>>(emptyList()) }

    val current = pack.pinnedPackVersion ?: pack.packRef.version ?: "?"

    fun refresh() {
        scope.launch {
            versions = runCatching { updater.availableVersions(pack) }.getOrDefault(versions)
            snapshots = runCatching { updater.listSnapshots(pack) }.getOrDefault(snapshots)
        }
    }

    LaunchedEffect(pack.id) {
        versions = runCatching { updater.availableVersions(pack) }.getOrDefault(emptyList())
        snapshots = runCatching { updater.listSnapshots(pack) }.getOrDefault(emptyList())
    }

    fun runCheck() {
        if (busy) return
        scope.launch {
            busy = true
            message = null
            check = runCatching { updater.checkForUpdate(pack) }.getOrElse { message = s.packVersionCheckFailed; null }
            busy = false
        }
    }

    fun apply(target: String?) {
        if (busy) return
        scope.launch {
            busy = true
            message = null
            runCatching { updater.applyUpdate(pack, target, null) }
                .onSuccess { repo.get(pack.id)?.let(onInstanceChange); check = null; refresh() }
                .onFailure { message = it.message ?: s.packVersionCheckFailed }
            busy = false
        }
    }

    fun restore(id: String) {
        if (busy) return
        scope.launch {
            busy = true
            message = null
            runCatching { updater.rollback(pack, id) }
                .onSuccess { onInstanceChange(it); check = null; refresh() }
                .onFailure { message = it.message ?: s.packVersionCheckFailed }
            busy = false
        }
    }

    NxSection(s.packVersionSection) {
        NxRow(title = s.packVersionInstalled, subtitle = current) {
            NxButton(
                label = if (busy) s.packVersionWorking else s.packVersionCheck,
                onClick = { runCheck() },
                style = NxButtonStyle.Secondary,
                enabled = !busy,
                compact = true,
            )
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
    }

    when (val c = check) {
        UpdateCheck.UpToDate -> NxCalloutBanner(
            body = s.packVersionUpToDate,
            tone = NxCalloutTone.Info,
            icon = NxIcon.CheckCircle,
        )
        is UpdateCheck.Available -> NxCalloutBanner(
            title = s.packVersionAvailable(c.toVersion),
            body = if (c.compat.isSafe) s.packVersionSafe else s.packVersionNeedsCare,
            tone = if (c.compat.isSafe) NxCalloutTone.Info else NxCalloutTone.Warning,
        ) {
            if (c.hasFileChanges) {
                Text(
                    "+${c.plan.toAdd.size}   ~${c.plan.toUpdate.size}   -${c.plan.toDelete.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary,
                )
            }
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NxButton(s.packVersionUpdateNow, onClick = { apply(null) }, enabled = !busy, compact = true)
            }
        }
        null -> Unit
    }

    message?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = colors.error, modifier = Modifier.padding(horizontal = 4.dp))
    }

    if (versions.size > 1) {
        NxSection(s.packVersionOtherBuilds) {
            versions.forEach { v ->
                val isCurrent = v == current
                NxRow(title = v, subtitle = if (isCurrent) s.packVersionCurrentTag else null) {
                    if (!isCurrent) {
                        NxButton(
                            s.packVersionSwitch,
                            onClick = { apply(v) },
                            style = NxButtonStyle.Tertiary,
                            enabled = !busy,
                            compact = true,
                        )
                    }
                }
            }
        }
    }

    if (snapshots.isNotEmpty()) {
        NxSection(s.packVersionSnapshots) {
            snapshots.forEach { snap ->
                val whenLabel = Instant.ofEpochMilli(snap.createdAtEpoch).atZone(ZoneId.systemDefault()).format(SNAPSHOT_TIME)
                NxRow(title = whenLabel, subtitle = snap.fromVersion) {
                    NxButton(
                        s.packVersionRestore,
                        onClick = { restore(snap.id) },
                        style = NxButtonStyle.Tertiary,
                        enabled = !busy,
                        compact = true,
                    )
                }
            }
            Text(s.packVersionSnapshotsHint, style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
        }
    }
}

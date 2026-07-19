package hivens.ui.screens.detail.versions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import hivens.core.api.dto.smrt.SmrtManifestBuild
import hivens.core.api.dto.smrt.SmrtModEntry
import hivens.core.api.dto.smrt.SmrtPackManifest
import hivens.core.api.interfaces.IMirrorPackClient
import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.PackInstance
import hivens.core.smrt.DiffEntry
import hivens.core.smrt.DiffKind
import hivens.core.smrt.ModIconResolver
import hivens.core.smrt.PackVersionDiff
import hivens.core.smrt.groupRebuildRuns
import hivens.core.update.CompatChange
import hivens.core.update.PackSnapshot
import hivens.core.update.PackUpdateStatus
import hivens.core.update.PackUpdateStatusHub
import hivens.core.update.PackUpdater
import hivens.core.update.UpdateCheck
import hivens.core.update.VersionChannel
import hivens.ui.components.ChannelChip
import hivens.ui.components.DestructiveConfirmDialog
import hivens.ui.components.formatBuildTimestamp
import hivens.ui.i18n.LocalStrings
import hivens.ui.nx.NxButton
import hivens.ui.nx.NxButtonStyle
import hivens.ui.nx.NxCalloutBanner
import hivens.ui.nx.NxCalloutTone
import hivens.ui.icons.NxIcon
import hivens.ui.nx.NxChoiceChip
import hivens.ui.nx.NxDiffRow
import hivens.ui.nx.NxDiffRowKind
import hivens.ui.nx.NxIconButton
import hivens.ui.nx.NxMetaChip
import hivens.ui.nx.NxMetaChipTone
import hivens.ui.nx.NxRow
import hivens.ui.nx.NxSection
import hivens.ui.nx.NxVerticalScrollbar
import hivens.ui.puppet.PuppetClick
import hivens.ui.puppet.PuppetScreen
import hivens.ui.screens.CenteredProgress
import hivens.ui.surface.NxSurface
import hivens.ui.surface.NxSurfaceLevel
import hivens.ui.theme.LocalStyle
import hivens.ui.theme.NxTheme
import hivens.ui.theme.decorativeColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val BUILD_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/** Which base the changelog diff compares the selected build against. */
private enum class DiffBase { Previous, Installed }

/** Lifecycle of a switch/restore run, rendered in the layout-stable status row. */
private sealed interface ApplyState {
    data object Idle : ApplyState
    data class Running(val current: Int, val total: Int, val path: String) : ApplyState
    data class Done(val version: String) : ApplyState
    data class Failed(val reason: String) : ApplyState
}

/**
 * Full-screen version manager for a mirror pack instance: the retained build
 * list on the left (channel, date, counts; label-only rebuild runs collapsed),
 * the selected build's changelog on the right -- a client-side manifest diff
 * against the previous build or the installed one -- plus the switch action
 * gated by the compat preview, and the instance's restore points.
 */
@Composable
fun PackVersionsScreen(instanceId: String, onBack: () -> Unit) {
    PuppetScreen("PackVersions.$instanceId")
    PuppetClick("packVersions.back") { onBack() }

    val repo: IPackRepository = koinInject()
    val updater: PackUpdater = koinInject()
    val mirror: IMirrorPackClient = koinInject()
    val icons: ModIconResolver = koinInject()
    val hub: PackUpdateStatusHub = koinInject()
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()

    var instance by remember(instanceId) { mutableStateOf<PackInstance?>(null) }
    var resolved by remember(instanceId) { mutableStateOf(false) }
    LaunchedEffect(instanceId) {
        instance = repo.observe().firstOrNull()?.firstOrNull { it.id == instanceId } ?: repo.get(instanceId)
        resolved = true
    }

    if (!resolved) {
        CenteredProgress(Modifier.fillMaxSize())
        return
    }
    val pack = instance ?: run { onBack(); return }
    val installedVersion = pack.pinnedPackVersion ?: pack.packRef.version

    var builds by remember(pack.id) { mutableStateOf<List<SmrtManifestBuild>?>(null) }
    var loadFailed by remember(pack.id) { mutableStateOf(false) }
    var loadTick by remember(pack.id) { mutableIntStateOf(0) }
    var selected by remember(pack.id) { mutableStateOf<SmrtManifestBuild?>(null) }
    var snapshots by remember(pack.id) { mutableStateOf<List<PackSnapshot>>(emptyList()) }
    var applyState by remember(pack.id) { mutableStateOf<ApplyState>(ApplyState.Idle) }
    var confirmTarget by remember(pack.id) { mutableStateOf<UpdateCheck.Available?>(null) }

    fun refreshInstance() {
        scope.launch { repo.get(pack.id)?.let { instance = it } }
    }

    LaunchedEffect(pack.id, loadTick) {
        loadFailed = false
        builds = null
        builds = runCatching { updater.availableBuilds(pack) }
            .onFailure { loadFailed = true }
            .getOrNull()
        selected = builds?.let { list -> list.firstOrNull { it.versionNumber == installedVersion } ?: list.firstOrNull() }
        snapshots = runCatching { updater.listSnapshots(pack) }.getOrDefault(emptyList())
    }

    val style = LocalStyle.current
    NxSurface(
        level    = NxSurfaceLevel.Raised,
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .clip(RoundedCornerShape(style.cardCorner)),
    ) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(Modifier.weight(1f)) {
                BuildListPane(
                    builds           = builds,
                    loadFailed       = loadFailed,
                    installedVersion = installedVersion,
                    latest           = builds?.firstOrNull()?.versionNumber,
                    selected         = selected,
                    onSelect         = { selected = it },
                    onRetry          = { loadTick++ },
                    modifier         = Modifier.width(340.dp).fillMaxHeight(),
                )
                Spacer(Modifier.width(20.dp))
                Column(
                    modifier            = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    val sel = selected
                    if (sel != null && builds != null) {
                        BuildDetailPane(
                            pack             = pack,
                            builds           = builds!!,
                            build            = sel,
                            installedVersion = installedVersion,
                            updater          = updater,
                            mirror           = mirror,
                            icons            = icons,
                            busy             = applyState is ApplyState.Running,
                            onSwitch         = { preview ->
                                if (preview.compat.isSafe) {
                                    runSwitch(scope, updater, repo, hub, pack, sel.versionNumber,
                                        onState = { applyState = it }, onDone = {
                                            refreshInstance()
                                            snapshots = runCatching { updater.listSnapshots(pack) }.getOrDefault(snapshots)
                                        })
                                } else {
                                    confirmTarget = preview
                                }
                            },
                        )
                        if (snapshots.isNotEmpty()) {
                            SnapshotsSection(
                                snapshots = snapshots,
                                busy      = applyState is ApplyState.Running,
                                onRestore = { snap ->
                                    runRestore(scope, updater, repo, hub, pack, snap.id,
                                        onState = { applyState = it }, onDone = {
                                            refreshInstance()
                                            snapshots = runCatching { updater.listSnapshots(pack) }.getOrDefault(snapshots)
                                        })
                                },
                            )
                        }
                    } else if (builds != null && builds!!.isEmpty() && !loadFailed) {
                        Text(s.packVersionsLoadError, color = NxTheme.colors.textSecondary)
                    }
                }
            }
            StatusRow(applyState)
        }
        // Corner close mirrors the settings window: the screen is a route (back
        // works too), but a transient-feeling surface earns an explicit exit.
        // Declared after the panes so it stays on top of the detail header.
        PuppetClick("packVersions.close") { onBack() }
        NxIconButton(
            icon               = NxIcon.Close,
            contentDescription = s.packSettingsClose,
            onClick            = onBack,
            modifier           = Modifier.align(Alignment.TopEnd).padding(10.dp),
        )
    }

    confirmTarget?.let { preview ->
        val conflictLine = if (preview.plan.conflicts.isNotEmpty()) "\n" + s.packVersionsConflicts(preview.plan.conflicts.size) else ""
        DestructiveConfirmDialog(
            title        = s.packVersionsConfirmTitle,
            body         = s.packVersionsConfirmBody(installedVersion ?: "?", preview.toVersion) + "\n" +
                s.packVersionsPlanCounts(preview.plan.toAdd.size, preview.plan.toUpdate.size, preview.plan.toDelete.size) +
                conflictLine,
            confirmLabel = s.packVersionSwitch,
            onConfirm    = {
                val target = preview.toVersion
                confirmTarget = null
                runSwitch(scope, updater, repo, hub, pack, target,
                    onState = { applyState = it }, onDone = {
                        refreshInstance()
                        snapshots = runCatching { updater.listSnapshots(pack) }.getOrDefault(snapshots)
                    })
            },
            onDismiss    = { confirmTarget = null },
        )
    }
}

// ─── Actions ─────────────────────────────────────────────────────────────────

private fun runSwitch(
    scope: kotlinx.coroutines.CoroutineScope,
    updater: PackUpdater,
    repo: IPackRepository,
    hub: PackUpdateStatusHub,
    pack: PackInstance,
    targetVersion: String,
    onState: (ApplyState) -> Unit,
    onDone: () -> Unit,
) {
    scope.launch {
        onState(ApplyState.Running(0, 0, ""))
        runCatching {
            val fresh = repo.get(pack.id) ?: pack
            updater.applyUpdate(fresh, targetVersion) { current, total, path ->
                onState(ApplyState.Running(current, total, path.substringAfterLast('/')))
            }
        }.onSuccess {
            // The user just handled this instance's version by hand: clear any
            // stale Pending so the ambient badges agree with reality. Quiet on
            // purpose -- the result already shows in this screen's status row.
            hub.report(pack.id, PackUpdateStatus.UpToDate)
            onState(ApplyState.Done(targetVersion))
            onDone()
        }.onFailure {
            onState(ApplyState.Failed(it.message ?: it.toString()))
        }
    }
}

private fun runRestore(
    scope: kotlinx.coroutines.CoroutineScope,
    updater: PackUpdater,
    repo: IPackRepository,
    hub: PackUpdateStatusHub,
    pack: PackInstance,
    snapshotId: String,
    onState: (ApplyState) -> Unit,
    onDone: () -> Unit,
) {
    scope.launch {
        onState(ApplyState.Running(0, 0, ""))
        runCatching {
            val fresh = repo.get(pack.id) ?: pack
            updater.rollback(fresh, snapshotId)
        }.onSuccess {
            hub.report(pack.id, PackUpdateStatus.UpToDate)
            onState(ApplyState.Done(it.pinnedPackVersion ?: ""))
            onDone()
        }.onFailure {
            onState(ApplyState.Failed(it.message ?: it.toString()))
        }
    }
}

// ─── Left pane: build list ───────────────────────────────────────────────────

@Composable
private fun BuildListPane(
    builds: List<SmrtManifestBuild>?,
    loadFailed: Boolean,
    installedVersion: String?,
    latest: String?,
    selected: SmrtManifestBuild?,
    onSelect: (SmrtManifestBuild) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    when {
        loadFailed -> Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            NxCalloutBanner(body = s.packVersionsLoadError, tone = NxCalloutTone.Error) {
                NxButton(s.packVersionsRetry, onClick = onRetry, style = NxButtonStyle.Secondary, compact = true)
            }
        }
        builds == null -> Box(modifier, contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = NxTheme.colors.primary.copy(alpha = 0.6f), strokeWidth = 2.dp, modifier = Modifier.size(26.dp))
        }
        else -> {
            val runs = remember(builds) { groupRebuildRuns(builds) }
            // The run hiding the installed build starts expanded -- the "current"
            // marker must be findable in the list without digging.
            var expandedRuns by remember(builds) {
                mutableStateOf(
                    setOfNotNull(
                        runs.firstOrNull { run -> run.drop(1).any { it.versionNumber == installedVersion } }
                            ?.first()?.versionNumber,
                    ),
                )
            }
            val listState = rememberLazyListState()
            val hover = remember { MutableInteractionSource() }
            val hovered by hover.collectIsHoveredAsState()
            Box(modifier.hoverable(hover)) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    runs.forEach { run ->
                        val head = run.first()
                        item(key = head.versionNumber) {
                            BuildRow(
                                build       = head,
                                isInstalled = head.versionNumber == installedVersion,
                                isLatest    = head.versionNumber == latest,
                                isSelected  = selected?.versionNumber == head.versionNumber,
                                rebuildTail = run.size - 1,
                                tailShown   = head.versionNumber in expandedRuns,
                                onToggleRun = {
                                    expandedRuns = if (head.versionNumber in expandedRuns) expandedRuns - head.versionNumber
                                    else expandedRuns + head.versionNumber
                                },
                                onClick     = { onSelect(head) },
                            )
                        }
                        if (head.versionNumber in expandedRuns) {
                            run.drop(1).forEach { member ->
                                item(key = member.versionNumber) {
                                    Box(Modifier.padding(start = 18.dp)) {
                                        BuildRow(
                                            build       = member,
                                            isInstalled = member.versionNumber == installedVersion,
                                            isLatest    = member.versionNumber == latest,
                                            isSelected  = selected?.versionNumber == member.versionNumber,
                                            rebuildTail = 0,
                                            tailShown   = false,
                                            onToggleRun = {},
                                            onClick     = { onSelect(member) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                NxVerticalScrollbar(
                    adapter  = rememberScrollbarAdapter(listState),
                    revealed = hovered || listState.isScrollInProgress,
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                )
            }
        }
    }
}

@Composable
private fun BuildRow(
    build: SmrtManifestBuild,
    isInstalled: Boolean,
    isLatest: Boolean,
    isSelected: Boolean,
    rebuildTail: Int,
    tailShown: Boolean,
    onToggleRun: () -> Unit,
    onClick: () -> Unit,
) {
    val s = LocalStrings.current
    val colors = NxTheme.colors
    val shape = RoundedCornerShape(LocalStyle.current.cardCorner)
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (isSelected) colors.primary.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text       = build.versionNumber,
                style      = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color      = colors.textPrimary,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis,
                modifier   = Modifier.weight(1f, fill = false),
            )
            ChannelChip(build.channel)
            if (isInstalled) NxMetaChip(s.packVersionCurrentTag, tone = NxMetaChipTone.Success)
            else if (isLatest) NxMetaChip(s.packVersionsLatestTag, tone = NxMetaChipTone.Surface)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text  = listOfNotNull(formatBuildTimestamp(build.datePublished), s.packVersionsCounts(build.modsCount, build.assetsCount)).joinToString("   "),
                style = MaterialTheme.typography.labelSmall,
                color = colors.textSecondary,
            )
        }
        if (rebuildTail > 0) {
            Text(
                text     = s.packVersionsRebuilds(rebuildTail),
                style    = MaterialTheme.typography.labelSmall,
                color    = if (tailShown) colors.primary else colors.textSecondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onToggleRun)
                    .padding(vertical = 2.dp, horizontal = 2.dp),
            )
        }
    }
}

// ─── Right pane: build detail / changelog ────────────────────────────────────

@Composable
private fun BuildDetailPane(
    pack: PackInstance,
    builds: List<SmrtManifestBuild>,
    build: SmrtManifestBuild,
    installedVersion: String?,
    updater: PackUpdater,
    mirror: IMirrorPackClient,
    icons: ModIconResolver,
    busy: Boolean,
    onSwitch: (UpdateCheck.Available) -> Unit,
) {
    val s = LocalStrings.current
    val colors = NxTheme.colors
    val isInstalled = build.versionNumber == installedVersion

    // Compat preview for a would-be switch; refreshed when the selection or the
    // installed build changes. Null while loading or for the installed build.
    val preview by produceState<UpdateCheck?>(null, build.versionNumber, installedVersion) {
        value = null
        if (!isInstalled) {
            value = runCatching { updater.previewSwitch(pack, build.versionNumber) }.getOrNull()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // End inset keeps the title row clear of the card's corner close button.
        Row(
            modifier              = Modifier.fillMaxWidth().padding(end = 40.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text       = build.versionNumber,
                style      = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color      = colors.textPrimary,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis,
                modifier   = Modifier.weight(1f, fill = false),
            )
            ChannelChip(build.channel)
            if (isInstalled) NxMetaChip(s.packVersionCurrentTag, tone = NxMetaChipTone.Success)
        }
        formatBuildTimestamp(build.datePublished)?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
        }

        when {
            isInstalled -> Unit
            preview == null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(color = colors.primary.copy(alpha = 0.5f), strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
            }
            preview is UpdateCheck.Available -> {
                val p = preview as UpdateCheck.Available
                NxCalloutBanner(
                    title = s.packVersionsPlanCounts(p.plan.toAdd.size, p.plan.toUpdate.size, p.plan.toDelete.size),
                    body  = (if (p.compat.isSafe) s.packVersionSafe else s.packVersionNeedsCare) +
                        (if (p.plan.conflicts.isNotEmpty()) "\n" + s.packVersionsConflicts(p.plan.conflicts.size) else ""),
                    tone  = if (p.compat.isSafe) NxCalloutTone.Info else NxCalloutTone.Warning,
                ) {
                    Row {
                        PuppetClick("packVersions.switch.${build.versionNumber}") { onSwitch(p) }
                        NxButton(
                            label   = s.packVersionsSwitchTo,
                            onClick = { onSwitch(p) },
                            enabled = !busy,
                            compact = true,
                        )
                    }
                }
            }
        }

        DiffSection(pack, builds, build, installedVersion, mirror, icons)
    }
}

@Composable
private fun DiffSection(
    pack: PackInstance,
    builds: List<SmrtManifestBuild>,
    build: SmrtManifestBuild,
    installedVersion: String?,
    mirror: IMirrorPackClient,
    icons: ModIconResolver,
) {
    val s = LocalStrings.current
    val colors = NxTheme.colors
    var base by remember(build.versionNumber) { mutableStateOf(DiffBase.Previous) }

    // Previous distinct-content build: skip same-fingerprint rebuild siblings so
    // "vs previous" answers "what did this build change", not "same as the rebuild".
    val previous = remember(builds, build) {
        val idx = builds.indexOfFirst { it.versionNumber == build.versionNumber }
        if (idx < 0) null
        else builds.drop(idx + 1).firstOrNull { candidate ->
            build.fingerprint == null || candidate.fingerprint == null || candidate.fingerprint != build.fingerprint
        }
    }

    val isInstalled = build.versionNumber == installedVersion
    val baseVersion = when (base) {
        DiffBase.Previous -> previous?.versionNumber
        DiffBase.Installed -> installedVersion
    }

    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        NxChoiceChip(s.packVersionsDiffVsPrevious, selected = base == DiffBase.Previous) { base = DiffBase.Previous }
        NxChoiceChip(
            label    = s.packVersionsDiffVsInstalled,
            selected = base == DiffBase.Installed,
            enabled  = !isInstalled && installedVersion != null,
        ) { base = DiffBase.Installed }
    }

    when {
        base == DiffBase.Previous && previous == null ->
            Text(s.packVersionsFirstBuild, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        baseVersion == null ->
            Text(s.packVersionsFirstBuild, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        baseVersion == build.versionNumber ->
            Text(s.packVersionsIdentical, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        else -> {
            val diff by produceState<Result<PackVersionDiff>?>(null, build.versionNumber, baseVersion) {
                value = null
                value = runCatching {
                    withContext(Dispatchers.IO) {
                        val fromManifest = mirror.fetchManifestVersion(pack.packRef.id, baseVersion)
                        val toManifest = mirror.fetchManifestVersion(pack.packRef.id, build.versionNumber)
                        PackVersionDiff.compute(fromManifest, toManifest)
                    }
                }
            }
            when (val result = diff) {
                null -> CircularProgressIndicator(color = colors.primary.copy(alpha = 0.5f), strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                else -> result.fold(
                    onSuccess = { DiffBody(it, icons) },
                    onFailure = {
                        NxCalloutBanner(body = s.packVersionsFailed(it.message ?: it.toString()), tone = NxCalloutTone.Error)
                    },
                )
            }
        }
    }
}

@Composable
private fun DiffBody(diff: PackVersionDiff, icons: ModIconResolver) {
    val s = LocalStrings.current
    val colors = NxTheme.colors

    if (diff.identicalContent && diff.minecraft == null && diff.loader == null && diff.java == null) {
        Text(s.packVersionsIdentical, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        return
    }

    val packChanges = listOfNotNull(
        diff.minecraft?.let { "Minecraft: ${it.from} → ${it.to}" },
        diff.loader?.let { "${it.from} → ${it.to}" },
        diff.java?.let { "Java: ${it.from} → ${it.to}" },
    )
    if (packChanges.isNotEmpty()) {
        NxSection(s.packVersionsSectionPack) {
            packChanges.forEach { line ->
                Text(line, style = MaterialTheme.typography.bodySmall, color = colors.textPrimary)
            }
        }
    }

    if (diff.identicalContent) {
        Text(s.packVersionsIdentical, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        return
    }

    if (diff.mods.isNotEmpty()) {
        NxSection(s.packVersionsSectionMods) {
            DiffGroup(diff.mods, icons)
        }
    }
    if (diff.assets.isNotEmpty()) {
        NxSection(s.packVersionsSectionAssets) {
            diff.assets.forEach { entry ->
                val subject = entry.to ?: entry.from
                NxDiffRow(
                    kind     = entry.kind.toRowKind(),
                    title    = subject?.dest ?: "?",
                    trailing = sizeLabel(entry.from?.sizeBytes, entry.to?.sizeBytes),
                )
            }
        }
    }
}

@Composable
private fun DiffGroup(entries: List<DiffEntry<SmrtModEntry>>, icons: ModIconResolver) {
    val s = LocalStrings.current
    val colors = NxTheme.colors
    val added = entries.filter { it.kind == DiffKind.Added }
    val updated = entries.filter { it.kind == DiffKind.Updated }
    val removed = entries.filter { it.kind == DiffKind.Removed }

    @Composable
    fun header(text: String) =
        Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = colors.textSecondary)

    if (added.isNotEmpty()) {
        header(s.packVersionsAdded(added.size))
        added.forEach { ModDiffRow(it, icons) }
    }
    if (updated.isNotEmpty()) {
        header(s.packVersionsUpdated(updated.size))
        updated.forEach { ModDiffRow(it, icons) }
    }
    if (removed.isNotEmpty()) {
        header(s.packVersionsRemoved(removed.size))
        removed.forEach { ModDiffRow(it, icons) }
    }
}

@Composable
private fun ModDiffRow(entry: DiffEntry<SmrtModEntry>, icons: ModIconResolver) {
    val subject = entry.to ?: entry.from ?: return
    val title = subject.display?.name?.takeIf { it.isNotBlank() } ?: subject.filename
    val subtitle = when (entry.kind) {
        DiffKind.Updated -> entry.from?.filename?.takeIf { it != entry.to?.filename }?.let { "$it → ${entry.to?.filename}" }
            ?: subject.filename.takeIf { it != title }
        else -> subject.filename.takeIf { it != title }
    }
    NxDiffRow(
        kind     = entry.kind.toRowKind(),
        title    = title,
        subtitle = subtitle,
        trailing = sizeLabel(entry.from?.sizeBytes, entry.to?.sizeBytes),
        leading  = { ModDiffIcon(subject, icons) },
    )
}

@Composable
private fun ModDiffIcon(entry: SmrtModEntry, icons: ModIconResolver) {
    val url by produceState<String?>(entry.display?.iconUrl, entry.filename) {
        if (value == null) value = runCatching { icons.resolve(entry) }.getOrNull()
    }
    val box = Modifier.size(24.dp).clip(RoundedCornerShape(6.dp))
    val current = url
    if (current != null) {
        AsyncImage(model = current, contentDescription = null, contentScale = ContentScale.Crop, modifier = box)
    } else {
        Box(box.background(NxTheme.colors.decorativeColor(entry.filename)), contentAlignment = Alignment.Center) {
            Text(
                text       = (entry.display?.name ?: entry.filename).firstOrNull()?.uppercase() ?: "?",
                style      = MaterialTheme.typography.labelSmall,
                color      = Color.White,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

// ─── Snapshots ───────────────────────────────────────────────────────────────

@Composable
private fun SnapshotsSection(
    snapshots: List<PackSnapshot>,
    busy: Boolean,
    onRestore: (PackSnapshot) -> Unit,
) {
    val s = LocalStrings.current
    NxSection(s.packVersionSnapshots) {
        snapshots.forEach { snap ->
            val whenLabel = runCatching {
                Instant.ofEpochMilli(snap.createdAtEpoch).atZone(ZoneId.systemDefault()).format(BUILD_TIME)
            }.getOrDefault(snap.id)
            NxRow(title = whenLabel, subtitle = snap.fromVersion) {
                NxButton(
                    label   = s.packVersionRestore,
                    onClick = { onRestore(snap) },
                    style   = NxButtonStyle.Tertiary,
                    enabled = !busy,
                    compact = true,
                )
            }
        }
        Text(s.packVersionSnapshotsHint, style = MaterialTheme.typography.labelSmall, color = NxTheme.colors.textSecondary)
    }
}

// ─── Status row ──────────────────────────────────────────────────────────────

/**
 * Layout-stable bottom strip for the in-progress/last operation (Rule 6: a
 * transient affordance lives in a reserved slot, it never reflows the panes).
 */
@Composable
private fun StatusRow(state: ApplyState) {
    val s = LocalStrings.current
    val colors = NxTheme.colors
    Box(Modifier.fillMaxWidth().height(34.dp).padding(top = 8.dp), contentAlignment = Alignment.CenterStart) {
        when (state) {
            ApplyState.Idle -> Unit
            is ApplyState.Running -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.total > 0) {
                    LinearProgressIndicator(
                        progress = { state.current.toFloat() / state.total },
                        modifier = Modifier.width(160.dp),
                        color    = colors.primary,
                    )
                    Text(
                        text  = s.packVersionsApplying(state.current, state.total, state.path),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.width(160.dp), color = colors.primary)
                }
            }
            is ApplyState.Done -> Text(
                text  = s.packVersionsApplied(state.version),
                style = MaterialTheme.typography.labelSmall,
                color = colors.success,
            )
            is ApplyState.Failed -> Text(
                text     = s.packVersionsFailed(state.reason),
                style    = MaterialTheme.typography.labelSmall,
                color    = colors.error,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ─── Helpers ─────────────────────────────────────────────────────────────────

private fun DiffKind.toRowKind(): NxDiffRowKind = when (this) {
    DiffKind.Added   -> NxDiffRowKind.Added
    DiffKind.Removed -> NxDiffRowKind.Removed
    DiffKind.Updated -> NxDiffRowKind.Updated
}

private fun sizeLabel(fromBytes: Long?, toBytes: Long?): String? = when {
    fromBytes != null && toBytes != null && fromBytes != toBytes -> "${humanSize(fromBytes)} → ${humanSize(toBytes)}"
    toBytes != null -> humanSize(toBytes)
    fromBytes != null -> humanSize(fromBytes)
    else -> null
}

private fun humanSize(bytes: Long): String = when {
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024      -> "${bytes / 1024} KB"
    else               -> "$bytes B"
}

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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
import com.mikepenz.markdown.m3.Markdown
import hivens.core.api.dto.smrt.SmrtBuildDiff
import hivens.core.update.PackBuild
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
import hivens.core.update.UpdateOutcome
import hivens.core.update.VersionChannel
import hivens.launcher.PackOperation
import hivens.launcher.PackOperationKind
import hivens.launcher.PackOperationPhase
import hivens.launcher.PackOperationService
import hivens.ui.components.ChannelChip
import hivens.ui.components.DestructiveConfirmDialog
import hivens.ui.components.formatBuildTime
import hivens.ui.components.formatBuildTimestamp
import hivens.ui.i18n.AppStrings
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.nx.CenteredProgress
import hivens.ui.nx.NxButton
import hivens.ui.nx.NxButtonStyle
import hivens.ui.nx.NxCalloutBanner
import hivens.ui.nx.NxCalloutTone
import hivens.ui.nx.NxChoiceChip
import hivens.ui.nx.NxDiffRow
import hivens.ui.nx.NxDiffRowKind
import hivens.ui.nx.NxIconButton
import hivens.ui.nx.NxMetaChip
import hivens.ui.nx.NxMetaChipTone
import hivens.ui.nx.NxRow
import hivens.ui.nx.NxSection
import hivens.ui.nx.NxVerticalScrollbar
import hivens.ui.components.rememberRunningPackGuard
import hivens.ui.puppet.PuppetClick
import hivens.ui.puppet.PuppetScreen
import hivens.ui.surface.NxSurface
import hivens.ui.surface.NxSurfaceLevel
import hivens.ui.theme.LocalStyle
import hivens.ui.theme.NxTheme
import hivens.ui.utils.humanSize
import hivens.ui.theme.decorativeColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.time.Instant

/** Which base the changelog diff compares the selected build against. */
private enum class DiffBase { Previous, Installed }

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
    val operations: PackOperationService = koinInject()
    val s = LocalStrings.current

    // Follows the registry rather than reading it once: an apply started in the
    // settings window this screen was opened from lands underneath it, and so does
    // the auto-update pass at startup. Both used to leave the "current" marker,
    // the switch banner and the restore points describing the build before them.
    val instances by remember { repo.observe() }.collectAsState()
    val inFlight by operations.operations.collectAsState()
    val pack = instances.firstOrNull { it.id == instanceId }

    // A finished outcome is read here and nowhere else once the screen goes; a
    // running operation is left alone, since leaving is not what ends it.
    DisposableEffect(instanceId) {
        onDispose { operations.dismiss(instanceId) }
    }

    if (pack == null) {
        // Deleted from under the screen: leave rather than paint a version manager
        // for an instance that is gone.
        LaunchedEffect(instanceId) { onBack() }
        CenteredProgress(Modifier.fillMaxSize())
        return
    }
    val installedVersion = pack.pinnedPackVersion ?: pack.packRef.version
    val operation = inFlight[instanceId]
    val busy = operation?.isRunning == true

    var builds by remember(pack.id) { mutableStateOf<List<PackBuild>?>(null) }
    var loadFailed by remember(pack.id) { mutableStateOf(false) }
    var loadTick by remember(pack.id) { mutableIntStateOf(0) }
    var selected by remember(pack.id) { mutableStateOf<PackBuild?>(null) }
    var snapshots by remember(pack.id) { mutableStateOf<List<PackSnapshot>>(emptyList()) }
    var confirmTarget by remember(pack.id) { mutableStateOf<UpdateCheck.Available?>(null) }

    // A switch and a rollback both rewrite the instance on disk. Warned about,
    // not blocked, when that instance is the one currently playing.
    val runningGuard = rememberRunningPackGuard(pack.id)

    // Both rewrite the whole instance and outlive this screen: Back, the corner
    // close and a click on a breadcrumb all dispose it, and on the composition's
    // scope every one of those cancelled the apply mid-flight -- the rollback put
    // the files back and the user was left with a switch that silently did not
    // happen. Same owner the settings window's apply already uses.
    fun doSwitch(targetVersion: String) {
        operations.start(pack, PackOperationKind.Update) { progress ->
            val fresh = repo.get(pack.id) ?: pack
            val outcome = updater.applyUpdate(fresh, targetVersion) { current, total, path ->
                progress(current, total, path.substringAfterLast('/'))
            }
            // The user just handled this instance's version by hand: clear any
            // stale Pending so the ambient badges agree with reality.
            hub.report(pack.id, PackUpdateStatus.UpToDate)
            // The build that ended up installed, which is not the asked-for one
            // when the apply found it already in place.
            PackOperationPhase.Updated((outcome as? UpdateOutcome.Applied)?.toVersion ?: targetVersion)
        }
    }

    fun doRestore(snapshotId: String) {
        operations.start(pack, PackOperationKind.Update) { _ ->
            val fresh = repo.get(pack.id) ?: pack
            val restored = updater.rollback(fresh, snapshotId)
            hub.report(pack.id, PackUpdateStatus.UpToDate)
            PackOperationPhase.Updated(
                restored.pinnedPackVersion ?: restored.packRef.version ?: snapshotId,
            )
        }
    }

    // Re-listed when the installed build changes and when an operation ends: an
    // apply writes a restore point and the retention sweep drops the oldest, and
    // neither of those is this screen's own doing any more. The walk reads a
    // record per entry, so it goes to IO rather than stalling the window.
    LaunchedEffect(pack.id, installedVersion, loadTick, operation?.isRunning) {
        // Not while one is running: the apply is writing a snapshot under the same
        // root this walks, and the list it would produce is neither current nor
        // final. The end of the operation re-runs this.
        if (operation?.isRunning == true) return@LaunchedEffect
        snapshots = withContext(Dispatchers.IO) {
            runCatching { updater.listSnapshots(pack) }.getOrDefault(snapshots)
        }
    }

    // Stale-then-fresh: a cached listing paints at once, the reloaded one replaces
    // it in place. Reading it once instead left the screen showing whatever the
    // cache held while the refresh it kicked off landed nowhere -- which is why
    // the newest builds only appeared on the second visit.
    LaunchedEffect(pack.id, loadTick) {
        loadFailed = false
        builds = null
        updater.availableBuildsStream(pack)
            .catch { loadFailed = true }
            .collect { list ->
                builds = list
                // Keep the user's pick across the refresh; only seed a selection
                // when there is none, or when the pick is gone from the listing.
                val current = selected?.key
                if (current == null || list.none { it.key == current }) {
                    selected = installedBuildOf(list, pack) ?: list.firstOrNull()
                }
            }
    }

    // Resolved once, by identity: a source may publish several builds under one
    // version number, and every marker on this screen has to mean the same one.
    val installedBuild = builds?.let { installedBuildOf(it, pack) }

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
                    installedKey     = installedBuild?.key,
                    latestKey        = builds?.firstOrNull()?.key,
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
                            installedKey     = installedBuild?.key,
                            installedVersion = installedVersion,
                            describesContents = updater.describesBuildContents(pack),
                            updater          = updater,
                            mirror           = mirror,
                            icons            = icons,
                            busy             = busy,
                            onSwitch         = { preview ->
                                if (preview.compat.isSafe) {
                                    // The identity, as the confirm path already does:
                                    // the build applied must be the row's own.
                                    runningGuard.run { doSwitch(sel.key) }
                                } else {
                                    confirmTarget = preview
                                }
                            },
                        )
                        if (snapshots.isNotEmpty()) {
                            SnapshotsSection(
                                snapshots = snapshots,
                                busy      = busy,
                                onRestore = { snap -> runningGuard.run { doRestore(snap.id) } },
                            )
                        }
                    } else if (builds != null && builds!!.isEmpty() && !loadFailed) {
                        Text(s.packVersionsLoadError, color = NxTheme.colors.textSecondary)
                    }
                }
            }
            StatusRow(operation)
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
        // The plan is absent for a source that cannot describe the change without
        // being handed the pack; the confirmation then names the versions only.
        val planLines = preview.plan?.let { plan ->
            "\n" + s.packVersionsPlanCounts(plan.toAdd.size, plan.toUpdate.size, plan.toDelete.size) +
                if (plan.conflicts.isNotEmpty()) "\n" + s.packVersionsConflicts(plan.conflicts.size) else ""
        }.orEmpty()
        DestructiveConfirmDialog(
            title        = s.packVersionsConfirmTitle,
            body         = s.packVersionsConfirmBody(installedVersion ?: "?", preview.toVersion) + planLines,
            confirmLabel = s.packVersionSwitch,
            onConfirm    = {
                // The identity, not the label: two builds can share a number and
                // the one applied must be the one the row stood for.
                val target = preview.targetKey
                confirmTarget = null
                // Two gates in sequence, because they answer different questions:
                // this one asked whether a structural change is wanted at all, the
                // next asks whether it is wanted right now, mid-session.
                runningGuard.run { doSwitch(target) }
            },
            onDismiss    = { confirmTarget = null },
        )
    }

    runningGuard.Dialog()
}

// ─── Left pane: build list ───────────────────────────────────────────────────

@Composable
private fun BuildListPane(
    builds: List<PackBuild>?,
    loadFailed: Boolean,
    installedKey: String?,
    latestKey: String?,
    selected: PackBuild?,
    onSelect: (PackBuild) -> Unit,
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
                        runs.firstOrNull { run -> run.drop(1).any { it.key == installedKey } }
                            ?.first()?.key,
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
                        // Identity, not label: a Modrinth pack publishes one version
                        // per loader and those share a version_number, so keying by
                        // the label threw "key was already used" and took the shell
                        // down with it.
                        item(key = head.key) {
                            BuildRow(
                                build       = head,
                                isInstalled = head.key == installedKey,
                                isLatest    = head.key == latestKey,
                                isSelected  = selected?.key == head.key,
                                rebuildTail = run.size - 1,
                                tailShown   = head.key in expandedRuns,
                                onToggleRun = {
                                    expandedRuns = if (head.key in expandedRuns) expandedRuns - head.key
                                    else expandedRuns + head.key
                                },
                                onClick     = { onSelect(head) },
                            )
                        }
                        if (head.key in expandedRuns) {
                            run.drop(1).forEach { member ->
                                item(key = member.key) {
                                    Box(Modifier.padding(start = 18.dp)) {
                                        BuildRow(
                                            build       = member,
                                            isInstalled = member.key == installedKey,
                                            isLatest    = member.key == latestKey,
                                            isSelected  = selected?.key == member.key,
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
    build: PackBuild,
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
                // Counts are omitted rather than zeroed when the source does not
                // publish them: a pack that says "0 mods" reads as broken, and
                // Modrinth cannot answer without handing over the whole archive.
                // What the build runs on, where the source says so. It is the one
                // fact that decides whether a switch strands a world, and it lands
                // in the gap left by a source that publishes no file counts, so
                // those rows stop reading as though something were missing.
                text  = listOfNotNull(
                    formatBuildTimestamp(build.datePublished),
                    build.modsCount?.let { mods -> build.assetsCount?.let { assets -> s.packVersionsCounts(mods, assets) } },
                    listOfNotNull(build.minecraftVersion, build.loaderName)
                        .takeIf { it.isNotEmpty() }
                        ?.joinToString(" "),
                ).joinToString("   "),
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
    builds: List<PackBuild>,
    build: PackBuild,
    installedKey: String?,
    installedVersion: String?,
    describesContents: Boolean,
    updater: PackUpdater,
    mirror: IMirrorPackClient,
    icons: ModIconResolver,
    busy: Boolean,
    onSwitch: (UpdateCheck.Available) -> Unit,
) {
    val s = LocalStrings.current
    val colors = NxTheme.colors
    val isInstalled = build.key == installedKey

    // Compat preview for a would-be switch; refreshed when the selection or the
    // installed build changes. Null while loading or for the installed build.
    val preview by produceState<UpdateCheck?>(null, build.key, installedKey) {
        value = null
        if (!isInstalled) {
            value = runCatching { updater.previewSwitch(pack, build.key) }.getOrNull()
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

        // The curator's "why" next to the structural diff's "what".
        build.changelog?.takeIf { it.isNotBlank() }?.let { notes ->
            NxSection(s.packVersionsNotes) {
                Markdown(content = notes)
            }
        }

        when {
            isInstalled -> Unit
            preview == null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(color = colors.primary.copy(alpha = 0.5f), strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
            }
            preview is UpdateCheck.Available -> {
                val p = preview as UpdateCheck.Available
                NxCalloutBanner(
                    // Without a plan the banner leads with the version instead of
                    // a file tally, rather than showing a tally of nothing.
                    title = p.plan
                        ?.let { s.packVersionsPlanCounts(it.toAdd.size, it.toUpdate.size, it.toDelete.size) }
                        ?: s.packVersionsSwitchTo,
                    body  = (if (p.compat.isSafe) s.packVersionSafe else s.packVersionNeedsCare) +
                        (p.plan?.takeIf { it.conflicts.isNotEmpty() }?.let { "\n" + s.packVersionsConflicts(it.conflicts.size) }.orEmpty()),
                    tone  = if (p.compat.isSafe) NxCalloutTone.Info else NxCalloutTone.Warning,
                ) {
                    Row {
                        PuppetClick("packVersions.switch.${build.versionNumber}", enabled = !busy) { onSwitch(p) }
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

        // Asked of the source, not attempted and reported when it fails: the
        // mirror's manifest endpoint knows nothing about a pack from anywhere
        // else, and the 404 it answers with reached the player as their pack
        // having failed.
        if (describesContents) {
            DiffSection(pack, builds, build, installedKey, installedVersion, mirror, icons)
        } else {
            Text(s.packVersionsNoDiffSource, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        }
    }
}

@Composable
private fun DiffSection(
    pack: PackInstance,
    builds: List<PackBuild>,
    build: PackBuild,
    installedKey: String?,
    installedVersion: String?,
    mirror: IMirrorPackClient,
    icons: ModIconResolver,
) {
    val s = LocalStrings.current
    val colors = NxTheme.colors
    var base by remember(build.key) { mutableStateOf(DiffBase.Previous) }

    // Previous distinct-content build: skip same-fingerprint rebuild siblings so
    // "vs previous" answers "what did this build change", not "same as the rebuild".
    val previous = remember(builds, build) {
        val idx = builds.indexOfFirst { it.key == build.key }
        if (idx < 0) null
        else builds.drop(idx + 1).firstOrNull { candidate ->
            build.fingerprint == null || candidate.fingerprint == null || candidate.fingerprint != build.fingerprint
        }
    }

    val isInstalled = build.key == installedKey
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
            val diff by produceState<Result<Pair<PackVersionDiff, SmrtBuildDiff?>>?>(null, build.versionNumber, baseVersion) {
                value = null
                value = runCatching {
                    withContext(Dispatchers.IO) {
                        val fromManifest = mirror.fetchManifestVersion(pack.packRef.id, baseVersion)
                        val toManifest = mirror.fetchManifestVersion(pack.packRef.id, build.versionNumber)
                        val computed = PackVersionDiff.compute(fromManifest, toManifest)
                        // Registry-enriched labels are display sugar: the mirror's diff
                        // endpoint knows real mod versions the manifests do not carry.
                        // Its absence (older mirror, offline race) costs only the labels.
                        val enriched = runCatching {
                            mirror.fetchDiff(pack.packRef.id, baseVersion, build.versionNumber)
                        }.getOrNull()
                        computed to enriched
                    }
                }
            }
            when (val result = diff) {
                null -> CircularProgressIndicator(color = colors.primary.copy(alpha = 0.5f), strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                else -> result.fold(
                    onSuccess = { (computed, enriched) -> DiffBody(computed, enriched, icons) },
                    onFailure = {
                        NxCalloutBanner(body = s.packVersionsFailed(it.message ?: it.toString()), tone = NxCalloutTone.Error)
                    },
                )
            }
        }
    }
}

@Composable
private fun DiffBody(diff: PackVersionDiff, enriched: SmrtBuildDiff?, icons: ModIconResolver) {
    val s = LocalStrings.current
    val colors = NxTheme.colors
    val labels = remember(enriched) { DiffLabels.from(enriched) }

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
            DiffGroup(diff.mods, labels, icons)
        }
    }
    if (diff.assets.isNotEmpty()) {
        NxSection(s.packVersionsSectionAssets) {
            diff.assets.forEach { entry ->
                val subject = entry.to ?: entry.from
                NxDiffRow(
                    kind     = entry.kind.toRowKind(),
                    title    = subject?.dest ?: "?",
                    trailing = sizeLabel(entry.from?.sizeBytes, entry.to?.sizeBytes, s),
                )
            }
        }
    }
}

@Composable
private fun DiffGroup(entries: List<DiffEntry<SmrtModEntry>>, labels: DiffLabels, icons: ModIconResolver) {
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
        added.forEach { ModDiffRow(it, labels, icons) }
    }
    if (updated.isNotEmpty()) {
        header(s.packVersionsUpdated(updated.size))
        updated.forEach { ModDiffRow(it, labels, icons) }
    }
    if (removed.isNotEmpty()) {
        header(s.packVersionsRemoved(removed.size))
        removed.forEach { ModDiffRow(it, labels, icons) }
    }
}

@Composable
private fun ModDiffRow(entry: DiffEntry<SmrtModEntry>, labels: DiffLabels, icons: ModIconResolver) {
    val s = LocalStrings.current
    val subject = entry.to ?: entry.from ?: return
    val title = subject.display?.name?.takeIf { it.isNotBlank() } ?: subject.filename
    // The mirror's registry labels beat anything derivable from the manifests:
    // real mod versions for updates, a version tag for adds/removes. Filename
    // details stay the fallback when the diff endpoint had nothing.
    val subtitle = labels.subtitleFor(entry)
        ?: when (entry.kind) {
            DiffKind.Updated -> entry.from?.filename?.takeIf { it != entry.to?.filename }?.let { "$it → ${entry.to?.filename}" }
                ?: subject.filename.takeIf { it != title }
            else -> subject.filename.takeIf { it != title }
        }
    NxDiffRow(
        kind     = entry.kind.toRowKind(),
        title    = title,
        subtitle = subtitle,
        trailing = sizeLabel(entry.from?.sizeBytes, entry.to?.sizeBytes, s),
        leading  = { ModDiffIcon(subject, icons) },
    )
}

/** Filename-keyed version labels lifted from the mirror's diff endpoint; empty when it was unavailable. */
private class DiffLabels(
    private val updated: Map<String, Pair<String?, String?>>,
    private val singleSided: Map<String, String>,
) {
    fun subtitleFor(entry: DiffEntry<SmrtModEntry>): String? {
        val filename = (entry.to ?: entry.from)?.filename ?: return null
        return when (entry.kind) {
            DiffKind.Updated -> updated[filename]?.let { (from, to) ->
                if (from != null && to != null) "$from → $to" else from ?: to
            }
            DiffKind.Added, DiffKind.Removed -> singleSided[filename]
        }
    }

    companion object {
        fun from(diff: SmrtBuildDiff?): DiffLabels {
            if (diff == null) return DiffLabels(emptyMap(), emptyMap())
            val updated = diff.modsUpdated.associate { it.filename to (it.versionFrom to it.versionTo) }
            val single = buildMap {
                diff.modsAdded.forEach { entry -> entry.version?.let { put(entry.filename, it) } }
                diff.modsRemoved.forEach { entry -> entry.version?.let { put(entry.filename, it) } }
            }
            return DiffLabels(updated, single)
        }
    }
}

@Composable
private fun ModDiffIcon(entry: SmrtModEntry, icons: ModIconResolver) {
    val url by produceState<String?>(entry.display?.iconUrl, entry.filename) {
        // The rows are emitted positionally, so one composition serves a different
        // entry when the compared versions change -- and produceState does not
        // re-apply its initial value on a key change. Without this reset the state
        // still held the previous mod's resolved icon, and the guard below then
        // skipped resolving, leaving that icon next to this mod's name for good.
        value = entry.display?.iconUrl
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
                formatBuildTime(Instant.ofEpochMilli(snap.createdAtEpoch))
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
 *
 * Narrates whatever this instance is running, whether or not this screen is what
 * started it -- an apply begun in the settings window carries on underneath.
 */
@Composable
private fun StatusRow(operation: PackOperation?) {
    val s = LocalStrings.current
    val colors = NxTheme.colors
    Box(Modifier.fillMaxWidth().height(34.dp).padding(top = 8.dp), contentAlignment = Alignment.CenterStart) {
        when (val phase = operation?.phase) {
            null -> Unit
            is PackOperationPhase.Running -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (phase.total > 0) {
                    LinearProgressIndicator(
                        progress = { phase.current.toFloat() / phase.total },
                        modifier = Modifier.width(160.dp),
                        color    = colors.primary,
                    )
                    Text(
                        text  = s.packVersionsApplying(phase.current, phase.total, phase.path),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.width(160.dp), color = colors.primary)
                }
            }
            is PackOperationPhase.Updated -> Text(
                text  = s.packVersionsApplied(phase.version),
                style = MaterialTheme.typography.labelSmall,
                color = colors.success,
            )
            is PackOperationPhase.Repaired -> Text(
                text  = s.packSettingsRepairDone(phase.checked, phase.repaired),
                style = MaterialTheme.typography.labelSmall,
                color = colors.success,
            )
            is PackOperationPhase.Failed -> Text(
                text     = s.packVersionsFailed(phase.message),
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

private fun sizeLabel(fromBytes: Long?, toBytes: Long?, s: AppStrings): String? = when {
    fromBytes != null && toBytes != null && fromBytes != toBytes -> "${humanSize(fromBytes, s)} → ${humanSize(toBytes, s)}"
    toBytes != null -> humanSize(toBytes, s)
    fromBytes != null -> humanSize(fromBytes, s)
    else -> null
}

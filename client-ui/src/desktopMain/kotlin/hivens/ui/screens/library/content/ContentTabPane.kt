package hivens.ui.screens.library.content

import kotlinx.coroutines.CoroutineScope
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import coil3.compose.LocalPlatformContext
import hivens.core.api.dto.modrinth.ModrinthProject
import hivens.core.api.dto.modrinth.ModrinthSearchHit
import hivens.core.data.OptionalContentRules
import hivens.core.data.PackInstance
import hivens.core.api.dto.modrinth.ModrinthVersion
import hivens.core.update.VersionChannel
import hivens.launcher.instance.ContentKind
import hivens.launcher.instance.ContentRef
import hivens.launcher.instance.DependencyIssue
import hivens.launcher.instance.InstalledContent
import hivens.launcher.instance.InstanceContentScanner
import hivens.launcher.instance.InstanceContentUpdater
import hivens.launcher.instance.ModUpdate
import hivens.launcher.instance.loadersFor
import androidx.compose.runtime.DisposableEffect
import hivens.ui.activity.Selection
import hivens.ui.activity.dragSelect
import hivens.ui.activity.SelectionAction
import hivens.ui.activity.SelectionActionKind
import hivens.ui.activity.SelectionItem
import hivens.ui.activity.SelectionRegistry
import hivens.ui.components.ConfirmDialog
import hivens.ui.components.DestructiveConfirmDialog
import hivens.ui.components.rememberRunningPackGuard
import hivens.ui.nx.NxAnchoredCard
import hivens.ui.nx.NxButton
import hivens.ui.nx.RetryStateBlock
import hivens.ui.nx.NxChoiceChip
import hivens.ui.nx.NxIconButton
import hivens.ui.nx.NxMetaChip
import hivens.ui.nx.NxMetaChipTone
import hivens.ui.nx.NxKebabButton
import hivens.ui.nx.NxContextMenu
import hivens.ui.nx.NxMenuItem
import hivens.ui.nx.NxPanelGroup
import hivens.ui.nx.NxPopoverPanel
import hivens.ui.nx.NxSteadyText
import hivens.ui.nx.NxSwitch
import hivens.ui.nx.NxToggle
import hivens.ui.nx.NxButtonStyle
import hivens.ui.nx.NxVerticalScrollbar
import hivens.ui.surface.NxSurface
import hivens.ui.surface.SurfaceKind
import hivens.ui.i18n.AppStrings
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.theme.familyForText
import hivens.ui.theme.decorativeColor
import hivens.ui.feature.catalogue.project.ModTarget
import hivens.ui.feature.catalogue.project.rememberLinkFollower
import hivens.ui.screens.versions.pickerIntentFor
import hivens.ui.screens.versions.pickerVersionsOf
import hivens.ui.screens.versions.VersionPickerWindow
import hivens.ui.utils.rememberFileDialogSettings
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import kotlin.time.Duration.Companion.milliseconds
import hivens.ui.theme.NxInk
import hivens.ui.theme.NxColor
import hivens.ui.theme.OnFill
import hivens.ui.theme.Status

/**
 * Library PackDetail Content tab. Reads what is ACTUALLY installed under the
 * instance (its `mods/`, `resourcepacks/`, `shaderpacks/` folders) via
 * [InstanceContentScanner] -- origin-agnostic, so it works for mirror, Modrinth
 * and from-scratch packs alike. Display is always on; managing (toggle / delete)
 * is unlocked once the instance is detached from its pack ([PackOrigin.Local]),
 * matching "detach, then make it your own". Detaching itself lives in the pack's
 * Data settings, not here: it costs the instance its update source, so it must not
 * sit one click away on a browsing surface.
 *
 * The scan, the pack manifest, the caches and every mutation live in
 * [ContentTabState]; this renders that state and forwards intents.
 */
@Composable
internal fun ContentTabPane(
    instance: PackInstance,
    state: ContentTabState,
    /**
     * Opens the project page. A page and not a dialog, because its metadata
     * blocks live in the shell's right rail and a dialog has no rail beside it.
     */
    onOpenProject: (ModTarget) -> Unit,
    /** Opens the launcher's browse aimed at this pack. */
    onFindProjects: () -> Unit,
    modifier: Modifier = Modifier,
    /** Opens the pack's settings where detaching lives, offered from a locked row's menu. */
    onOpenPackSettings: () -> Unit = {},
) {
    val s = LocalStrings.current
    val selections: SelectionRegistry = koinInject()
    // Replacing files the running game has open is the pack update's risk too, and
    // asked about the same way: on Windows the old jar cannot be removed and the
    // next start finds two builds of one mod.
    val runningGuard = rememberRunningPackGuard(instance.id)
    val scope = rememberCoroutineScope()
    val addDialogSettings = rememberFileDialogSettings(s.contentAddFiles)

    LaunchedEffect(state) { state.load() }
    // Separate from load(): this one runs for as long as the tab is on screen,
    // picking up anything added to the folders from outside the launcher.
    LaunchedEffect(state) { state.watchContentFolders() }
    // A batch of updates belongs to the app, not to this tab, so the tab
    // subscribes to it rather than owning it: come back mid-run and the line is
    // where the run actually is.
    LaunchedEffect(state) { state.watchUpdateRun() }
    // Asked once, when the scan first lands. Detecting this up front is what
    // lets the toolbar offer the update instead of making someone click
    // something to find out whether there is anything to click.
    LaunchedEffect(state, state.items) {
        if (state.items != null && !state.checked && !state.checkingUpdates) state.checkUpdates()
    }

    // The selection lives on the activity surface, so this view publishes it and
    // takes it back down on the way out. Leaving the tab with rows still ticked
    // would otherwise leave a bar offering to delete things the user can no
    // longer see.
    val picked = state.picked
    val blocked = if (state.lockedCount > 0) s.selectionBlockedByPack(state.lockedCount) else null
    DisposableEffect(picked, blocked) {
        val published = if (picked.isEmpty()) null else Selection(
            items = picked.map {
                SelectionItem(it.selectionKey(), it.displayName, state.iconFor(it)?.model())
            },
            actions = listOf(
                SelectionAction(SelectionActionKind.Enable, blockedReason = blocked) {
                    state.setEnabledForSelection(picked, true)
                },
                SelectionAction(SelectionActionKind.Disable, blockedReason = blocked) {
                    state.setEnabledForSelection(picked, false)
                },
                SelectionAction(SelectionActionKind.Delete, blockedReason = blocked) { state.requestBulkDelete() },
            ),
            clear = { state.clearSelection() },
        )
        selections.set(published)
        onDispose { selections.clearIf(published) }
    }

    Column(
        modifier            = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Toolbar(
            query          = state.query,
            onQuery        = { state.query = it },
            filter         = state.filter,
            onFilter       = { state.filter = it },
            filters        = state.filters,
            onFilters      = { state.filters = it },
            offersOptional = state.hasOptional,
            offersOwner    = state.hasPackContent,
            shownCount     = state.visible.size,
            scannedCount   = state.scannedCount,
            // Adding mods is gated behind detach; resource / shader packs can be added
            // any time (switch to their filter to target that folder).
            canAdd         = state.canAddContent ||
                state.filter.kind == ContentKind.ResourcePack ||
                state.filter.kind == ContentKind.ShaderPack,
            // A mirror pack refuses the player's mods and still takes their resource
            // packs and shaders, so the browse aimed at it opens on those.
            canFindProjects = state.canAddContent || state.isMirror,
            onAddFiles     = { state.addFiles(addDialogSettings) },
            onFindProjects = onFindProjects,
            // Updating is offered wherever replacing a file is: on a tracked pack
            // the pack decides what its mods are, and a swap behind its back is
            // undone by the next sync.
            updateCount    = state.liveUpdates.size,
            offersUpdates  = state.updatable.isNotEmpty(),
            checkFailed    = state.checkFailed,
            run            = state.updateRun,
            onUpdateAll    = state::requestUpdateAll,
            onCheck        = { scope.launch { state.checkUpdates(force = true) } },
        )

        // The outcome of a batch is NOT drawn here. It goes to the activity pill,
        // which is where the launcher accounts for work it is doing -- a band
        // across the list would push the content down to report on itself, and
        // the batch outlives this tab anyway.

        val visible = state.visible
        when {
            state.items == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = NxColor.wash(NxColor.lead(), 0.6f), strokeWidth = 2.dp, modifier = Modifier.size(26.dp))
            }
            visible.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(s.contentEmpty, style = MaterialTheme.typography.bodyMedium, color = NxInk.quiet)
            }
            else -> {
                val listState = rememberLazyListState()
                val hover = remember { MutableInteractionSource() }
                val hovered by hover.collectIsHoveredAsState()
                Box(Modifier.fillMaxSize().hoverable(hover)) {
                    LazyColumn(
                        state               = listState,
                        modifier            = Modifier.fillMaxSize().dragSelect(
                            listState   = listState,
                            keyAt       = { index -> visible.getOrNull(index)?.selectionKey() },
                            isSelected  = { it in state.selectedKeys },
                            setSelected = state::setSelected,
                            selecting   = state.selectedKeys.isNotEmpty(),
                        ),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(items = visible, key = { it.selectionKey() }) { c ->
                            val rules = state.rulesFor(c)
                            val ref = ContentRef(c.kind, c.fileName)
                            ContentRow(
                                content        = c,
                                selected       = c.selectionKey() in state.selectedKeys,
                                iconState      = state.iconFor(c),
                                rules          = rules,
                                onToggle       = { enabled -> state.toggle(c, enabled) },
                                onDelete       = if (rules.canDelete) ({ state.requestDelete(c) }) else null,
                                onDetails      = {
                                    onOpenProject(ModTarget.Installed(instance.id, c.kind, c.fileName))
                                },
                                resolveProject = { state.resolveProject(c) },
                                update         = state.liveUpdates[ref],
                                warning        = rules.problem?.let { problemReason(it, s) }
                                    ?: state.dependencyProblems[ref]?.firstOrNull()?.let { dependencyReason(it, state::providerName, s) }
                                    ?: state.liveBehind[ref]?.let { s.contentBehindPin(it.neededBy, it.pinned.versionNumber) },
                                onUpdate       = { runningGuard.run { state.update(c) } },
                                // Switching versions is the same write as an update,
                                // so it is offered on the same rows.
                                onVersions     = if (rules.canDelete) ({ state.openVersions(c) }) else null,
                                onOpenPackSettings = onOpenPackSettings,
                            )
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

    // Replacing fifty files is worth one question. It is not destructive enough
    // for the red gate, so it takes the ordinary one with its own verb.
    if (state.pendingUpdateAll > 0) {
        ConfirmDialog(
            title        = s.contentUpdateConfirmTitle,
            body         = s.contentUpdateConfirmBody(state.pendingUpdateAll),
            confirmLabel = s.contentUpdateConfirmAction,
            onConfirm    = { runningGuard.run(state::updateAll) },
            onDismiss    = state::cancelUpdateAll,
        )
    }

    if (state.pendingBulkDelete.isNotEmpty()) {
        DestructiveConfirmDialog(
            title        = s.contentDeleteTitle,
            body         = s.contentBulkDeleteBody(state.pendingBulkDelete.size),
            confirmLabel = s.editorDelete,
            onConfirm    = state::confirmDelete,
            onDismiss    = state::cancelDelete,
        )
    }

    if (state.pendingDelete != null) {
        DestructiveConfirmDialog(
            title        = s.contentDeleteTitle,
            body         = s.contentDeleteBody,
            confirmLabel = s.editorDelete,
            onConfirm    = state::confirmDelete,
            onDismiss    = state::cancelDelete,
        )
    }

    runningGuard.Dialog()

}

/**
 * The Content tab's version picker, hosted by the SCREEN rather than by the tab.
 *
 * It sizes itself against what contains it, and what contained it was the tab
 * body -- so the window came out inset by the tab's own padding, clipped on the
 * right, and scaled to a panel instead of to the app. A modal belongs to the
 * surface it covers, which is the screen; the same reason the pack's settings
 * window is hosted there and not inside whichever section opened it.
 */
@Composable
internal fun ContentVersionsOverlay(instance: PackInstance, state: ContentTabState) {
    val runningGuard = rememberRunningPackGuard(instance.id)
    runningGuard.Dialog()
    val target = state.versionsOf ?: return
    ModVersionsWindow(
        content       = target,
        versions      = state.versionList,
        failed        = state.versionsFailed,
        unknown       = state.versionsUnknown,
        installedId   = state.installedVersionId(target),
        icon          = state.iconFor(target)?.model(),
        busyVersionId = state.switchingTo,
        mcVersion     = instance.cachedManifest?.minecraftVersion.orEmpty(),
        loader        = instance.cachedManifest?.loaderName
            ?.takeIf { it.isNotBlank() && !it.equals("vanilla", ignoreCase = true) }
            ?.lowercase().orEmpty(),
        onPick        = { v -> runningGuard.run { state.switchTo(target, v) } },
        onDismiss     = state::closeVersions,
    )
}

/**
 * Every build Modrinth has of one installed file, in the picker the pack's own
 * version switch uses.
 *
 * The same window on purpose: picking a build of a mod and picking a build of a
 * pack are the same decision at a different scale, and the pack's picker already
 * answers what a person asks at that moment -- which one am I on, what is the
 * newest, what changed, and is this a step forward or back.
 *
 * Versions that cannot run here are left out rather than shown and refused, with
 * the installed one always kept: a file can be installed under a version that no
 * longer lists this game version, and hiding the row the reader is standing on
 * makes the list unreadable.
 */
@Composable
internal fun ModVersionsWindow(
    content: InstalledContent,
    versions: List<ModrinthVersion>?,
    failed: Boolean,
    unknown: Boolean,
    installedId: String?,
    icon: Any?,
    busyVersionId: String?,
    mcVersion: String,
    loader: String,
    onPick: (ModrinthVersion) -> Unit,
    onDismiss: () -> Unit,
) {
    val s = LocalStrings.current
    val loaders = loadersFor(content.kind, loader, mcVersion)
    // Everything with a file, marked rather than filtered. Dropping the builds
    // that do not fit answered "is there one for me" by making it unaskable: an
    // absence read the same whether the project never shipped one or shipped one
    // for another loader. The window folds them away by default and says how many.
    val all = versions.orEmpty()
    val rows = remember(all, mcVersion, loaders, installedId, s) {
        pickerVersionsOf(all, mcVersion, loaders, installedId, s)
    }
    val installedAt = all.firstOrNull { it.id == installedId }?.datePublished

    VersionPickerWindow(
        title       = s.contentVersionsTitle,
        packName    = content.displayName,
        packIcon    = icon,
        versions    = rows,
        intentFor   = { picked -> pickerIntentFor(picked, all, installedAt) },
        onConfirm   = { picked -> all.firstOrNull { it.id == picked.id }?.let(onPick) },
        onDismiss   = onDismiss,
        busyVersionId = busyVersionId,
        // The window opens on the click and the listing is a request behind it.
        loading     = versions == null && !unknown && !failed,
        // The list is empty for two different reasons and they need different
        // words: Modrinth has never seen this file, or it has and the fetch
        // failed. Neither is "this mod has no versions".
        warning     = when {
            unknown -> s.contentVersionsUnknown
            failed  -> s.contentVersionsLoadFailed
            versions != null && rows.isEmpty() -> s.contentVersionsUnknown
            else -> null
        },
    )
}

@Composable
internal fun Toolbar(
    query: String,
    onQuery: (String) -> Unit,
    filter: ContentFilter,
    onFilter: (ContentFilter) -> Unit,
    filters: ContentFilters,
    onFilters: (ContentFilters) -> Unit,
    offersOptional: Boolean,
    offersOwner: Boolean,
    shownCount: Int,
    scannedCount: Int,
    canAdd: Boolean,
    canFindProjects: Boolean,
    onAddFiles: () -> Unit,
    onFindProjects: () -> Unit,
    updateCount: Int,
    offersUpdates: Boolean,
    checkFailed: Boolean,
    run: InstanceContentUpdater.Run?,
    onUpdateAll: () -> Unit,
    onCheck: () -> Unit,
) {
    val s = LocalStrings.current
    var filtersOpen by remember { mutableStateOf(false) }
    // On a panel of its own, like the project page's header: laid straight over the
    // page, the outlined buttons and the unselected chips were ink on whatever the
    // wallpaper had there, and no ink the theme picks reads on a picture it has
    // never seen.
    NxSurface(SurfaceKind.Panel, modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ContentSearch(query, onQuery, s.contentSearchPlaceholder)
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment     = Alignment.CenterVertically,
        ) {
            Row(
                modifier              = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                FilterChip(s.contentFilterAll, filter == ContentFilter.All) { onFilter(ContentFilter.All) }
                FilterChip(s.contentFilterMods, filter == ContentFilter.Mods) { onFilter(ContentFilter.Mods) }
                FilterChip(s.contentFilterResourcePacks, filter == ContentFilter.ResourcePacks) { onFilter(ContentFilter.ResourcePacks) }
                FilterChip(s.contentFilterShaderPacks, filter == ContentFilter.ShaderPacks) { onFilter(ContentFilter.ShaderPacks) }
            }
            // The chips answer what kind of thing a row is; everything that
            // narrows WITHIN a section lives in one panel, so the row does not
            // grow a control per axis.
            ContentFilterButton(
                filters        = filters,
                onFilters      = onFilters,
                offersOptional = offersOptional,
                offersOwner    = offersOwner,
                shownCount     = shownCount,
                scannedCount   = scannedCount,
            )
            if (offersUpdates) {
                UpdateControls(
                    count       = updateCount,
                    checkFailed = checkFailed,
                    run         = run,
                    onUpdateAll = onUpdateAll,
                    onCheck     = onCheck,
                )
            }
            if (canFindProjects) {
                NxButton(label = s.contentFindProjects, onClick = onFindProjects, style = NxButtonStyle.Secondary, icon = NxIcon.Search, compact = true)
            }
            if (canAdd) {
                NxButton(label = s.contentAddFiles, onClick = onAddFiles, style = NxButtonStyle.Secondary, icon = NxIcon.Add, compact = true)
            }
        }
    }
    }
}

/**
 * The toolbar's update control: one button, and only when there is something to
 * press it for.
 *
 * A folder with nothing to update, and a check still running, both draw NOTHING.
 * The previous version narrated itself -- "checking", "everything is up to
 * date" -- which put a line of prose in a row of controls to report the absence
 * of work. A control that is not there says the same thing and takes no room.
 *
 * Two states do draw. Updates found: the download action, counted. A batch in
 * flight: the same slot, inert, carrying the count as it goes, because a minute
 * of downloads with no sign of progress reads as nothing happening.
 *
 * A check that FAILED is the exception, and it is a glyph rather than a
 * sentence: silence there would be indistinguishable from "nothing new", which
 * is the one wrong thing this can say. It is also the retry -- the only way back
 * from a check that did not run.
 */
@Composable
private fun UpdateControls(
    count: Int,
    checkFailed: Boolean,
    run: InstanceContentUpdater.Run?,
    onUpdateAll: () -> Unit,
    onCheck: () -> Unit,
) {
    val s = LocalStrings.current
    val active = run?.takeIf { !it.finished }
    when {
        active != null -> Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment     = Alignment.CenterVertically,
            modifier              = Modifier.padding(horizontal = 6.dp),
        ) {
            CircularProgressIndicator(color = NxColor.lead(), strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
            Text(
                text     = s.contentUpdateRunning(active.done, active.total),
                style    = MaterialTheme.typography.labelLarge,
                color    = NxInk.quiet,
                maxLines = 1,
            )
        }
        count > 0 -> NxButton(
            label   = s.contentUpdateAll(count),
            onClick = onUpdateAll,
            icon    = NxIcon.Download,
            compact = true,
        )
        checkFailed -> NxIconButton(
            icon               = NxIcon.Warning,
            contentDescription = s.contentUpdateCheckFailed,
            onClick            = onCheck,
            tint               = NxColor.status(Status.Warning),
        )
    }
}

/**
 * The filter trigger and its panel.
 *
 * Badged with the number of active axes and tinted while any is on: a list quietly
 * shorter than the folder reads as content having gone missing, and the badge is
 * what separates "nothing matches" from "nothing is there".
 *
 * An axis whose data the pack does not carry is not offered -- a local pack curates
 * nothing, so "optional" and "who added it" would be questions with one answer.
 */
@Composable
private fun ContentFilterButton(
    filters: ContentFilters,
    onFilters: (ContentFilters) -> Unit,
    offersOptional: Boolean,
    offersOwner: Boolean,
    shownCount: Int,
    scannedCount: Int,
) {
    val s = LocalStrings.current
    var open by remember { mutableStateOf(false) }
    Box {
        Box(contentAlignment = Alignment.TopEnd) {
            NxIconButton(
                // Turns into the close while the panel is out, and the panel's own
                // close lands on this exact spot -- so the control the user pressed
                // reads as having become the corner of what opened. Otherwise the
                // plain funnel: the crossed-out one reads as "no filtering", which
                // is the opposite of what an active filter means, so the badge and
                // the tint carry that instead.
                icon               = if (open) NxIcon.Close else NxIcon.FilterAlt,
                contentDescription = s.contentFiltersTitle,
                onClick            = { open = !open },
                tint               = if (filters.isEmpty && !open) NxInk.quiet
                                     else NxColor.lead(),
            )
            if (!filters.isEmpty && !open) {
                Box(
                    modifier = Modifier
                        .padding(top = 2.dp, end = 2.dp)
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(NxColor.lead()),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text       = filters.activeCount.toString(),
                        style      = MaterialTheme.typography.labelSmall,
                        color      = NxColor.on(NxColor.lead()),
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        NxPopoverPanel(
            expanded         = open,
            onDismissRequest = { open = false },
            title            = s.contentFiltersTitle,
            footer           = {
                Text(
                    text  = s.contentFiltersShown(shownCount, scannedCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = NxInk.quiet,
                )
                NxButton(
                    label   = s.contentFiltersReset,
                    onClick = { onFilters(ContentFilters()) },
                    style   = NxButtonStyle.Tertiary,
                    enabled = !filters.isEmpty,
                    compact = true,
                )
            },
        ) {
            if (offersOptional) {
                // A chip, not a settings toggle: every axis in this panel is asked
                // the same way, and a switch row among chip rows reads as a
                // different kind of thing than it is.
                NxPanelGroup(s.contentFilterGroupCurated, hint = s.contentFilterOptionalOnlyHint) {
                    NxChoiceChip(s.contentFilterOptionalOnly, filters.optionalOnly) {
                        onFilters(filters.copy(optionalOnly = !filters.optionalOnly))
                    }
                }
            }
            NxPanelGroup(s.contentFilterGroupStatus) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NxChoiceChip(s.contentFilterAny, filters.status == ContentStatus.Any) {
                        onFilters(filters.copy(status = ContentStatus.Any))
                    }
                    NxChoiceChip(s.contentFilterEnabled, filters.status == ContentStatus.Enabled) {
                        onFilters(filters.copy(status = ContentStatus.Enabled))
                    }
                    NxChoiceChip(s.contentFilterDisabled, filters.status == ContentStatus.Disabled) {
                        onFilters(filters.copy(status = ContentStatus.Disabled))
                    }
                }
            }
            if (offersOwner) {
                NxPanelGroup(s.contentFilterGroupOwner) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        NxChoiceChip(s.contentFilterAny, filters.owner == ContentOwner.Any) {
                            onFilters(filters.copy(owner = ContentOwner.Any))
                        }
                        NxChoiceChip(s.contentFilterOwnerPack, filters.owner == ContentOwner.Pack) {
                            onFilters(filters.copy(owner = ContentOwner.Pack))
                        }
                        NxChoiceChip(s.contentFilterOwnerUser, filters.owner == ContentOwner.User) {
                            onFilters(filters.copy(owner = ContentOwner.User))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContentSearch(query: String, onQuery: (String) -> Unit, placeholder: String) {
    NxSurface(SurfaceKind.Field, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        BasicTextField(
            value         = query,
            onValueChange = onQuery,
            singleLine    = true,
            textStyle     = MaterialTheme.typography.bodyMedium.copy(color = NxInk.main),
            cursorBrush   = SolidColor(NxColor.lead()),
            modifier      = Modifier.fillMaxWidth(),
        ) { inner ->
            Row(
                modifier          = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Symbol(NxIcon.Search, contentDescription = null, tint = NxInk.quiet, size = 18.dp)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = NxInk.quiet)
                    inner()
                }
            }
        }
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val fill = if (selected) NxColor.lead() else NxColor.wash(NxInk.quiet, UNSELECTED_CHIP_WASH)
    Box(
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .background(fill)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        OnFill(fill) {
            // The selected chip is bold and a bold chip is wider, which moved the rest
            // of the row sideways on every click; the box is measured at the weight the
            // label can grow to, so only the ink changes.
            NxSteadyText(
                text     = label,
                style    = MaterialTheme.typography.labelLarge,
                color    = if (selected) NxColor.on(fill) else NxInk.quiet,
                weight   = if (selected) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private const val UNSELECTED_CHIP_WASH = 0.10f

/** How much of a row the update chip may take before its label yields. */
private val UPDATE_CHIP_MAX = 200.dp

/**
 * One content row, drawn from the [rules] the state computed for it: what the
 * switch reads, whether there is one at all, and whether the row may be deleted.
 * [onDelete] is null unless the row is user-owned.
 */
@Composable
internal fun ContentRow(
    content: InstalledContent,
    iconState: ContentIconState?,
    rules: ContentRowRules,
    // Every row selects. A control that appears on some rows and not others
    // ragged the left edge of the whole list and told the user the difference
    // before they had asked a question it answers; what a row can have DONE to it
    // is a property of the action, not of whether it may be pointed at.
    //
    // The gesture itself belongs to the list: a drag is followed across children,
    // and a row only ever hears about itself.
    selected: Boolean,
    onToggle: (Boolean) -> Unit,
    onDelete: (() -> Unit)?,
    onDetails: () -> Unit,
    resolveProject: suspend () -> ModrinthProject?,
    update: ModUpdate?,
    onUpdate: () -> Unit,
    onVersions: (() -> Unit)?,
    onOpenPackSettings: () -> Unit = {},
    /**
     * What will go wrong with this file as things are set: a conflict or a missing
     * requirement the pack's rules name, or a library older than a build another
     * mod pinned. Drawn light red with the sentence under the name, never acted on.
     */
    warning: String? = null,
) {
    val s = LocalStrings.current
    val dim = if (rules.effectiveEnabled) 1f else 0.5f
    // A row that cannot be deleted is the pack's: only a mod is ever held back, the
    // rest of the content is the player's whatever the pack shipped.
    val lock = when {
        rules.canDelete -> null
        rules.showToggle -> RowLock.PackOptional
        else -> RowLock.Pack
    }
    var menuAt by remember(content.fileName) { mutableStateOf<Offset?>(null) }
    // A locked row answers a right-click with a card rather than a menu, see PackLockCard.
    var cardAt by remember(content.fileName) { mutableStateOf<Offset?>(null) }
    var cardFromKebab by remember(content.fileName) { mutableStateOf(false) }
    val currentLock by rememberUpdatedState(lock)
    var rowOrigin by remember(content.fileName) { mutableStateOf(Offset.Zero) }
    NxSurface(SurfaceKind.Card, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small) {
        val rowFill = when {
            selected -> NxColor.wash(NxColor.lead(), 0.14f)
            warning != null -> NxColor.wash(NxColor.status(Status.Error), PROBLEM_WASH)
            else -> Color.Transparent
        }
        OnFill(rowFill) {
            Row(
                modifier              = Modifier
                    .fillMaxWidth()
                    .background(rowFill)
                    .onGloballyPositioned { rowOrigin = it.boundsInWindow().topLeft }
                    .pointerInput(content.fileName) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.type != PointerEventType.Press) continue
                                if (!event.buttons.isSecondaryPressed) continue
                                val change = event.changes.first()
                                val at = rowOrigin + change.position
                                if (currentLock != null) cardAt = at else menuAt = at
                                change.consume()
                            }
                        }
                    }
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // The tick rides the icon rather than taking a column of its own, which
                // is what keeps every row the same shape whether or not anything is
                // selected. Messaging apps settled on this for the same reason.
                Box {
                    ContentIcon(iconState, content.fileName, content.displayName, dim)
                    if (selected) {
                        Box(
                            Modifier.matchParentSize().clip(MaterialTheme.shapes.small)
                                .background(NxColor.lead().copy(alpha = 0.72f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Symbol(NxIcon.Check, contentDescription = null, tint = NxColor.on(NxColor.lead()), size = 16.dp)
                        }
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text       = content.displayName,
                        fontFamily = familyForText(content.displayName),
                        style      = MaterialTheme.typography.bodyMedium,
                        color      = if (rules.effectiveEnabled) NxInk.main else NxInk.quiet,
                        fontWeight = FontWeight.SemiBold,
                        maxLines   = 1,
                        overflow   = TextOverflow.Ellipsis,
                    )
                    content.version?.let { v ->
                        Text(v, style = MaterialTheme.typography.labelSmall, color = if (rules.effectiveEnabled) NxInk.quiet else NxInk.off, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    // Said on the row and left to the player: the switch still does what
                    // they ask, and an update on offer is the chip beside it.
                    warning?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = NxColor.status(Status.Error, text = true), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                // The chip both reports the newer build and is the way to take it: the
                // row already says what is installed, so the one thing worth a control
                // here is the one thing the reader would do about it.
                if (update != null) {
                    NxMetaChip(
                        text    = s.contentUpdateTo(update.versionNumber),
                        // Capped: the label is a sentence WITH a version number in it, a
                        // Modrinth build number runs to forty characters, and a plain Row
                        // measures this before the name column beside it -- so one long
                        // build number ate the mod's own name off its row.
                        modifier = Modifier.widthIn(max = UPDATE_CHIP_MAX),
                        tone    = NxMetaChipTone.Success,
                        onClick = onUpdate,
                    )
                }
                // Beside the switch rather than buried in the overflow: picking a build
                // is a thing done TO this row, and the overflow is where actions go to
                // be found only by someone already looking for them.
                if (onVersions != null) {
                    NxIconButton(
                        // Swap, not a clock: History reads as "put back what was here
                        // before", and this picks WHICH build to run -- forward, back or
                        // sideways onto a beta.
                        icon               = NxIcon.SwapHoriz,
                        contentDescription = s.contentActionVersions,
                        onClick            = onVersions,
                        tint               = NxInk.quiet,
                    )
                }
                if (rules.showToggle) {
                    NxSwitch(
                        checked         = rules.effectiveEnabled,
                        onCheckedChange = onToggle,
                    )
                }
                // One overflow instead of a bare trash can: Details is always available
                // (local metadata at minimum); Open page and Delete appear only when the
                // caller passed them (a mod with a known URL / a user-owned row).
                if (lock != null) {
                    Box {
                        NxIconButton(
                            icon               = NxIcon.MoreVert,
                            contentDescription = s.packCardMore,
                            onClick            = { cardFromKebab = true },
                            tint               = NxInk.quiet,
                        )
                        NxAnchoredCard(expanded = cardFromKebab, onDismissRequest = { cardFromKebab = false }) {
                            PackLockCard(
                                content            = content,
                                lock               = lock,
                                mark               = { ContentIcon(iconState, content.fileName, content.displayName, 1f) },
                                resolveProject     = resolveProject,
                                onDetails          = onDetails,
                                onOpenPackSettings = onOpenPackSettings,
                                dismiss            = { cardFromKebab = false },
                            )
                        }
                    }
                } else {
                    NxKebabButton(contentDescription = s.packCardMore) { dismiss ->
                        ContentRowMenuItems(content, update, resolveProject, onDetails, onUpdate, onDelete, dismiss)
                    }
                }
            }
        }
    }

    // The same menu on a right-click, which is where a desktop reader looks for it
    // first. Anchored in window coordinates because the row is not the window, and
    // consumed on the MAIN pass so the editor's own right-click entry -- which
    // listens on the final pass over the whole shell -- never sees it. Without that
    // the gesture opened "edit layout" over a mod, which is a menu about the panel
    // answering a question asked about a file in it.
    menuAt?.let { at ->
        NxContextMenu(anchorInWindow = at, expanded = true, onDismissRequest = { menuAt = null }) {
            ContentRowMenuItems(content, update, resolveProject, onDetails, onUpdate, onDelete) { menuAt = null }
        }
    }
    cardAt?.let { at ->
        val held = lock ?: return@let
        NxAnchoredCard(anchorInWindow = at, expanded = true, onDismissRequest = { cardAt = null }) {
            PackLockCard(
                content            = content,
                lock               = held,
                mark               = { ContentIcon(iconState, content.fileName, content.displayName, 1f) },
                resolveProject     = resolveProject,
                onDetails          = onDetails,
                onOpenPackSettings = onOpenPackSettings,
                dismiss            = { cardAt = null },
            )
        }
    }
}

/**
 * What a content row offers, in one place so the kebab and the right-click cannot
 * drift into two different menus about the same file.
 */
@Composable
private fun ContentRowMenuItems(
    content: InstalledContent,
    update: ModUpdate?,
    resolveProject: suspend () -> ModrinthProject?,
    onDetails: () -> Unit,
    onUpdate: () -> Unit,
    onDelete: (() -> Unit)?,
    dismiss: () -> Unit,
) {
    val s = LocalStrings.current
    val follow = rememberLinkFollower()
    NxMenuItem(label = s.contentActionDetails, icon = NxIcon.Info, onClick = { dismiss(); onDetails() })
    if (update != null) {
        NxMenuItem(label = s.contentUpdateTo(update.versionNumber), icon = NxIcon.Download, onClick = { dismiss(); onUpdate() })
    }
    // "Open page" is kind-agnostic: the embedded homepage if the archive declared
    // one, else the canonical Modrinth page (mod / resourcepack / shader all
    // resolve by file hash). Resolved while the menu is open, so it appears once a
    // URL is known and never sits there dead for content with no page anywhere.
    var page by remember(content.fileName) { mutableStateOf(content.homepageUrl) }
    if (page == null) {
        LaunchedEffect(content.fileName) {
            // A lookup that could not be made leaves the entry off the menu, the
            // same as a file the catalogue does not know. Nothing here can report
            // a failure, and a menu is not where someone would read one.
            page = runCatching { resolveProject() }.getOrNull()
                ?.let { "https://modrinth.com/${it.projectType}/${it.slug}" }
        }
    }
    page?.let { url ->
        NxMenuItem(label = s.contentActionOpenPage, icon = NxIcon.OpenInNew, onClick = { dismiss(); follow(url) })
    }
    if (onDelete != null) {
        NxMenuItem(label = s.editorDelete, icon = NxIcon.Delete, destructive = true, onClick = { dismiss(); onDelete() })
    }
}

/**
 * Pure icon renderer: draws the pre-resolved [state] that the panel's prefetch put in
 * the shared cache, so a row does no resolution of its own. `null` = still resolving
 * (a tinted placeholder); [ContentIconState.None] = nothing found anywhere (a letter).
 * Resolution order lives in the prefetch: embedded -> Modrinth-by-hash -> jar probe.
 */
@Composable
private fun ContentIcon(state: ContentIconState?, seed: String, displayName: String, dim: Float) {
    val context = LocalPlatformContext.current
    val box = Modifier.size(30.dp).clip(RoundedCornerShape(7.dp))
    val plate = decorativeColor(seed).copy(alpha = dim)

    /** The tinted square, which is what an icon settles ONTO and never sits on. */
    @Composable
    fun Plate(letter: Boolean) {
        Box(Modifier.fillMaxSize().background(plate), contentAlignment = Alignment.Center) {
            if (letter) {
                Text(
                    text       = displayName.firstOrNull()?.uppercase() ?: "?",
                    style      = MaterialTheme.typography.labelMedium,
                    color      = Color.White,
                    fontWeight = FontWeight.Bold,
                    textAlign  = TextAlign.Center,
                )
            }
        }
    }

    // The plate is what is drawn WHILE and INSTEAD, never UNDERNEATH. Putting it
    // behind a loaded image killed the flash through an empty hole but tinted every
    // icon with transparency: a mod whose art is a tree on nothing came out as a
    // tree on a green square that was never part of it.
    Box(box) {
        when (state) {
            is ContentIconState.Bytes -> SubcomposeAsyncImage(
                // Keyed for the memory cache by hand: Coil has no keyer for a raw
                // ByteArray, so an unkeyed one is decoded again on every mount and
                // the jar's own icon flashed exactly like a fetched one.
                model = remember(state.data, seed) {
                    ImageRequest.Builder(context)
                        .data(state.data)
                        .memoryCacheKey("jar:$seed")
                        .build()
                },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loading = { Plate(letter = false) },
                error = { Plate(letter = true) },
            )
            is ContentIconState.Url -> SubcomposeAsyncImage(
                model = state.url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loading = { Plate(letter = false) },
                error = { Plate(letter = true) },
            )
            // Nothing found anywhere: the plate keeps its letter.
            ContentIconState.None -> Plate(letter = true)
            // Still resolving: the bare plate, which is what it settles onto.
            null -> Plate(letter = false)
        }
    }
}

/** The light red under a row with a [ContentRow] warning. */
private const val PROBLEM_WASH = 0.10f

/** The sentence under a mod the pack's rules have something to say about. */
internal fun problemReason(problem: OptionalContentRules.Problem, s: AppStrings): String {
    val name = problem.other.display?.name ?: problem.other.filename
    return when (problem) {
        is OptionalContentRules.Problem.ConflictsWith -> s.optionalConflictsWith(name)
        is OptionalContentRules.Problem.NeedsDisabled -> s.optionalNeedsOff(name)
        is OptionalContentRules.Problem.NeededBy -> s.optionalNeededBy(name)
    }
}

/** The sentence under a mod whose own metadata asks for something nothing enabled gives it. */
internal fun dependencyReason(issue: DependencyIssue, nameOf: (String) -> String, s: AppStrings): String {
    val wanted = issue.requirement.ranges.joinToString(" / ")
    return when (issue) {
        is DependencyIssue.Missing -> s.contentDependencyMissing(issue.requirement.id)
        is DependencyIssue.WrongVersion ->
            s.contentDependencyVersion(nameOf(issue.requirement.id), wanted, issue.installed.joinToString(", "))
    }
}

/** What the image loader should be handed for this icon, if anything. */
private fun ContentIconState.model(): Any? = when (this) {
    is ContentIconState.Bytes -> data
    is ContentIconState.Url -> url
    ContentIconState.None -> null
}

package hivens.ui.feature.catalogue.project

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hivens.core.api.dto.modrinth.ModrinthGameVersion
import hivens.core.api.dto.modrinth.ModrinthVersion
import hivens.core.update.VersionChannel
import hivens.ui.components.LoaderGlyph
import hivens.ui.components.formatBuildTimestamp
import hivens.ui.components.relativeAge
import hivens.ui.components.hasLoaderGlyph
import hivens.ui.i18n.AppStrings
import hivens.ui.i18n.LocalStrings
import hivens.ui.nx.CenteredProgress
import hivens.ui.nx.NxButton
import hivens.ui.nx.NxButtonStyle
import hivens.ui.nx.NxChoiceChip
import hivens.ui.nx.NxContextMenu
import hivens.ui.nx.NxMenuAlign
import hivens.ui.nx.NxIconButton
import hivens.ui.nx.NxMetaChip
import hivens.ui.nx.NxMetaChipTone
import hivens.ui.nx.NxPopoverPanel
import hivens.ui.nx.NxTooltip
import hivens.ui.nx.NxVerticalScrollbar
import hivens.ui.nx.RetryStateBlock
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.utils.humanSize
import kotlinx.coroutines.launch
import hivens.ui.theme.NxInk
import hivens.ui.theme.NxColor
import hivens.ui.theme.OnFill
import hivens.ui.theme.Status

/**
 * Every build the catalogue has, as a table.
 *
 * A table and not the version modal's list-beside-notes, which is what this was
 * first built as. The modal answers "which one do I switch to" for a reader who
 * already knows the mod, so it puts the notes where the eye lands. A tab answers
 * a different question: what has this project shipped, for what, and when. That is
 * a row per build with its facts in columns a reader can run down, and the notes
 * belong to the changelog tab next to it.
 */
@Composable
internal fun VersionsPane(
    state: ModDetailState,
    /** Opens one build's own page. The row keeps its files expander. */
    onOpenVersion: (ModrinthVersion) -> Unit,
    /**
     * Asks the SCREEN to load the project again.
     *
     * Not `state.load()` on this pane's own scope: the pane goes away when the
     * reader switches tabs, taking the retry it launched with it, and the two
     * would race the screen's own effect over the same fields either way.
     */
    onReload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    val installScope = state.installScope
    // Keyed on the project too. The page resolves it asynchronously, and a tab
    // opened before it lands used to ask with nothing to ask about, give up
    // silently, and leave a spinner running for the rest of the visit.
    LaunchedEffect(state, state.project) { state.loadVersions() }
    // A file the mirror answered for has its releases from there, folded the same way.
    LaunchedEffect(state, state.mirror) { if (state.mirror != null) state.loadGameVersionTags() }

    val all = state.versions

    when {
        // The page's own lookup never ran, so whether the catalogue holds an entry
        // for this file is not known. The branch below would answer that it holds
        // none, which is a verdict nobody obtained.
        state.failed -> {
            RetryStateBlock(
                title = s.contentTabFetchErrorTitle,
                message = s.contentTabFetchErrorGeneric,
                retryLabel = s.contentTabRetry,
                onRetry = onReload,
                modifier = modifier.padding(20.dp),
                titleStyle = MaterialTheme.typography.titleMedium,
            )
            return
        }
        state.versionsFailed -> {
            RetryStateBlock(
                title = s.modPageVersionsFailed,
                message = s.modPageVersionsFailedBody,
                retryLabel = s.contentTabRetry,
                onRetry = { scope.launch { state.versions = null; state.loadVersions() } },
                modifier = modifier.padding(20.dp),
                titleStyle = MaterialTheme.typography.titleMedium,
            )
            return
        }
        // Still finding out. Either the page has not resolved its project or the
        // list itself is in flight.
        state.loading || state.versionsLoading -> {
            CenteredProgress(modifier.fillMaxSize())
            return
        }
        // The catalogue has no entry and the mirror has one: its releases, with
        // nothing to install, since the mirror's files go in through its packs.
        !state.knownToCatalogue && state.mirror != null -> {
            val mirror = state.mirror
            val builds = remember(mirror) { mirror?.builds().orEmpty() }
            BuildsTable(
                builds = builds,
                gameVersionTags = state.gameVersionTags,
                onOpenBuild = null,
                rowAction = null,
                modifier = modifier,
            )
            return
        }
        // Looked, and there is no entry. A jar the catalogue has never seen has no
        // build list and never will, which is an answer rather than a wait.
        !state.knownToCatalogue -> {
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    s.modPageVersionsNoEntry,
                    style = MaterialTheme.typography.bodySmall,
                    color = NxInk.quiet,
                )
            }
            return
        }
        all == null -> {
            CenteredProgress(modifier.fillMaxSize())
            return
        }
    }

    // Everything with a file, newest first. A record with no file is a release
    // nobody can install.
    val rows = remember(all) {
        all.filter { it.files.isNotEmpty() }.sortedByDescending { it.datePublished }
    }
    if (rows.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                s.contentVersionsUnknown,
                style = MaterialTheme.typography.bodySmall,
                color = NxInk.quiet,
            )
        }
        return
    }
    val byId = remember(rows) { rows.associateBy { it.id } }
    val table = remember(rows) { rows.map { it.toBuild() } }
    // The action per row, which is the point of the table. Nothing where there is
    // no pack to put it in.
    val installable = state.install is InstallAction.Install || state.install is InstallAction.Present
    BuildsTable(
        builds = table,
        gameVersionTags = state.gameVersionTags,
        onOpenBuild = { b -> byId[b.id]?.let(onOpenVersion) },
        rowAction = if (installable) {
            { b -> byId[b.id]?.let { v -> InstallBuildButton(state, b, v, installScope) } }
        } else {
            null
        },
        modifier = modifier,
    )
}

/**
 * The per-row install on a project page.
 *
 * Quiet and icon-only, the way the reference draws it. A filled button repeated
 * down forty rows is forty invitations competing with the facts they sit beside,
 * and the facts are what the table is for. The label lives in the tooltip.
 *
 * A build the pack cannot run is MARKED, not withheld: the header's one-click pick
 * refuses rather than substitute, and this is where the reader overrules that on
 * purpose. Orange and a tooltip say which way they are stepping; the click still
 * works.
 */
@Composable
private fun InstallBuildButton(state: ModDetailState, b: ProjectBuild, v: ModrinthVersion, scope: CoroutineScope) {
    val s = LocalStrings.current
    val fits = remember(b, state.packMcVersion, state.packLoaders) {
        runsOn(b, state.packMcVersion, state.packLoaders)
    }
    NxIconButton(
        icon = NxIcon.Download,
        contentDescription = if (fits) s.modPageInstallShort else s.versionsIncompatibleHint,
        onClick = { scope.launch(Dispatchers.Main) { state.installVersion(v) } },
        enabled = !state.installing,
        tint = if (fits) NxColor.lead() else NxColor.status(Status.Warning),
    )
}

/**
 * Every build as a table: what it is, what it runs on, when it shipped and how
 * much it was taken, filterable on the three axes a reader narrows by.
 *
 * Shared by the project page and the pack page, which publish their builds in the
 * same terms. [onOpenBuild] makes the name a way to the build's own page where
 * there is one, [rowAction] fills the column at the end of each row, and a source
 * that counts no downloads but names its mods gets that count in the same place.
 */
@Composable
internal fun BuildsTable(
    builds: List<ProjectBuild>,
    gameVersionTags: List<ModrinthGameVersion>,
    onOpenBuild: ((ProjectBuild) -> Unit)?,
    rowAction: (@Composable (ProjectBuild) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    var filters by remember(builds) { mutableStateOf(VersionFilters()) }
    val facets = remember(builds, gameVersionTags) { facetsOf(builds, gameVersionTags) }
    val shown = remember(builds, filters) { builds.filter { filters.matches(it) } }
    val count = remember(builds) { countColumnOf(builds) }

    val listState = rememberLazyListState()
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    // The columns are fixed widths and together they need more room than the
    // narrowest window leaves the centre panel, so the table scrolls sideways
    // rather than losing its right-hand end. ONE state for the header and every
    // row: two would let the headings slide out from over their own columns.
    val columns = rememberScrollState()
    BoxWithConstraints(modifier) {
    // The name takes what the fixed columns leave, up to a ceiling. At its floor it
    // cut every name a build carries its loader and game version in, while half the
    // panel stood empty to the right of the table.
    val action = if (rowAction != null) ACTION_COLUMN else 0.dp
    val counted = if (count != null) COUNT_COLUMN + COLUMN_GAP else 0.dp
    val fixed = CHANNEL_COLUMN + CHIP_COLUMN * 2 + DATE_COLUMN + counted + COLUMN_GAP * 5 + ROW_PADDING * 2 + action
    val nameWidth = (maxWidth - fixed).coerceIn(NAME_COLUMN, NAME_COLUMN_MAX)
    Column(Modifier.fillMaxSize()) {
        VersionFilterBar(
            facets = facets,
            filters = filters,
            onFilters = { filters = it },
            shownCount = shown.size,
            totalCount = builds.size,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        )
        HorizontalDivider(color = NxInk.line)
        HeaderRow(columns, nameWidth, count)
        HorizontalDivider(color = NxInk.line)
        if (shown.isEmpty()) {
            // Filtered to nothing, which is not the same as a project with no
            // builds and must not read like one.
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    s.versionsFilterNoMatch,
                    style = MaterialTheme.typography.bodySmall,
                    color = NxInk.quiet,
                )
            }
            return@Column
        }
        Box(Modifier.weight(1f).hoverable(hover)) {
            var expanded by remember(shown) { mutableStateOf<String?>(null) }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(shown, key = { it.id }) { b ->
                    VersionTableRow(
                        columns = columns,
                        nameWidth = nameWidth,
                        b = b,
                        gameVersionTags = gameVersionTags,
                        count = count,
                        filters = filters,
                        onFilters = { filters = it },
                        onExpand = if (b.files.isEmpty()) null else { { expanded = if (expanded == b.id) null else b.id } },
                        onOpen = onOpenBuild?.let { open -> { open(b) } },
                        action = rowAction,
                    )
                    if (expanded == b.id) FilesRow(b, s)
                    HorizontalDivider(color = NxColor.wash(NxInk.line, 0.5f))
                }
            }
            NxVerticalScrollbar(
                adapter = rememberScrollbarAdapter(listState),
                revealed = hovered || listState.isScrollInProgress,
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            )
        }
    }
    }
}

/** What the number at the end of a row counts: downloads where the source counts them, else mods. */
internal enum class CountColumn { Downloads, Mods }

internal fun countColumnOf(builds: List<ProjectBuild>): CountColumn? = when {
    builds.any { it.downloads != null } -> CountColumn.Downloads
    builds.any { it.modsCount != null } -> CountColumn.Mods
    else -> null
}

/**
 * The three axes, side by side and always readable.
 *
 * One dropdown per axis in the bar itself, the way the reference does it, and NOT
 * one panel holding all three. A panel is right where the filter is a detour from
 * what is on screen; here the filter is about the table directly under it, and the
 * panel opened on top of the rows it was narrowing -- a reader could not see the
 * effect of the thing they were pressing. The trigger carries its own state, so
 * what is narrowing is legible without opening anything.
 */
@Composable
private fun VersionFilterBar(
    facets: VersionFacets,
    filters: VersionFilters,
    onFilters: (VersionFilters) -> Unit,
    shownCount: Int,
    totalCount: Int,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        if (facets.channels.size > 1) {
            FilterDropdown(
                label = s.versionsFilterChannel,
                summary = filters.channels.takeIf { it.isNotEmpty() }
                    ?.joinToString(", ") { channelLabel(it, s) },
                onClear = { onFilters(filters.clearChannels()) },
            ) {
                facets.channels.forEach { c ->
                    NxChoiceChip(channelLabel(c, s), c in filters.channels) {
                        onFilters(filters.toggleChannel(c))
                    }
                }
            }
        }
        if (facets.gameVersionGroups.size > 1) {
            FilterDropdown(
                label = s.versionsFilterGameVersion,
                summary = facets.gameVersionGroups
                    .filter { filters.gameVersions.containsAll(it.versions) }
                    .takeIf { it.isNotEmpty() }
                    ?.joinToString(", ") { it.label },
                onClear = { onFilters(filters.clearGameVersions()) },
            ) {
                facets.gameVersionGroups.forEach { g ->
                    NxChoiceChip(g.label, filters.gameVersions.containsAll(g.versions)) {
                        onFilters(filters.toggleGameVersions(g.versions))
                    }
                }
            }
        }
        if (facets.loaders.size > 1) {
            FilterDropdown(
                label = s.versionsFilterPlatform,
                summary = filters.loaders.takeIf { it.isNotEmpty() }
                    ?.joinToString(", ") { loaderLabel(it) },
                onClear = { onFilters(filters.clearLoaders()) },
            ) {
                facets.loaders.forEach { l ->
                    NxChoiceChip(loaderLabel(l), l in filters.loaders) {
                        onFilters(filters.toggleLoader(l))
                    }
                }
            }
        }
        // The count and the way out, at the end of the same row. Both belong to the
        // whole bar rather than to any one axis.
        Text(
            s.versionsFilterShown(shownCount, totalCount),
            style = MaterialTheme.typography.labelSmall,
            color = NxInk.quiet,
        )
        if (!filters.isEmpty) {
            NxButton(
                label = s.versionsFilterReset,
                onClick = { onFilters(VersionFilters()) },
                style = NxButtonStyle.Tertiary,
                compact = true,
            )
        }
    }
}

/**
 * One axis: a trigger that says what it is narrowed to, and a menu to change it.
 *
 * [summary] present means the axis is narrowing, and the trigger shows to what
 * rather than to a count. "Platform: Fabric" answers the question the label asks;
 * "Platform (1)" makes the reader open it to find out what the one is.
 */
@Composable
private fun FilterDropdown(
    label: String,
    summary: String?,
    onClear: () -> Unit,
    chips: @Composable () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val narrowed = summary != null
    val fill = if (narrowed) NxColor.wash(NxColor.lead(), 0.16f) else NxColor.wash(NxInk.quiet, 0.08f)
    Box {
        OnFill(fill) {
            Row(
                Modifier
                    .clip(MaterialTheme.shapes.large)
                    .background(fill)
                    .clickable { open = true }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    if (narrowed) "$label: $summary" else label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (narrowed) NxColor.lead(text = true) else NxInk.quiet,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 220.dp),
                )
                // The axis clears itself where it is narrowing, and opens where it is
                // not, so the same corner never means two things at once.
                if (narrowed) {
                    Symbol(
                        NxIcon.Close,
                        contentDescription = null,
                        tint = NxColor.lead(),
                        size = 14.dp,
                        modifier = Modifier.clickable { onClear() },
                    )
                } else {
                    Symbol(NxIcon.ExpandMore, contentDescription = null, tint = NxInk.quiet, size = 14.dp)
                }
            }
        }
        // Pinned to the LEADING edge. The trigger's label grows when a selection is
        // made ("Платформа" becomes "Платформа: Fabric, Forge"), and these chips do
        // not close the menu, so an end-aligned popup slid sideways by the whole of
        // that growth while the pointer was still clicking inside it. The left edge
        // is the one thing that cannot move while its own menu is open. The
        // component's KDoc says as much: trailing is for an overflow button,
        // leading for a list under a field, and this is the second kind.
        NxContextMenu(
            expanded = open,
            onDismissRequest = { open = false },
            align = NxMenuAlign.Start,
            minWidth = 200.dp,
        ) {
            FlowRow(
                Modifier.padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) { chips() }
        }
    }
}

/** Not composable: it reads nothing from composition, only from the language it is handed. */
private fun channelLabel(versionType: String, s: AppStrings): String =
    when (VersionChannel.of(versionType, "")) {
        VersionChannel.Release -> s.packVersionsChannelRelease
        VersionChannel.Beta -> s.packVersionsChannelBeta
        VersionChannel.Alpha -> s.packVersionsChannelAlpha
    }

// The measures the reference sets, in the same order. The channel is a mark and
// wants no more than a mark's worth; the two chip columns are capped so a build
// supporting thirty game versions cannot push the dates off the row.
private val CHANNEL_COLUMN = 40.dp
private val NAME_COLUMN = 180.dp
private val NAME_COLUMN_MAX = 400.dp
private val COLUMN_GAP = 10.dp
private val ROW_PADDING = 14.dp

/** Room kept at the end of a row for its install button. */
private val ACTION_COLUMN = 48.dp
private val CHIP_COLUMN = 192.dp
private val DATE_COLUMN = 130.dp
private val COUNT_COLUMN = 90.dp

/** Beyond these a chip column says how many more there are rather than growing. */
private const val MAX_GAME_VERSION_CHIPS = 5
private const val MAX_PLATFORM_CHIPS = 3

@Composable
private fun HeaderRow(columns: ScrollState, nameWidth: Dp, count: CountColumn?) {
    val s = LocalStrings.current
    Row(
        Modifier.fillMaxWidth().horizontalScroll(columns).padding(horizontal = ROW_PADDING, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(COLUMN_GAP),
    ) {
        Box(Modifier.width(CHANNEL_COLUMN))
        HeaderCell(s.versionsColumnVersion, nameWidth)
        HeaderCell(s.versionsColumnGameVersion, CHIP_COLUMN)
        HeaderCell(s.versionsColumnPlatform, CHIP_COLUMN)
        HeaderCell(s.versionsColumnPublished, DATE_COLUMN)
        when (count) {
            CountColumn.Downloads -> HeaderCell(s.versionsColumnDownloads, COUNT_COLUMN)
            CountColumn.Mods -> HeaderCell(s.contentFilterMods, COUNT_COLUMN)
            null -> Unit
        }
        Box(Modifier.weight(1f))
    }
}

@Composable
private fun HeaderCell(label: String, width: Dp) = Text(
    label,
    style = MaterialTheme.typography.labelMedium,
    color = NxInk.quiet,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
    modifier = Modifier.width(width),
)

@Composable
private fun VersionTableRow(
    columns: ScrollState,
    nameWidth: Dp,
    b: ProjectBuild,
    gameVersionTags: List<ModrinthGameVersion>,
    count: CountColumn?,
    filters: VersionFilters,
    onFilters: (VersionFilters) -> Unit,
    /** Opens the build's files under the row; null for a build that lists none. */
    onExpand: (() -> Unit)?,
    /** Opens the build's own page; null where it has none. */
    onOpen: (() -> Unit)?,
    action: (@Composable (ProjectBuild) -> Unit)?,
) {
    val s = LocalStrings.current
    val channel = remember(b) { VersionChannel.of(b.versionType, b.versionNumber) }
    Row(
        Modifier.fillMaxWidth()
            .horizontalScroll(columns)
            // The row opens its files. The reference reveals them under the row it
            // belongs to for the same reason: which jar a build actually ships, and
            // how big it is, is a question about that build and nowhere else.
            .then(
                if (onExpand == null) {
                    Modifier
                } else {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onExpand,
                    )
                },
            )
            .padding(horizontal = ROW_PADDING, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(COLUMN_GAP),
    ) {
        // The channel as a mark, not a word. A column of "Release" repeated forty
        // times is a column that says nothing; the colour is read at a glance and
        // the tooltip has the word for anyone who needs it.
        Box(Modifier.width(CHANNEL_COLUMN), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(9.dp).clip(CircleShape)
                    .background(channelColor(channel))
                    .clickable { onFilters(filters.toggleChannel(b.versionType)) },
            )
        }

        // The name is the way in to the build's own page, which is where the
        // reference puts that link too. The rest of the row keeps opening the
        // files, so the two reaches do not compete for the same pixel.
        val nameHover = remember { MutableInteractionSource() }
        val nameHovered by nameHover.collectIsHoveredAsState()
        Text(
            b.label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = NxInk.main,
            textDecoration = if (nameHovered && onOpen != null) TextDecoration.Underline else null,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(nameWidth)
                .then(
                    if (onOpen == null) {
                        Modifier
                    } else {
                        Modifier.hoverable(nameHover).clickable(interactionSource = nameHover, indication = null, onClick = onOpen)
                    },
                ),
        )

        // Every chip narrows the table to itself. That is the connective tissue
        // between a row and the filter panel: a reader who spots the platform they
        // run says "that one" by pointing at it, rather than opening a panel and
        // finding the same word in a list.
        val groups = remember(b, gameVersionTags) {
            gameVersionChips(b.gameVersions, gameVersionTags)
        }
        ChipColumn(
            title = s.versionsColumnGameVersion,
            groups = groups,
            cap = MAX_GAME_VERSION_CHIPS,
            selected = { filters.gameVersions.containsAll(it.versions) },
            onToggle = { onFilters(filters.toggleGameVersions(it.versions)) },
        )

        Box(Modifier.width(CHIP_COLUMN)) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                b.loaders.take(MAX_PLATFORM_CHIPS).forEach { loader ->
                    NxMetaChip(
                        loaderLabel(loader),
                        tone = if (loader in filters.loaders) NxMetaChipTone.Success else NxMetaChipTone.Surface,
                        leading = if (hasLoaderGlyph(loader)) {
                            { LoaderGlyph(loader, tint = NxInk.quiet, size = 12.dp) }
                        } else {
                            null
                        },
                        onClick = { onFilters(filters.toggleLoader(loader)) },
                    )
                }
                // The rest, named rather than counted: "+2" with no way to see
                // which two is a number that answers nothing.
                if (b.loaders.size > MAX_PLATFORM_CHIPS) {
                    OverflowChips(
                        title = s.versionsColumnPlatform,
                        labels = b.loaders.drop(MAX_PLATFORM_CHIPS).map { loaderLabel(it) to it },
                        selected = { it in filters.loaders },
                        onToggle = { onFilters(filters.toggleLoader(it)) },
                    )
                }
            }
        }

        // Relative, with the exact moment behind it. A column of full timestamps is
        // a column nobody reads: what a reader wants from this is how long ago,
        // and the date itself only when they are checking something specific.
        NxTooltip(text = formatBuildTimestamp(b.datePublished).orEmpty()) {
            Text(
                relativeAge(b.datePublished, s),
                style = MaterialTheme.typography.labelMedium,
                color = NxInk.quiet,
                maxLines = 1,
                modifier = Modifier.width(DATE_COLUMN),
            )
        }

        // The compact number is what fits; the exact one is what a reader
        // occasionally actually wants, so it is a hover away rather than gone.
        val counted = when (count) {
            CountColumn.Downloads -> b.downloads
            CountColumn.Mods -> b.modsCount?.toLong()
            null -> null
        }
        if (count != null) {
            NxTooltip(text = counted?.toString().orEmpty()) {
                Text(
                    counted?.let { compactCount(it, s) }.orEmpty(),
                    style = MaterialTheme.typography.labelMedium,
                    color = NxInk.quiet,
                    maxLines = 1,
                    modifier = Modifier.width(COUNT_COLUMN),
                )
            }
        }

        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            action?.invoke(b)
        }
    }
}

@Composable
private fun ChipColumn(
    title: String,
    groups: List<GameVersionGroup>,
    cap: Int,
    selected: (GameVersionGroup) -> Boolean,
    onToggle: (GameVersionGroup) -> Unit,
) {
    Box(Modifier.width(CHIP_COLUMN)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            groups.take(cap).forEach { g ->
                NxMetaChip(
                    g.label,
                    tone = if (selected(g)) NxMetaChipTone.Success else NxMetaChipTone.Surface,
                    modifier = Modifier.widthIn(max = CHIP_COLUMN),
                    onClick = { onToggle(g) },
                )
            }
            if (groups.size > cap) {
                // Carries the GROUP, not its label. Matching back by label picked
                // whichever group wore that text first, so two groups printing the
                // same thing toggled each other.
                OverflowChips(
                    title = title,
                    labels = groups.drop(cap).map { it.label to it },
                    selected = selected,
                    onToggle = onToggle,
                )
            }
        }
    }
}

/**
 * The files one build ships, under the row that ships them.
 *
 * The primary one is marked rather than sorted first: a build with three files has
 * one the installer takes and two beside it, and which is which is the whole of
 * what this row answers.
 */
@Composable
private fun FilesRow(v: ProjectBuild, s: AppStrings) {
    FlowRow(
        Modifier.fillMaxWidth().padding(start = 64.dp, end = 14.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        v.files.forEach { file ->
            NxMetaChip(
                "${file.filename}  ${humanSize(file.size, s)}",
                tone = NxMetaChipTone.Surface,
                leading = if (file.primary) {
                    {
                        Symbol(
                            NxIcon.Star,
                            contentDescription = null,
                            tint = NxColor.status(Status.Warning),
                            size = 12.dp,
                        )
                    }
                } else {
                    null
                },
            )
        }
    }
}

/**
 * The chips a column had no room for, behind a count that opens them.
 *
 * The reference reveals them on hover; a click is the same reach with one less way
 * to lose them by moving the pointer, and the menu is the one this app already
 * uses for a list of small choices.
 */
@Composable
private fun <T> OverflowChips(
    /** What the hidden chips are OF. The panel draws its header either way, so a blank one is a bare rule. */
    title: String,
    labels: List<Pair<String, T>>,
    selected: (T) -> Boolean,
    onToggle: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        NxMetaChip("+${labels.size}", tone = NxMetaChipTone.Surface, onClick = { open = true })
        NxPopoverPanel(
            expanded = open,
            onDismissRequest = { open = false },
            title = title,
            width = 240.dp,
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                labels.forEach { (label, value) ->
                    NxChoiceChip(label, selected(value)) { onToggle(value) }
                }
            }
        }
    }
}

@Composable
private fun channelColor(channel: VersionChannel) = when (channel) {
    VersionChannel.Release -> NxColor.status(Status.Success)
    VersionChannel.Beta -> NxColor.status(Status.Warning)
    VersionChannel.Alpha -> NxColor.status(Status.Error)
}

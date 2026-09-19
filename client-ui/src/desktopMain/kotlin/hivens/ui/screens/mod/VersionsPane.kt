package hivens.ui.screens.mod

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import hivens.core.api.dto.modrinth.ModrinthVersion
import hivens.core.update.VersionChannel
import hivens.ui.components.LoaderGlyph
import hivens.ui.components.formatBuildTimestamp
import hivens.ui.components.relativeAge
import hivens.ui.components.hasLoaderGlyph
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
import hivens.ui.theme.NxTheme
import hivens.ui.utils.humanSize
import kotlinx.coroutines.launch

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
    // Keyed on the project too. The page resolves it asynchronously, and a tab
    // opened before it lands used to ask with nothing to ask about, give up
    // silently, and leave a spinner running for the rest of the visit.
    LaunchedEffect(state, state.project) { state.loadVersions() }

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
        // Looked, and there is no entry. A jar the catalogue has never seen has no
        // build list and never will, which is an answer rather than a wait.
        !state.knownToCatalogue -> {
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    s.modPageVersionsNoEntry,
                    style = MaterialTheme.typography.bodySmall,
                    color = NxTheme.colors.textSecondary,
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
    val builds = all.orEmpty()
    val rows = remember(builds) {
        builds.filter { it.files.isNotEmpty() }.sortedByDescending { it.datePublished }
    }
    if (rows.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                s.contentVersionsUnknown,
                style = MaterialTheme.typography.bodySmall,
                color = NxTheme.colors.textSecondary,
            )
        }
        return
    }

    var filters by remember(state) { mutableStateOf(VersionFilters()) }
    val facets = remember(rows, state.gameVersionTags) { facetsOf(rows, state.gameVersionTags) }
    val shown = remember(rows, filters) { rows.filter { filters.matches(it) } }

    val listState = rememberLazyListState()
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    // The columns are fixed widths and together they need more room than the
    // narrowest window leaves the centre panel, so the table scrolls sideways
    // rather than losing its right-hand end. ONE state for the header and every
    // row: two would let the headings slide out from over their own columns.
    val columns = rememberScrollState()
    Column(modifier) {
        VersionFilterBar(
            facets = facets,
            filters = filters,
            onFilters = { filters = it },
            shownCount = shown.size,
            totalCount = rows.size,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        )
        HorizontalDivider(color = NxTheme.colors.outline.copy(alpha = 0.25f))
        HeaderRow(columns)
        HorizontalDivider(color = NxTheme.colors.outline.copy(alpha = 0.25f))
        if (shown.isEmpty()) {
            // Filtered to nothing, which is not the same as a project with no
            // builds and must not read like one.
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    s.versionsFilterNoMatch,
                    style = MaterialTheme.typography.bodySmall,
                    color = NxTheme.colors.textSecondary,
                )
            }
            return@Column
        }
        Box(Modifier.weight(1f).hoverable(hover)) {
            var expanded by remember(shown) { mutableStateOf<String?>(null) }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(shown, key = { it.id }) { v ->
                    VersionTableRow(
                        columns = columns,
                        v = v,
                        state = state,
                        scope = scope,
                        filters = filters,
                        onFilters = { filters = it },
                        expanded = expanded == v.id,
                        onExpand = { expanded = if (expanded == v.id) null else v.id },
                        onOpen = { onOpenVersion(v) },
                    )
                    if (expanded == v.id) FilesRow(v, s)
                    HorizontalDivider(color = NxTheme.colors.outline.copy(alpha = 0.12f))
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
            color = NxTheme.colors.textSecondary,
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
    Box {
        Row(
            Modifier
                .clip(MaterialTheme.shapes.large)
                .background(
                    if (narrowed) NxTheme.colors.primary.copy(alpha = 0.16f)
                    else NxTheme.colors.surface.copy(alpha = 0.5f),
                )
                .clickable { open = true }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                if (narrowed) "$label: $summary" else label,
                style = MaterialTheme.typography.labelMedium,
                color = if (narrowed) NxTheme.colors.primary else NxTheme.colors.textSecondary,
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
                    tint = NxTheme.colors.primary,
                    size = 14.dp,
                    modifier = Modifier.clickable { onClear() },
                )
            } else {
                Symbol(NxIcon.ExpandMore, contentDescription = null, tint = NxTheme.colors.textSecondary, size = 14.dp)
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
private fun channelLabel(versionType: String, s: hivens.ui.i18n.AppStrings): String =
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
private val CHIP_COLUMN = 192.dp
private val DATE_COLUMN = 130.dp
private val COUNT_COLUMN = 90.dp

/** Beyond these a chip column says how many more there are rather than growing. */
private const val MAX_GAME_VERSION_CHIPS = 5
private const val MAX_PLATFORM_CHIPS = 3

@Composable
private fun HeaderRow(columns: ScrollState) {
    val s = LocalStrings.current
    Row(
        Modifier.fillMaxWidth().horizontalScroll(columns).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.width(CHANNEL_COLUMN))
        HeaderCell(s.versionsColumnVersion, NAME_COLUMN)
        HeaderCell(s.versionsColumnGameVersion, CHIP_COLUMN)
        HeaderCell(s.versionsColumnPlatform, CHIP_COLUMN)
        HeaderCell(s.versionsColumnPublished, DATE_COLUMN)
        HeaderCell(s.versionsColumnDownloads, COUNT_COLUMN)
        Box(Modifier.weight(1f))
    }
}

@Composable
private fun HeaderCell(label: String, width: Dp) = Text(
    label,
    style = MaterialTheme.typography.labelMedium,
    color = NxTheme.colors.textSecondary,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
    modifier = Modifier.width(width),
)

@Composable
private fun VersionTableRow(
    columns: ScrollState,
    v: ModrinthVersion,
    state: ModDetailState,
    scope: kotlinx.coroutines.CoroutineScope,
    filters: VersionFilters,
    onFilters: (VersionFilters) -> Unit,
    expanded: Boolean,
    onExpand: () -> Unit,
    onOpen: () -> Unit,
) {
    val s = LocalStrings.current
    val channel = remember(v) { VersionChannel.of(v.versionType, v.versionNumber) }
    Row(
        Modifier.fillMaxWidth()
            .horizontalScroll(columns)
            // The row opens its files. The reference reveals them under the row it
            // belongs to for the same reason: which jar a build actually ships, and
            // how big it is, is a question about that build and nowhere else.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onExpand,
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // The channel as a mark, not a word. A column of "Release" repeated forty
        // times is a column that says nothing; the colour is read at a glance and
        // the tooltip has the word for anyone who needs it.
        Box(Modifier.width(CHANNEL_COLUMN), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(9.dp).clip(CircleShape)
                    .background(channelColor(channel))
                    .clickable { onFilters(filters.toggleChannel(v.versionType)) },
            )
        }

        // The name is the way in to the build's own page, which is where the
        // reference puts that link too. The rest of the row keeps opening the
        // files, so the two reaches do not compete for the same pixel.
        val nameHover = remember { MutableInteractionSource() }
        val nameHovered by nameHover.collectIsHoveredAsState()
        Text(
            v.versionNumber.ifBlank { v.name },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = NxTheme.colors.textPrimary,
            textDecoration = if (nameHovered) TextDecoration.Underline else null,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(NAME_COLUMN)
                .hoverable(nameHover)
                .clickable(interactionSource = nameHover, indication = null, onClick = onOpen),
        )

        // Every chip narrows the table to itself. That is the connective tissue
        // between a row and the filter panel: a reader who spots the platform they
        // run says "that one" by pointing at it, rather than opening a panel and
        // finding the same word in a list.
        val groups = remember(v, state.gameVersionTags) {
            groupGameVersions(v.gameVersions, state.gameVersionTags)
                .ifEmpty { v.gameVersions.map { GameVersionGroup(it, listOf(it)) } }
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
                v.loaders.take(MAX_PLATFORM_CHIPS).forEach { loader ->
                    NxMetaChip(
                        loaderLabel(loader),
                        tone = if (loader in filters.loaders) NxMetaChipTone.Success else NxMetaChipTone.Surface,
                        leading = if (hasLoaderGlyph(loader)) {
                            { LoaderGlyph(loader, tint = NxTheme.colors.textSecondary, size = 12.dp) }
                        } else {
                            null
                        },
                        onClick = { onFilters(filters.toggleLoader(loader)) },
                    )
                }
                // The rest, named rather than counted: "+2" with no way to see
                // which two is a number that answers nothing.
                if (v.loaders.size > MAX_PLATFORM_CHIPS) {
                    OverflowChips(
                        title = s.versionsColumnPlatform,
                        labels = v.loaders.drop(MAX_PLATFORM_CHIPS).map { loaderLabel(it) to it },
                        selected = { it in filters.loaders },
                        onToggle = { onFilters(filters.toggleLoader(it)) },
                    )
                }
            }
        }

        // Relative, with the exact moment behind it. A column of full timestamps is
        // a column nobody reads: what a reader wants from this is how long ago,
        // and the date itself only when they are checking something specific.
        NxTooltip(text = formatBuildTimestamp(v.datePublished).orEmpty()) {
            Text(
                relativeAge(v.datePublished, s),
                style = MaterialTheme.typography.labelMedium,
                color = NxTheme.colors.textSecondary,
                maxLines = 1,
                modifier = Modifier.width(DATE_COLUMN),
            )
        }

        // The compact number is what fits; the exact one is what a reader
        // occasionally actually wants, so it is a hover away rather than gone.
        NxTooltip(text = v.downloads.toString()) {
            Text(
                compactCount(v.downloads, s),
                style = MaterialTheme.typography.labelMedium,
                color = NxTheme.colors.textSecondary,
                maxLines = 1,
                modifier = Modifier.width(COUNT_COLUMN),
            )
        }

        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            // The action per row, which is the point of the table. Nothing where
            // there is no pack to put it in.
            if (state.install != InstallAction.None) {
                // Quiet and icon-only, the way the reference draws it. A filled
                // button repeated down forty rows is forty invitations competing
                // with the facts they sit beside, and the facts are what the table
                // is for. The label lives in the tooltip.
                //
                // A build the pack cannot run is MARKED, not withheld: the header's
                // one-click pick refuses rather than substitute, and this is where
                // the reader overrules that on purpose. Orange and a tooltip say
                // which way they are stepping; the click still works.
                val fits = remember(v, state.packMcVersion, state.packLoaders) {
                    runsOn(v, state.packMcVersion, state.packLoaders)
                }
                NxIconButton(
                    icon = NxIcon.Download,
                    contentDescription = if (fits) s.modPageInstallShort else s.versionsIncompatibleHint,
                    onClick = { scope.launch { state.installVersion(v) } },
                    enabled = !state.installing,
                    tint = if (fits) NxTheme.colors.primary else NxTheme.colors.warnAccent,
                )
            }
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
private fun FilesRow(v: ModrinthVersion, s: hivens.ui.i18n.AppStrings) {
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
                            tint = NxTheme.colors.warnAccent,
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
    VersionChannel.Release -> NxTheme.colors.success
    VersionChannel.Beta -> NxTheme.colors.warnAccent
    VersionChannel.Alpha -> NxTheme.colors.error
}

package hivens.ui.feature.catalogue.browse

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import hivens.catalogue.api.BROWSE_KINDS
import hivens.catalogue.api.BrowseSort
import hivens.catalogue.api.BrowseView
import hivens.catalogue.api.CatalogueCard
import hivens.catalogue.api.CatalogueKeys
import hivens.catalogue.api.FilterClear
import hivens.catalogue.api.FilterField
import hivens.catalogue.api.FilterPick
import hivens.catalogue.api.ProjectKind
import hivens.catalogue.api.ProjectTarget
import hivens.catalogue.api.SearchResults
import hivens.catalogue.api.SourceIcon
import hivens.catalogue.api.SourceView
import hivens.catalogue.api.catalogueTypeOf
import hivens.i18n.api.Strings
import hivens.ui.components.LoaderGlyph
import hivens.ui.components.hasLoaderGlyph
import hivens.ui.i18n.nexiraStrings
import hivens.ui.icons.IconKey
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.layers.layerTrigger
import hivens.ui.navigation.LauncherConfigs
import hivens.ui.nx.NxChoiceItem
import hivens.ui.nx.NxChoiceMenu
import hivens.ui.nx.NxIconButton
import hivens.ui.nx.NxMenuAlign
import hivens.ui.nx.NxTooltip
import hivens.ui.press.clickable
import hivens.ui.puppet.PuppetClick
import hivens.ui.puppet.PuppetField
import hivens.ui.feature.catalogue.project.loaderLabel
import hivens.ui.surface.NxSurface
import hivens.ui.surface.SurfaceKind
import hivens.ui.theme.Motion
import hivens.ui.theme.NxColor
import hivens.ui.theme.NxInk
import hivens.ui.theme.Status
import hivens.ui.widgets.RailBlock
import hivens.ui.widgets.RailGroup
import hivens.ui.widgets.RailLabel
import hivens.widget.api.rememberScopedCommand
import hivens.widget.api.rememberScopedSource
import hivens.widget.api.valueOrNull
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance
import hivens.pages.api.PagesKeys

/**
 * Browse's widgets: the pack being installed into, the search box, the chosen
 * filters and the results in the centre, and what is searched in the right rail.
 * The filter blocks of the rail are in BrowseFilterWidgets.kt.
 *
 * None of them talks to another. Each reads the window's [CatalogueKeys.Browse] and
 * changes it through the browse commands, which is why the rail can steer a list it
 * has never seen and why any of them can be moved or taken out in the editor without
 * the others noticing. Where no catalogue module is brought in they draw nothing.
 */

@Widget(id = "browse.search", displayName = "widget.browse.search")
@Composable
fun BrowseSearchWidget(instance: WidgetInstance) {
    val browse = rememberScopedSource(CatalogueKeys.Browse).valueOrNull() ?: return
    val setQuery = rememberScopedCommand(CatalogueKeys.SetQuery) ?: return
    val s = nexiraStrings()
    val (text, type) = rememberEchoedText(browse.query, setQuery)
    PuppetField("browse.search", text) { type(it) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // On a narrow column the words of the pill took the field's whole width
        val narrow = maxWidth < NARROW_SEARCH
        SearchField(
            value         = text,
            onValueChange = type,
            placeholder   = searchPlaceholder(browse.kind, s),
            modifier      = Modifier.padding(bottom = 10.dp),
            // A project search is sorted. A source that lists its packs itself lists them
            // in its own order.
            trailing      = if (browse.type != null) {
                { SortPicker(browse.sort, wordless = narrow) }
            } else {
                null
            },
        )
    }
}

private val NARROW_SEARCH = 560.dp

private fun searchPlaceholder(kind: ProjectKind?, s: Strings): String = when (kind) {
    null -> s.text("browseSearchPlaceholder")
    ProjectKind.Mod -> s.text("browseSearchMods")
    ProjectKind.ResourcePack -> s.text("browseSearchResourcePacks")
    ProjectKind.ShaderPack -> s.text("browseSearchShaders")
}

/** The order of a catalogue search, as a pill at the end of the field. */
@Composable
private fun SortPicker(current: BrowseSort, wordless: Boolean) {
    val s = nexiraStrings()
    val setSort = rememberScopedCommand(CatalogueKeys.SetSort)
    var open by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf(false) }
    val turn by animateFloatAsState(if (open) 180f else 0f, animationSpec = Motion.tap, label = "sortChevron")
    val fade = Motion.fade
    val tap = Motion.tap
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val fill by animateColorAsState(
        NxColor.wash(NxInk.quiet, if (hovered || open || shown) 0.16f else 0.10f),
        animationSpec = Motion.tap.of(),
        label = "sortFill",
    )
    Box {
        Row(
            modifier = Modifier
                .clip(MaterialTheme.shapes.medium)
                .background(fill)
                .hoverable(interaction)
                .layerTrigger()
                .clickable(interactionSource = interaction, indication = null) { open = true }
                .padding(start = 12.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (wordless) {
                Symbol(NxIcon.Tune, contentDescription = s.text("browseSortLabel"), tint = NxInk.main, size = 18.dp)
            } else {
                Text(s.text("browseSortLabel"), style = MaterialTheme.typography.labelLarge, color = NxInk.quiet, maxLines = 1)
                AnimatedContent(
                    targetState    = current,
                    transitionSpec = { fade.enter togetherWith fade.exit using SizeTransform(clip = false) { _, _ -> tap.of() } },
                    label          = "sortValue",
                ) { sort ->
                    Text(sort.label(s), style = MaterialTheme.typography.labelLarge, color = NxInk.main, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
            Symbol(NxIcon.ExpandMore, contentDescription = null, tint = NxInk.quiet, size = 18.dp, modifier = Modifier.rotate(turn))
        }
        NxChoiceMenu(expanded = open, onDismissRequest = { open = false }, align = NxMenuAlign.End, onShownChange = { shown = it }) {
            BrowseSort.entries.forEach { sort ->
                NxChoiceItem(label = sort.label(s), selected = sort == current) {
                    setSort?.invoke(sort)
                    open = false
                }
            }
        }
    }
    BrowseSort.entries.forEach { sort -> PuppetClick("browse.sort.${sort.name}") { setSort?.invoke(sort) } }
}

// The ceiling is load-bearing: this lists lazily, and a lazy list cannot be
// measured against an unbounded axis.
@Widget(
    id = "browse.results",
    displayName = "widget.browse.results",
    minWidth = 320, minHeight = 200,
    maxWidth = 2400, maxHeight = 1600,
    needs = ["nav.show", CatalogueKeys.RESULTS_ID],
)
@Composable
fun BrowseResultsWidget(instance: WidgetInstance) {
    val browse = rememberScopedSource(CatalogueKeys.Browse).valueOrNull() ?: return
    val results = rememberScopedSource(CatalogueKeys.Results).valueOrNull() ?: return
    val show = rememberScopedCommand(PagesKeys.Show) ?: return
    // A result leaves Browse for its page: a catalogue pack's, or a project's, which
    // carries the target pack so the page can install into it.
    val openPack: (CatalogueCard) -> Unit = remember(show) {
        { pack -> show(LauncherConfigs.cataloguePack(pack.origin, pack.id)) }
    }
    val openProject: (ProjectTarget) -> Unit = remember(show) { { show(LauncherConfigs.project(it)) } }
    val fade = Motion.fade
    // Each kind is its own list, so a change of kind crossfades one list into the
    // next instead of swapping the cards under the reader's eye. The list fading out
    // keeps drawing the answer it had, since the window's search has moved on.
    AnimatedContent(
        targetState    = browse.kind to browse.origin,
        transitionSpec = { fade.enter togetherWith fade.exit },
        modifier       = Modifier.fillMaxSize(),
        label          = "browseKind",
    ) { (kind, from) ->
        val last = remember { arrayOf<SearchResults?>(null) }
        val mine = results.question?.let { it.kind == kind && it.origin == from } == true
        if (mine) last[0] = results
        val shown = if (mine) results else last[0] ?: SearchResults()
        if (catalogueTypeOf(kind, browse.sources.firstOrNull { it.id == from }?.packType) == null) {
            val source = browse.sources.firstOrNull { it.id == from }
            PackBrowse(shown, openPack, shown = browse::shows, sourceName = source?.let { sourceLabel(it) } ?: from)
        } else {
            ProjectResults(results = shown, kind = kind, onOpenProject = openProject, onOpenPack = openPack)
        }
    }
}

/**
 * The pack the catalogue installs into, as a header over the search, the way the
 * catalogue's own app names the instance it is adding to: its mark, its name, what
 * it runs, a way back to it and a way to stop installing into it.
 *
 * Draws nothing without a pack, which is the ordinary case: an install from the
 * catalogue then asks where.
 */
@Widget(id = "browse.installing", displayName = "widget.browse.installing")
@Composable
fun BrowseInstallingWidget(instance: WidgetInstance) {
    val browse = rememberScopedSource(CatalogueKeys.Browse).valueOrNull() ?: return
    val setTarget = rememberScopedCommand(CatalogueKeys.SetTarget)
    val show = rememberScopedCommand(PagesKeys.Show)
    val s = nexiraStrings()
    val target = browse.target
    PuppetClick("browse.target.none") { setTarget?.invoke(null) }
    AnimatedVisibility(visible = target != null, enter = Motion.reveal.enter, exit = Motion.reveal.exit) {
        val t = target ?: return@AnimatedVisibility
        Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            NxSurface(SurfaceKind.Panel, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    NxTooltip(text = s.text("browseBackToPack")) {
                        NxIconButton(icon = NxIcon.ArrowBack, contentDescription = s.text("browseBackToPack"), onClick = { show?.invoke(LauncherConfigs.pack(t.packId)) })
                    }
                    PackMark(t.packId, t.name, 48.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(s.text("browseInstallingInto"), style = MaterialTheme.typography.labelMedium, color = NxInk.quiet)
                        Text(
                            t.name,
                            style = MaterialTheme.typography.titleLarge,
                            color = NxInk.main,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        FlowRow(itemVerticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            if (t.mcVersion.isNotBlank()) Fact(NxIcon.Inventory2, "Minecraft ${t.mcVersion}")
                            if (t.loader.isNotBlank()) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (hasLoaderGlyph(t.loader)) LoaderGlyph(t.loader, tint = NxInk.quiet, size = 16.dp)
                                    Text(loaderLabel(t.loader), style = MaterialTheme.typography.bodySmall, color = NxInk.quiet)
                                }
                            }
                        }
                    }
                    NxTooltip(text = s.text("browseLeaveTarget")) {
                        NxIconButton(icon = NxIcon.Close, contentDescription = s.text("browseLeaveTarget"), onClick = { setTarget?.invoke(null) })
                    }
                }
            }
            // A kind the pack does not take is still worth browsing, and only the
            // install is not on offer. Said here, where the pack is named.
            AnimatedVisibility(visible = browse.kind != null && !t.takes(browse.kind), enter = Motion.reveal.enter, exit = Motion.reveal.exit) {
                Row(Modifier.padding(start = 6.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Symbol(NxIcon.Info, contentDescription = null, tint = NxColor.status(Status.Warning), size = 16.dp)
                    Text(s.text("installTargetNotTaken"), style = MaterialTheme.typography.bodySmall, color = NxColor.status(Status.Warning))
                }
            }
        }
    }
}

@Composable
private fun Fact(icon: IconKey, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Symbol(icon, contentDescription = null, tint = NxInk.quiet, size = 16.dp)
        Text(text, style = MaterialTheme.typography.bodySmall, color = NxInk.quiet)
    }
}

/**
 * The filters in force, as chips under the search: each one taken off by its own
 * cross, all of them at once when there are several, and the pack's own as locked
 * chips that say where they came from.
 */
@Widget(id = "browse.chips", displayName = "widget.browse.chips")
@Composable
fun BrowseChipsWidget(instance: WidgetInstance) {
    val browse = rememberScopedSource(CatalogueKeys.Browse).valueOrNull() ?: return
    val remove = rememberScopedCommand(CatalogueKeys.RemoveFilter)
    val clear = rememberScopedCommand(CatalogueKeys.ClearFilters)
    val s = nexiraStrings()
    val type = browse.type
    val locked = if (type == null) emptyList() else browse.locked
    val lockedFields = locked.mapTo(HashSet()) { it.field }
    val chosen = type?.let { browse.chosenFor(it).filterNot { f -> f.field in lockedFields } }.orEmpty()
    val versions = rememberScopedSource(CatalogueKeys.Tags).valueOrNull()?.gameVersions.orEmpty()
    val chips = chosenChips(chosen, versions, s)
    PuppetClick("browse.chips.clear") { type?.let { clear?.invoke(FilterClear(it)) } }
    AnimatedVisibility(visible = chosen.isNotEmpty() || locked.isNotEmpty(), enter = Motion.reveal.enter, exit = Motion.reveal.exit) {
        FlowRow(
            Modifier.fillMaxWidth().padding(bottom = 12.dp).animateContentSize(Motion.reveal.of()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Counted in chips, the way they read: one folded `1.20.x` is one choice
            // to the reader however many versions it holds.
            if (chips.size > 1 && type != null) {
                FilterChip(s.text("browseFiltersClearAll"), leading = NxIcon.FilterAltOff, emphasis = true) { clear?.invoke(FilterClear(type)) }
            }
            chips.forEach { chip ->
                FilterChip(chip.label, leading = NxIcon.Close, excluded = chip.excluded) {
                    if (type != null) chip.filters.forEach { remove?.invoke(FilterPick(type, it)) }
                }
            }
            locked.groupBy { it.field }.forEach { (field, values) ->
                val text = when (field) {
                    FilterField.Loader -> values.joinToString(", ") { loaderLabel(it.value) }
                    else -> values.joinToString(", ") { it.value }
                }
                NxTooltip(text = s.text("browseFilterLockedBy", browse.target?.name.orEmpty())) {
                    FilterChip(text, leading = NxIcon.Lock, onClick = null)
                }
            }
        }
    }
}

@Composable
private fun FilterChip(
    text: String,
    leading: IconKey,
    excluded: Boolean = false,
    emphasis: Boolean = false,
    onClick: (() -> Unit)?,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val fill by animateColorAsState(
        when {
            hovered && onClick != null -> NxColor.wash(NxInk.quiet, 0.20f)
            emphasis -> NxColor.wash(NxColor.lead(), 0.14f)
            else -> NxColor.wash(NxInk.quiet, 0.12f)
        },
        animationSpec = Motion.tap.of(),
        label = "chipFill",
    )
    val shape = MaterialTheme.shapes.extraLarge
    Row(
        Modifier.clip(shape).background(fill)
            .then(if (onClick != null) Modifier.hoverable(interaction).clickable(interactionSource = interaction, indication = null, onClick = onClick) else Modifier)
            .padding(start = 8.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Symbol(leading, contentDescription = null, tint = NxInk.quiet, size = 14.dp)
        // Ruled out, it says so beside the cross that takes it off.
        if (excluded) Symbol(NxIcon.Block, contentDescription = null, tint = NxColor.status(Status.Error), size = 14.dp)
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (emphasis) NxColor.lead() else NxInk.main, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/**
 * What is searched: four tiles for the four kinds, and for packs the source they
 * are listed from.
 *
 * A kind the chosen pack does not take stays on offer, dimmed, with the reason on
 * hover: browsing it is still useful, and only the install is not on offer.
 */
@Widget(
    id = "browse.scope",
    displayName = "widget.browse.scope",
    surface = """{"fill":"card","border":{"widthDp":1.0}}""",
)
@Composable
fun BrowseScopeWidget(instance: WidgetInstance) {
    val browse = rememberScopedSource(CatalogueKeys.Browse).valueOrNull() ?: return
    val setKind = rememberScopedCommand(CatalogueKeys.SetKind)
    val setOrigin = rememberScopedCommand(CatalogueKeys.SetOrigin)
    val s = nexiraStrings()
    val sources = browse.sources
    val target = browse.target

    RailBlock(s.text("browseRailFind")) {
        Column(verticalArrangement = Arrangement.spacedBy(TILE_GAP)) {
            BROWSE_KINDS.chunked(2).forEach { pair ->
                // As tall as its taller tile, so a name that takes two lines does not
                // leave its neighbour short.
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(TILE_GAP)) {
                    pair.forEach { kind ->
                        val reason = if (target != null && kind != null && !target.takes(kind)) s.text("installTargetNotTaken") else null
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            val tile = @Composable {
                                KindTile(
                                    label    = kindLabel(kind, s),
                                    icon     = kindIcon(kind),
                                    selected = browse.kind == kind,
                                    dimmed   = reason != null,
                                    onClick  = { setKind?.invoke(kind) },
                                )
                            }
                            if (reason != null) NxTooltip(text = reason) { tile() } else tile()
                        }
                        PuppetClick("browse.kind.${kind?.name ?: "Packs"}") { setKind?.invoke(kind) }
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = browse.kind == null && sources.size > 1,
            enter   = Motion.reveal.enter,
            exit    = Motion.reveal.exit,
        ) {
            RailGroup {
                RailLabel(s.text("browseRailSource"))
                SourceSwitch(sources, browse.origin) { setOrigin?.invoke(it) }
            }
        }
        // A driver clicks a source by its id.
        sources.forEach { o -> PuppetClick("browse.source.${o.id}") { setOrigin?.invoke(o.id) } }
    }
}

internal val TILE_GAP = 6.dp

private fun kindIcon(kind: ProjectKind?): IconKey = when (kind) {
    null -> NxIcon.Inventory2
    ProjectKind.Mod -> NxIcon.Build
    ProjectKind.ResourcePack -> NxIcon.Palette
    ProjectKind.ShaderPack -> NxIcon.LightMode
}

/**
 * One choice as a tile: its mark over its name, filled with the accent while chosen.
 * With [glyphFill] the mark fills in as it is chosen, for a tile that is switched on
 * and off rather than picked from a set. A [sourceIcon] is a source's own mark, drawn
 * in place of [icon].
 */
@Composable
internal fun KindTile(
    label: String,
    icon: IconKey,
    selected: Boolean,
    dimmed: Boolean,
    onClick: () -> Unit,
    glyphFill: Boolean = false,
    sourceIcon: SourceIcon? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val fill by animateColorAsState(
        when {
            selected -> NxColor.wash(NxColor.lead(), 0.20f)
            hovered -> NxColor.wash(NxInk.quiet, 0.14f)
            else -> NxColor.wash(NxInk.quiet, 0.07f)
        },
        animationSpec = Motion.colorShift.of(),
        label = "tileFill",
    )
    val edge by animateColorAsState(
        if (selected) NxColor.lead() else Color.Transparent,
        animationSpec = Motion.colorShift.of(),
        label = "tileEdge",
    )
    val ink by animateColorAsState(
        when {
            selected -> NxColor.lead()
            hovered -> NxInk.main
            else -> NxInk.quiet
        },
        animationSpec = Motion.colorShift.of(),
        label = "tileInk",
    )
    // The chosen tile's mark rises a little, which is the one motion that says
    // "this one" without a second colour.
    val lift by animateFloatAsState(if (selected) 1f else 0f, animationSpec = Motion.emphasis, label = "tileLift")
    val solid by animateFloatAsState(if (selected && glyphFill) 1f else 0f, animationSpec = Motion.emphasis, label = "tileGlyphFill")
    val shape = MaterialTheme.shapes.medium
    Column(
        Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .heightIn(min = TILE_HEIGHT)
            .alpha(if (dimmed && !selected) 0.5f else 1f)
            .clip(shape)
            .background(fill)
            .border(1.dp, edge, shape)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterVertically),
    ) {
        if (sourceIcon != null) {
            SourceMark(sourceIcon, tint = ink, size = 22.dp, fill = solid, modifier = Modifier.offset { IntOffset(0, ((-2).dp * lift).roundToPx()) })
        } else {
            Symbol(icon, contentDescription = null, tint = ink, fill = solid, size = 22.dp, modifier = Modifier.offset { IntOffset(0, ((-2).dp * lift).roundToPx()) })
        }
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = ink,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

private val TILE_HEIGHT = 64.dp

/**
 * Two or more sources as one switch: a track with a thumb that slides to the chosen
 * one, rather than buttons that light up in turn.
 */
@Composable
private fun SourceSwitch(sources: List<SourceView>, selected: String, onSelect: (String) -> Unit) {
    val shape = MaterialTheme.shapes.medium
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(SWITCH_HEIGHT).clip(shape).background(NxColor.wash(NxInk.quiet, 0.08f)).padding(3.dp),
    ) {
        val cell = maxWidth / sources.size
        val index = sources.indexOfFirst { it.id == selected }.coerceAtLeast(0)
        val at by animateDpAsState(cell * index, animationSpec = Motion.track.of(), label = "sourceThumb")
        Box(
            Modifier.offset { IntOffset(at.roundToPx(), 0) }.width(cell).fillMaxSize()
                .clip(MaterialTheme.shapes.small)
                .background(NxColor.wash(NxColor.lead(), 0.22f)),
        )
        Row(Modifier.fillMaxSize()) {
            sources.forEach { source ->
                val chosen = source.id == selected
                val ink by animateColorAsState(
                    if (chosen) NxColor.lead() else NxInk.quiet,
                    animationSpec = Motion.colorShift.of(),
                    label = "sourceInk",
                )
                Row(
                    Modifier.weight(1f).fillMaxSize().clip(MaterialTheme.shapes.small).clickable { onSelect(source.id) },
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SourceMark(source.icon, tint = ink, size = 16.dp)
                    Text(sourceLabel(source), style = MaterialTheme.typography.labelLarge, color = ink, fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
                }
            }
        }
    }
}

private val SWITCH_HEIGHT = 38.dp

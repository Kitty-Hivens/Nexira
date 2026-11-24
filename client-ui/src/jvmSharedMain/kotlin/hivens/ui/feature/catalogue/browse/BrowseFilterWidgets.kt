package hivens.ui.feature.catalogue.browse

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import hivens.catalogue.api.CatalogueKeys
import hivens.catalogue.api.FilterField
import hivens.catalogue.api.FilterPick
import hivens.catalogue.api.SearchFilter
import hivens.catalogue.api.SourceOption
import hivens.i18n.api.strings
import hivens.ui.components.LoaderGlyph
import hivens.ui.components.hasLoaderGlyph
import hivens.ui.i18n.nexiraStrings
import hivens.ui.icons.IconKey
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.nx.NxButton
import hivens.ui.nx.NxButtonStyle
import hivens.ui.nx.NxField
import hivens.ui.nx.NxSwitch
import hivens.ui.nx.NxTooltip
import hivens.ui.press.clickable
import hivens.ui.puppet.PuppetClick
import hivens.ui.feature.catalogue.project.loaderLabel
import hivens.ui.theme.Motion
import hivens.ui.theme.NxColor
import hivens.ui.theme.NxInk
import hivens.ui.theme.Status
import hivens.ui.widgets.RailBlock
import hivens.widget.api.rememberScopedAction
import hivens.widget.api.rememberScopedCommand
import hivens.widget.api.rememberScopedSource
import hivens.widget.api.valueOrNull
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance

/**
 * The filter rail: one block per filter, after the catalogue's own sidebar.
 *
 * Each block is its own widget, so the reader can reorder them, drop the ones they
 * never touch, or move one somewhere else. A block draws nothing for a kind it does
 * not apply to, which is most of them at any moment: resolutions are a resource
 * pack's, environments a mod's. The slot gives a widget that drew nothing no gap.
 */

private const val CARD = """{"fill":"card","border":{"widthDp":1.0}}"""

@Widget(id = "browse.filter.version", displayName = "widget.browse.filter.version", surface = CARD)
@Composable
fun BrowseVersionFilterWidget(instance: WidgetInstance) = FilterBlock(FilterGroup.GameVersion, instance)

@Widget(id = "browse.filter.loader", displayName = "widget.browse.filter.loader", surface = CARD)
@Composable
fun BrowseLoaderFilterWidget(instance: WidgetInstance) = FilterBlock(FilterGroup.Loader, instance)

@Widget(id = "browse.filter.categories", displayName = "widget.browse.filter.categories", surface = CARD)
@Composable
fun BrowseCategoriesFilterWidget(instance: WidgetInstance) = FilterBlock(FilterGroup.Categories, instance)

@Widget(id = "browse.filter.features", displayName = "widget.browse.filter.features", surface = CARD)
@Composable
fun BrowseFeaturesFilterWidget(instance: WidgetInstance) = FilterBlock(FilterGroup.Features, instance)

@Widget(id = "browse.filter.resolutions", displayName = "widget.browse.filter.resolutions", surface = CARD)
@Composable
fun BrowseResolutionsFilterWidget(instance: WidgetInstance) = FilterBlock(FilterGroup.Resolutions, instance)

@Widget(id = "browse.filter.performance", displayName = "widget.browse.filter.performance", surface = CARD)
@Composable
fun BrowsePerformanceFilterWidget(instance: WidgetInstance) = FilterBlock(FilterGroup.Performance, instance)

@Widget(id = "browse.filter.environment", displayName = "widget.browse.filter.environment", surface = CARD)
@Composable
fun BrowseEnvironmentFilterWidget(instance: WidgetInstance) = FilterBlock(FilterGroup.Environment, instance)

@Widget(id = "browse.filter.license", displayName = "widget.browse.filter.license", surface = CARD)
@Composable
fun BrowseLicenseFilterWidget(instance: WidgetInstance) = FilterBlock(FilterGroup.License, instance)

@Widget(id = "browse.filter.exclusions", displayName = "widget.browse.filter.exclusions", surface = CARD)
@Composable
fun BrowseExclusionsFilterWidget(instance: WidgetInstance) = FilterBlock(FilterGroup.Exclusions, instance)

/**
 * Leaves out what the target pack already holds. Only there while a pack that takes
 * the kind is chosen: without one nothing is held.
 */
@Widget(id = "browse.filter.installed", displayName = "widget.browse.filter.installed", surface = CARD)
@Composable
fun BrowseInstalledFilterWidget(instance: WidgetInstance) {
    val browse = rememberScopedSource(CatalogueKeys.Browse).valueOrNull() ?: return
    // Flipped where the view is kept, since the view read here arrives a frame late.
    val toggleHide = rememberScopedAction(CatalogueKeys.ToggleHideInstalled)
    val s = nexiraStrings()
    if (browse.target?.takes(browse.kind) != true) return
    PuppetClick("browse.filter.installed") { toggleHide?.invoke() }
    Row(
        Modifier.fillMaxWidth().clickable { toggleHide?.invoke() }.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(s.text("browseHideInstalled"), style = MaterialTheme.typography.bodyMedium, color = NxInk.main, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        NxSwitch(checked = browse.hideInstalled, onCheckedChange = { toggleHide?.invoke() })
    }
}

/**
 * The ways the source packs are listed from splits its own list, a block for each
 * filter it declares, named and worded by the source. Only there while packs are listed
 * from a source that declares one. The widget keeps the id it had while the one such
 * filter was who built a mirror pack, so a layout that placed it keeps it.
 */
@Widget(id = "browse.filter.builtBy", displayName = "widget.browse.filter.builtBy", surface = CARD)
@Composable
fun BrowseSourceFiltersWidget(instance: WidgetInstance) {
    val browse = rememberScopedSource(CatalogueKeys.Browse).valueOrNull() ?: return
    // Flipped where the view is kept, since the view read here arrives a frame late.
    val toggle = rememberScopedCommand(CatalogueKeys.ToggleSourceOption)
    val source = browse.source?.takeIf { browse.kind == null } ?: return
    val words = strings(source.module)
    source.filters.forEach { filter ->
        val options = filter.options.map { SourceOption(source.id, filter.id, it.id) to it }
        options.forEach { (pick, _) -> PuppetClick("browse.filter.${filter.id}.${pick.option}") { toggle?.invoke(pick) } }
        // Tiles switched on and off rather than switches: the same plane as the kind
        // tiles above, so the rail reads as one set of controls, with the mark filling
        // in for what is listed.
        RailBlock(words.text(filter.label)) {
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(TILE_GAP)) {
                options.forEach { (pick, option) ->
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        KindTile(
                            label = words.text(option.label),
                            icon = NxIcon.Language,
                            sourceIcon = option.icon,
                            selected = browse.lists(pick),
                            dimmed = false,
                            onClick = { toggle?.invoke(pick) },
                            glyphFill = true,
                        )
                    }
                }
            }
        }
    }
}

/** How a filter row chooses and rules out: the window's browse commands. */
private class FilterActions(val toggle: (FilterPick) -> Unit, val exclude: (FilterPick) -> Unit)

@Composable
private fun rememberFilterActions(): FilterActions {
    val toggle = rememberScopedCommand(CatalogueKeys.ToggleFilter)
    val exclude = rememberScopedCommand(CatalogueKeys.ExcludeFilter)
    return remember(toggle, exclude) { FilterActions({ toggle?.invoke(it) }, { exclude?.invoke(it) }) }
}

/** Which blocks start open: the short ones a reader reaches for first. */
private val FilterGroup.openAtFirst: Boolean
    get() = this == FilterGroup.Loader || this == FilterGroup.Categories || this == FilterGroup.Environment

@Composable
private fun FilterBlock(group: FilterGroup, instance: WidgetInstance) {
    val browse = rememberScopedSource(CatalogueKeys.Browse).valueOrNull() ?: return
    val loadTags = rememberScopedAction(CatalogueKeys.LoadTags)
    LaunchedEffect(loadTags != null) { loadTags?.invoke() }
    val tags = rememberScopedSource(CatalogueKeys.Tags).valueOrNull()
    val unlock = rememberScopedCommand(CatalogueKeys.Unlock)
    val relock = rememberScopedCommand(CatalogueKeys.Relock)
    val act = rememberFilterActions()
    val s = nexiraStrings()
    val type = browse.type ?: return
    var allVersions by rememberSaveable(instance.instanceId) { mutableStateOf(false) }
    val choices = remember(group, type, tags, s, allVersions) { choicesFor(group, type, tags, s, allVersions) }
    if (choices.isEmpty()) return

    val locked = browse.locked.filter { it.field == group.field }
    val values = remember(choices) { choices.mapTo(HashSet()) { it.filter.value } }
    val chosen = browse.chosenFor(type).filter { it.field == group.field && (group == FilterGroup.GameVersion || it.value in values) }
    var open by rememberSaveable(instance.instanceId, group) { mutableStateOf(group.openAtFirst || locked.isNotEmpty()) }
    val turn by animateFloatAsState(if (open) 180f else 0f, animationSpec = Motion.reveal, label = "filterChevron")
    val headerHover = remember { MutableInteractionSource() }
    val headerHovered by headerHover.collectIsHoveredAsState()
    PuppetClick("browse.filter.${group.name}.toggle") { open = !open }

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth()
                .hoverable(headerHover)
                .clickable(interactionSource = headerHover, indication = null) { open = !open }
                .padding(start = 16.dp, end = 12.dp, top = 13.dp, bottom = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                group.title(s),
                style = MaterialTheme.typography.titleMedium,
                color = NxInk.main,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (locked.isNotEmpty()) Symbol(NxIcon.Lock, contentDescription = null, tint = NxInk.quiet, size = 16.dp, modifier = Modifier.padding(end = 6.dp))
            Symbol(NxIcon.ExpandMore, contentDescription = null, tint = if (headerHovered) NxInk.main else NxInk.quiet, size = 20.dp, modifier = Modifier.rotate(turn))
        }
        // Folded, the block still says what it holds, so the reader knows a filter is
        // on without opening every block to look.
        AnimatedVisibility(visible = !open && (chosen.isNotEmpty() || locked.isNotEmpty()), enter = Motion.reveal.enter, exit = Motion.reveal.exit) {
            FlowRow(
                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                (locked + chosen).forEach { SummaryChip(it, locked = it in locked) }
            }
        }
        AnimatedVisibility(visible = open, enter = Motion.reveal.enter, exit = Motion.reveal.exit) {
            Column(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 10.dp)) {
                if (locked.isNotEmpty()) {
                    LockedNotice(
                        packName = browse.target?.name.orEmpty(),
                        values = locked.joinToString(", ") { if (it.field == FilterField.Loader) loaderLabel(it.value) else it.value },
                        onUnlock = { unlock?.invoke(group.field) },
                    )
                    PuppetClick("browse.filter.${group.name}.unlock") { unlock?.invoke(group.field) }
                } else {
                    when (group) {
                        FilterGroup.GameVersion -> VersionChoices(act, type, choices, chosen, allVersions) { allVersions = it }
                        FilterGroup.Loader -> FoldedChoices(act, type, group, choices, chosen)
                        else -> choices.forEach { choice -> ChoiceRow(act, type, group, choice, chosen) }
                    }
                    if (group.field in browse.unlocked && browse.target?.takes(browse.kind) == true) {
                        TextAction(s.text("browseFilterRelock"), NxIcon.Lock) { relock?.invoke(group.field) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryChip(filter: SearchFilter, locked: Boolean) {
    val s = nexiraStrings()
    Row(
        Modifier.clip(MaterialTheme.shapes.extraLarge).background(NxColor.wash(NxInk.quiet, 0.12f)).padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        when {
            locked -> Symbol(NxIcon.Lock, contentDescription = null, tint = NxInk.quiet, size = 12.dp)
            filter.excluded -> Symbol(NxIcon.Block, contentDescription = null, tint = NxColor.status(Status.Error), size = 12.dp)
        }
        Text(filterLabel(filter, s), style = MaterialTheme.typography.labelSmall, color = NxInk.quiet, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/**
 * A filter the target pack decides, shown as decided rather than as choices: what
 * the pack runs, why it is fixed, and the way to take it over, which is a way to find
 * builds that will not run on the pack.
 */
@Composable
private fun LockedNotice(packName: String, values: String, onUnlock: () -> Unit) {
    val s = nexiraStrings()
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp)
            .border(1.dp, NxInk.line, MaterialTheme.shapes.medium)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(values, style = MaterialTheme.typography.bodyMedium, color = NxInk.main, fontWeight = FontWeight.Bold)
        Text(s.text("browseFilterLockedBy", packName), style = MaterialTheme.typography.labelMedium, color = NxInk.quiet)
        Text(s.text("browseFilterUnlockHint"), style = MaterialTheme.typography.labelMedium, color = NxInk.quiet)
        NxButton(label = s.text("browseFilterUnlock"), onClick = onUnlock, style = NxButtonStyle.Secondary, icon = NxIcon.LockOpen, compact = true)
    }
}

/** The game versions: a search over a list that scrolls, and the switch that adds snapshots to it. */
@Composable
private fun VersionChoices(
    c: FilterActions,
    type: String,
    choices: List<FilterChoice>,
    chosen: List<SearchFilter>,
    allVersions: Boolean,
    onAllVersions: (Boolean) -> Unit,
) {
    val s = nexiraStrings()
    var query by remember { mutableStateOf("") }
    NxField(value = query, onValueChange = { query = it }, placeholder = s.text("versionPickerSearch"), modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp))
    val shown = choices.filter { query.isBlank() || it.label.contains(query.trim()) }
    Column(Modifier.fillMaxWidth().heightIn(max = VERSION_LIST_HEIGHT).verticalScroll(rememberScrollState())) {
        shown.forEach { choice -> ChoiceRow(c, type, FilterGroup.GameVersion, choice, chosen) }
    }
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { onAllVersions(!allVersions) }.padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(s.text("browseFilterAllVersions"), style = MaterialTheme.typography.labelLarge, color = NxInk.quiet, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        NxSwitch(checked = allVersions, onCheckedChange = onAllVersions)
    }
    PuppetClick("browse.filter.allVersions") { onAllVersions(!allVersions) }
}

private val VERSION_LIST_HEIGHT = 256.dp

/** The common choices, and the rest behind "show more", which opens rather than jumps. */
@Composable
private fun FoldedChoices(
    c: FilterActions,
    type: String,
    group: FilterGroup,
    choices: List<FilterChoice>,
    chosen: List<SearchFilter>,
) {
    val s = nexiraStrings()
    val first = choices.filter { !it.secondary || chosen.any { f -> f.value == it.filter.value } }
    val rest = choices - first.toSet()
    var more by remember { mutableStateOf(false) }
    first.forEach { choice -> ChoiceRow(c, type, group, choice, chosen) }
    AnimatedVisibility(visible = more, enter = Motion.reveal.enter, exit = Motion.reveal.exit) {
        Column { rest.forEach { choice -> ChoiceRow(c, type, group, choice, chosen) } }
    }
    if (rest.isNotEmpty()) {
        val turn by animateFloatAsState(if (more) 180f else 0f, animationSpec = Motion.reveal, label = "moreChevron")
        TextAction(if (more) s.text("browseFilterShowLess") else s.text("browseFilterShowMore"), NxIcon.ExpandMore, iconRotation = turn) { more = !more }
        PuppetClick("browse.filter.${group.name}.more") { more = !more }
    }
}

@Composable
private fun TextAction(label: String, icon: IconKey, iconRotation: Float = 0f, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val ink by animateColorAsState(if (hovered) NxInk.main else NxInk.quiet, animationSpec = Motion.tap.of(), label = "textActionInk")
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Symbol(icon, contentDescription = null, tint = ink, size = 16.dp, modifier = Modifier.rotate(iconRotation))
        Text(label, style = MaterialTheme.typography.labelLarge, color = ink, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * One choice. A click asks for it, or drops it again. Where the group can also rule
 * a choice out, a second control appears beside the row under the pointer; a
 * group of exclusions only ever rules out, so its click does that.
 *
 * Asked for, the row takes the accent; ruled out, the error colour. The mark at the
 * end says which, and shows faintly under the pointer before the click, so the row
 * says what the click will do.
 */
@Composable
private fun ChoiceRow(
    c: FilterActions,
    type: String,
    group: FilterGroup,
    choice: FilterChoice,
    chosen: List<SearchFilter>,
) {
    val s = nexiraStrings()
    val current = chosen.firstOrNull { it.value == choice.filter.value }
    val included = current != null && !current.excluded
    val excluded = current?.excluded == true
    val onlyExcludes = choice.filter.excluded
    val rowHover = remember { MutableInteractionSource() }
    val hovered by rowHover.collectIsHoveredAsState()
    val error = NxColor.status(Status.Error)
    val fill by animateColorAsState(
        when {
            included -> NxColor.wash(NxColor.lead(), 0.20f)
            excluded -> NxColor.wash(error, 0.20f)
            hovered -> NxColor.wash(NxInk.quiet, 0.10f)
            else -> Color.Transparent
        },
        animationSpec = Motion.tap.of(),
        label = "choiceFill",
    )
    val ink by animateColorAsState(
        if (included || excluded || hovered) NxInk.main else NxInk.quiet,
        animationSpec = Motion.tap.of(),
        label = "choiceInk",
    )
    val markAlpha by animateFloatAsState(
        when {
            included || excluded -> 1f
            hovered -> 0.6f
            else -> 0f
        },
        animationSpec = Motion.tap,
        label = "choiceMark",
    )
    val main = { if (onlyExcludes) c.exclude(FilterPick(type, choice.filter)) else c.toggle(FilterPick(type, choice.filter)) }
    PuppetClick("browse.filter.${group.name}.${choice.filter.value}") { main() }
    // One plane for the whole row, the exclude control inside it, so a chosen row's
    // fill runs to the edge instead of stopping short of a gap kept for a control
    // that is only there under the pointer.
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(fill)
            .hoverable(rowHover)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = main)
            .padding(start = 8.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        choice.mark?.let { ChoiceMarkView(it, ink) }
        Text(
            choice.label,
            style = MaterialTheme.typography.labelLarge,
            color = ink,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(vertical = 3.dp),
        )
        if (group.excludes && !onlyExcludes) {
            val excludeAlpha by animateFloatAsState(if (hovered && !excluded && !included) 1f else 0f, animationSpec = Motion.tap, label = "excludeButton")
            val excludeHover = remember { MutableInteractionSource() }
            val excludeHovered by excludeHover.collectIsHoveredAsState()
            NxTooltip(text = s.text("browseFilterExclude"), enabled = excludeAlpha > 0f) {
                Box(
                    Modifier.size(24.dp).alpha(excludeAlpha).clip(MaterialTheme.shapes.small)
                        .background(if (excludeHovered) NxColor.wash(error, 0.18f) else Color.Transparent)
                        .hoverable(excludeHover)
                        .clickable(interactionSource = excludeHover, indication = null, enabled = excludeAlpha > 0f) { c.exclude(FilterPick(type, choice.filter)) },
                    contentAlignment = Alignment.Center,
                ) {
                    Symbol(NxIcon.Block, contentDescription = s.text("browseFilterExclude"), tint = if (excludeHovered) NxColor.status(Status.Error) else NxInk.quiet, size = 16.dp)
                }
            }
            PuppetClick("browse.filter.${group.name}.${choice.filter.value}.exclude") { c.exclude(FilterPick(type, choice.filter)) }
        }
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            Symbol(
                if (excluded || onlyExcludes) NxIcon.Block else NxIcon.Check,
                contentDescription = null,
                tint = if (excluded || (onlyExcludes && hovered)) NxColor.status(Status.Error) else NxColor.lead(),
                size = 16.dp,
                modifier = Modifier.alpha(markAlpha),
            )
        }
    }
}

@Composable
private fun ChoiceMarkView(mark: ChoiceMark, ink: Color) {
    when (mark) {
        is ChoiceMark.Glyph -> Symbol(mark.icon, contentDescription = null, tint = ink, size = 16.dp)
        is ChoiceMark.Loader -> if (hasLoaderGlyph(mark.name)) {
            LoaderGlyph(mark.name, tint = ink, size = 16.dp)
        } else {
            Symbol(NxIcon.Build, contentDescription = null, tint = ink, size = 16.dp)
        }
        is ChoiceMark.Svg -> svgPainter(mark.source)?.let { painter ->
            Image(painter, contentDescription = null, colorFilter = ColorFilter.tint(ink), modifier = Modifier.size(16.dp))
        }
    }
}


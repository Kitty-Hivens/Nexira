package hivens.ui.feature.catalogue.browse

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import hivens.catalogue.api.CatalogueCard
import hivens.catalogue.api.CatalogueKeys
import hivens.catalogue.api.InstallProblem
import hivens.catalogue.api.InstallRefusal
import hivens.catalogue.api.InstallSubject
import hivens.catalogue.api.ProjectHit
import hivens.catalogue.api.ProjectKind
import hivens.catalogue.api.ProjectTarget
import hivens.catalogue.api.ResultsScroll
import hivens.catalogue.api.ScrollMark
import hivens.catalogue.api.SearchResults
import hivens.catalogue.api.SearchStatus
import hivens.catalogue.api.SkipReason
import hivens.i18n.api.Strings
import hivens.ui.components.LoaderGlyph
import hivens.ui.components.hasLoaderGlyph
import hivens.ui.components.relativeAge
import hivens.ui.i18n.modrinthCategory
import hivens.ui.i18n.nexiraStrings
import hivens.ui.icons.IconKey
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.layers.layerTrigger
import hivens.ui.nx.NxMetaChip
import hivens.ui.nx.NxMetaChipTone
import hivens.ui.nx.NxTooltip
import hivens.ui.nx.RetryStateBlock
import hivens.ui.press.clickable
import hivens.ui.puppet.PuppetClick
import hivens.ui.feature.catalogue.project.compactCount
import hivens.ui.feature.catalogue.project.loaderLabel
import hivens.ui.surface.NxSurface
import hivens.ui.surface.SurfaceKind
import hivens.ui.theme.Motion
import hivens.ui.theme.NxColor
import hivens.ui.theme.NxInk
import hivens.ui.theme.Status
import hivens.ui.theme.decorativeColor
import hivens.widget.api.rememberScopedAction
import hivens.widget.api.rememberScopedCommand

/** What a pack runs, as a reader would write it: "NeoForge 1.21.1". */
internal fun runtimeLabel(mcVersion: String, loader: String): String =
    listOf(loader.takeIf { it.isNotBlank() }?.let(::loaderLabel), mcVersion.takeIf { it.isNotBlank() })
        .filterNotNull()
        .joinToString(" ")

internal fun InstallProblem.label(s: Strings): String = when (this) {
    InstallProblem.NoBuild -> s.text("installTargetNoBuild")
    is InstallProblem.Refused -> when (reason) {
        InstallRefusal.GameRunning -> s.text("installRefusedGameRunning")
        InstallRefusal.Busy -> s.text("installRefusedBusy")
    }
    InstallProblem.NotLanded -> s.text("contentInstallFailed")
    is InstallProblem.LeftOut -> reason.reason(s)
    InstallProblem.SourceCannotInstall -> s.text("installSourceCannotInstall")
}

/** Why an install left a project out, in the reader's words. */
internal fun SkipReason.reason(s: Strings): String = when (this) {
    SkipReason.NoBuild -> s.text("installTargetNoBuild")
    SkipReason.LookupFailed -> s.text("installSkipLookupFailed")
    SkipReason.NotPlaceable -> s.text("installSkipNotPlaceable")
    SkipReason.NotAllowed -> s.text("installTargetNotTaken")
    SkipReason.NameTaken -> s.text("installSkipNameTaken")
    SkipReason.Failed -> s.text("contentInstallFailed")
    SkipReason.NotAttempted -> s.text("installSkipNotAttempted")
    SkipReason.TooDeep -> s.text("installSkipTooDeep")
    SkipReason.PackOwned -> s.text("installSkipPackOwned")
    SkipReason.SwitchedOff -> s.text("installSkipSwitchedOff")
    SkipReason.Present, SkipReason.AlreadyInstalled -> ""
}

/**
 * The catalogue's results for one project type, as the window's search found them.
 *
 * A card opens the project's page, or a modpack's. When the search installs into a
 * pack every card carries an install straight into it. Without one a card carries the
 * install that asks where, see [InstallDialog]. A modpack is not installed into a
 * pack, so its card is only a way to its page.
 */
@Composable
internal fun ProjectResults(
    results: SearchResults,
    kind: ProjectKind?,
    onOpenProject: (ProjectTarget) -> Unit,
    onOpenPack: (CatalogueCard) -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = nexiraStrings()
    val retry = rememberScopedAction(CatalogueKeys.RetryResults)
    val more = rememberScopedAction(CatalogueKeys.MoreResults)
    val scroll = rememberScopedCommand(CatalogueKeys.ScrollResults)
    val installResult = rememberScopedCommand(CatalogueKeys.InstallResult)
    var choosing by remember { mutableStateOf<InstallSubject?>(null) }
    // The pack installs go into, where the search has one that takes the kind.
    val packId = results.question?.packId?.takeIf { kind != null }

    PuppetClick("browse.retry") { retry?.invoke() }

    choosing?.let { subject -> InstallDialog(subject) { choosing = null } }

    when (results.status) {
        SearchStatus.Loading -> BrowseLoading()
        is SearchStatus.Failed -> RetryStateBlock(
            title      = s.text("modBrowserErrorTitle"),
            message    = s.text("modBrowserErrorMessage"),
            retryLabel = s.text("browseRetry"),
            onRetry    = { retry?.invoke() },
            modifier   = modifier.fillMaxSize(),
        )
        SearchStatus.Empty -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(s.text("contentEmpty"), style = MaterialTheme.typography.bodyMedium, color = NxInk.quiet)
        }
        SearchStatus.NoSource -> BrowseEmpty(notice = checkNotNull(browseNotice(SearchStatus.NoSource, "", s)), onRetry = null)
        SearchStatus.Loaded -> BoxWithConstraints(modifier.fillMaxSize()) {
            // The catalogue's own break: a narrow column takes the small icon so the
            // title keeps its width.
            val icon = if (maxWidth >= WIDE_CARD) ICON_WIDE else ICON_NARROW
            val stacked = maxWidth < STACKED_CARD
            // One list per question: it starts where the reader left that question's
            // answer, and says where they leave it under that question's name. Shared
            // across questions, a kept answer shown in place of another kept the other's
            // position, and a position was recorded under the question asked next.
            key(results.question) {
                val question = results.question
                val listState = rememberLazyListState(results.scroll.index, results.scroll.offset)
                DisposableEffect(listState) {
                    onDispose {
                        question?.let { scroll?.invoke(ResultsScroll(it, ScrollMark(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset))) }
                    }
                }
                LaunchedEffect(listState, results.projects.size) {
                    val total = results.projects.size
                    snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
                        .collect { last -> if (last >= total - 3) more?.invoke() }
                }
                LazyColumn(
                    state               = listState,
                    modifier            = Modifier.fillMaxSize(),
                    contentPadding      = PaddingValues(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(CARD_GAP),
                ) {
                    items(items = results.projects, key = { it.projectId }) { hit ->
                        val id = hit.projectId
                        val open = if (kind == null) {
                            { onOpenPack(modpackOf(hit, question?.origin.orEmpty())) }
                        } else {
                            { onOpenProject(ProjectTarget.Catalogue(id, packId)) }
                        }
                        val subject = InstallSubject(id, hit.title, hit.iconUrl, hit.author, source = results.projectSource)
                        val install = if (packId != null) {
                            { installResult?.invoke(id); Unit }
                        } else {
                            { choosing = subject }
                        }
                        val problem = results.problems[id]
                        PuppetClick("browse.project.$id") { open() }
                        if (kind != null) PuppetClick("browse.install.$id") { install() }
                        ProjectCard(
                            hit      = hit,
                            iconSize = icon,
                            stacked  = stacked,
                            note     = problem?.label(s) ?: results.missing[id]?.let { s.text("modPageInstallMissing", it) },
                            onOpen   = open,
                            modifier = Modifier.animateItem(
                                fadeInSpec    = Motion.fade,
                                placementSpec = Motion.reveal.of<IntOffset>(),
                                fadeOutSpec   = Motion.fade,
                            ),
                            action   = if (kind == null) {
                                null
                            } else {
                                {
                                    CardInstall(
                                        phase = when {
                                            id in results.present -> CardPhase.Installed
                                            id in results.working -> CardPhase.Working
                                            problem != null -> CardPhase.Failed
                                            else -> CardPhase.Offered
                                        },
                                        onInstall = install,
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

/** A modpack the project search found, as the pack page of the source it was listed from opens it. */
private fun modpackOf(hit: ProjectHit, origin: String) = CatalogueCard(
    origin = origin,
    id = hit.projectId,
    title = hit.title,
    tagline = hit.description,
    iconUrl = hit.iconUrl,
    bannerUrl = hit.featuredGallery ?: hit.gallery.firstOrNull(),
    tags = hit.categories,
)

/** Where a card's install stands. */
internal enum class CardPhase { Offered, Working, Installed, Failed }

/**
 * A card's install, in the catalogue's outlined accent: offered, under way with a
 * spinner, done and disabled, or failed with the action offered again.
 *
 * One control changing what it says rather than four controls swapped in and out,
 * so the eye stays on the place it clicked. A failure keeps the action and the
 * card says why under its summary: silence after a click reading as success is the
 * failure this replaced.
 */
@Composable
internal fun CardInstall(phase: CardPhase, onInstall: () -> Unit) {
    val s = nexiraStrings()
    // Resolved here: a role is read in composition, and the transition is built outside it.
    val tap = Motion.tap
    AnimatedContent(
        targetState    = phase,
        transitionSpec = {
            (fadeIn(tap.of()) + scaleIn(tap.of(), initialScale = 0.9f)) togetherWith
                fadeOut(tap.of()) using SizeTransform(clip = false) { _, _ -> tap.of() }
        },
        contentAlignment = Alignment.CenterEnd,
        label          = "cardInstall",
    ) { p ->
        when (p) {
            CardPhase.Offered -> OutlinedAction(s.text("browseDetailInstallButton"), NxColor.lead(), NxColor.lead(), onClick = onInstall) {
                Symbol(NxIcon.Download, contentDescription = null, tint = NxColor.lead(), size = ACTION_ICON)
            }
            CardPhase.Working -> OutlinedAction(s.text("modPageInstalling"), NxInk.line, NxInk.quiet, onClick = null) {
                CircularProgressIndicator(color = NxColor.lead(), strokeWidth = 2.dp, modifier = Modifier.size(ACTION_ICON - 2.dp))
            }
            CardPhase.Installed -> OutlinedAction(s.text("installInstalled"), NxInk.line, NxInk.quiet, onClick = null) {
                Symbol(NxIcon.Check, contentDescription = null, tint = NxColor.lead(), size = ACTION_ICON)
            }
            CardPhase.Failed -> OutlinedAction(s.text("contentInstallRetry"), NxColor.status(Status.Error), NxColor.status(Status.Error), onClick = onInstall) {
                Symbol(NxIcon.Refresh, contentDescription = null, tint = NxColor.status(Status.Error), size = ACTION_ICON)
            }
        }
    }
}

/**
 * The outlined pill a card's action is drawn as: [edge] for the outline, [ink] for
 * the label. With no [onClick] it is a statement rather than a control, so the
 * outline dims and nothing answers the pointer.
 */
@Composable
private fun OutlinedAction(
    label: String,
    edge: Color,
    ink: Color,
    onClick: (() -> Unit)?,
    leading: @Composable () -> Unit,
) {
    val shape = MaterialTheme.shapes.small
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val fill by animateColorAsState(
        targetValue   = if (hovered && onClick != null) NxColor.wash(edge, 0.14f) else Color.Transparent,
        animationSpec = Motion.tap.of(),
        label         = "actionFill",
    )
    Row(
        modifier = Modifier
            .clip(shape)
            .background(fill)
            .border(1.dp, edge.copy(alpha = if (onClick != null) 0.85f else 0.5f), shape)
            .hoverable(interaction, enabled = onClick != null)
            .then(if (onClick != null) Modifier.layerTrigger().clickable(interactionSource = interaction, indication = null, onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        leading()
        Text(label, style = MaterialTheme.typography.labelLarge, color = ink, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/**
 * One search result, laid out the way the catalogue lays its own: the mark on the
 * left, the name with its author and the summary beside it, the tags along the
 * bottom, and the numbers down the right edge under the action.
 *
 * [note] is said under the summary, for what went wrong with the card's install.
 */
@Composable
internal fun ProjectCard(
    hit: ProjectHit,
    modifier: Modifier = Modifier,
    iconSize: Dp = ICON_WIDE,
    /** The numbers and the action under the text rather than beside it, for a column too narrow to spare the width. */
    stacked: Boolean = false,
    note: String? = null,
    onOpen: (() -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val s = nexiraStrings()
    val shape = MaterialTheme.shapes.medium
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    // The edge warms toward the accent under the pointer, which is how a card that
    // opens on click says so before it is clicked.
    val edge by animateColorAsState(
        targetValue   = if (hovered && onOpen != null) NxColor.wash(NxColor.lead(), 0.55f) else Color.Transparent,
        animationSpec = Motion.tap.of(),
        label         = "cardEdge",
    )
    NxSurface(
        SurfaceKind.Card,
        shape    = shape,
        modifier = modifier.fillMaxWidth().border(1.dp, edge, shape),
    ) {
        val area = Modifier
            .fillMaxWidth()
            .hoverable(interaction)
            .then(if (onOpen != null) Modifier.clickable(interactionSource = interaction, indication = null, onClick = onOpen) else Modifier)
            .padding(CARD_PADDING)
        val text = @Composable { width: Modifier ->
            Column(width, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        hit.title,
                        style      = MaterialTheme.typography.titleLarge,
                        fontSize   = TITLE,
                        color      = NxInk.main,
                        fontWeight = FontWeight.SemiBold,
                        maxLines   = 1,
                        overflow   = TextOverflow.Ellipsis,
                        modifier   = Modifier.weight(1f, fill = false),
                    )
                    if (hit.author.isNotBlank()) {
                        Text(
                            s.text("browseByAuthor", hit.author),
                            style    = MaterialTheme.typography.bodyMedium,
                            color    = NxInk.quiet,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(bottom = 2.dp),
                        )
                    }
                }
                if (hit.description.isNotBlank()) {
                    Text(
                        hit.description,
                        style    = MaterialTheme.typography.bodyMedium,
                        color    = NxInk.main,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                note?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = NxColor.status(Status.Error), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                ProjectTags(hit.displayCategories.ifEmpty { hit.categories }, max = if (action != null) TAGS_WITH_ACTION else TAGS)
            }
        }
        val stats = @Composable {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Stat(NxIcon.Download, hit.downloads, s.text("modPageStatDownloads"))
                Stat(NxIcon.Favorite, hit.follows, s.text("modPageStatFollowers"))
            }
        }
        if (stacked) {
            Column(area, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ProjectIcon(hit, iconSize)
                    text(Modifier.weight(1f))
                }
                // A row gave the action what the numbers left, and cut its label short
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalArrangement   = Arrangement.spacedBy(8.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    stats()
                    action?.invoke()
                }
            }
        } else {
            Row(area, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ProjectIcon(hit, iconSize)
                text(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    action?.invoke()
                    stats()
                    if (hit.dateModified.isNotBlank()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Symbol(NxIcon.History, contentDescription = null, tint = NxInk.quiet, size = 16.dp)
                            Text(
                                s.text("modUpdatedOn", relativeAge(hit.dateModified, s)),
                                style    = MaterialTheme.typography.labelMedium,
                                color    = NxInk.quiet,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A count with its mark, compact on the card and whole in the tooltip. */
@Composable
private fun Stat(icon: IconKey, n: Long, unit: String) {
    val s = nexiraStrings()
    NxTooltip(text = "%,d %s".format(n, unit)) {
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
            Symbol(icon, contentDescription = null, tint = NxInk.quiet, size = STAT_ICON)
            Text(compactCount(n, s), style = MaterialTheme.typography.bodyMedium, color = NxInk.main, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

/**
 * The catalogue's tags for a card, loaders with their own mark, then a count of
 * the rest. One line: a chip is placed at its own width or not at all.
 */
@Composable
private fun ProjectTags(tags: List<String>, max: Int) {
    if (tags.isEmpty()) return
    val s = nexiraStrings()
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
        maxLines              = 1,
    ) {
        tags.take(max).forEach { tag ->
            val loader = hasLoaderGlyph(tag)
            NxMetaChip(
                if (loader) loaderLabel(tag) else s.modrinthCategory(tag),
                tone    = NxMetaChipTone.Surface,
                leading = if (loader) {
                    { LoaderGlyph(tag, tint = NxInk.quiet, size = 12.dp) }
                } else {
                    null
                },
            )
        }
        val hidden = tags.size - max
        if (hidden > 0) NxMetaChip("+$hidden", tone = NxMetaChipTone.Surface)
    }
}

@Composable
private fun ProjectIcon(hit: ProjectHit, size: Dp) {
    val shape = MaterialTheme.shapes.medium
    if (hit.iconUrl != null) {
        AsyncImage(model = hit.iconUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size).clip(shape))
    } else {
        Box(Modifier.size(size).clip(shape).background(decorativeColor(hit.title)), contentAlignment = Alignment.Center) {
            Text(
                hit.title.firstOrNull()?.uppercase() ?: "?",
                style      = if (size >= ICON_WIDE) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,
                color      = Color.White,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**
 * The card's measurements, taken off the catalogue's own list: sixteen inside, a
 * hundred for the mark and sixty-four below the width where the column cannot
 * spare it, twenty for the name.
 */
private val CARD_PADDING = 16.dp
private val CARD_GAP = 10.dp
internal val ICON_WIDE = 100.dp
internal val ICON_NARROW = 64.dp
private val WIDE_CARD = 850.dp
private val STACKED_CARD = 560.dp
private val TITLE = 20.sp
private val STAT_ICON = 20.dp
private val ACTION_ICON = 16.dp

/** Tags named before counting: one fewer when the action takes part of the width. */
private const val TAGS = 5
private const val TAGS_WITH_ACTION = 4

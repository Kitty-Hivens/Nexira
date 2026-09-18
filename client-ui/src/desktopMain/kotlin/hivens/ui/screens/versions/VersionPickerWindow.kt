package hivens.ui.screens.versions

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import hivens.core.update.VersionChannel
import hivens.ui.components.ChannelChip
import hivens.ui.components.ReleaseNotes
import hivens.ui.components.channelColor
import hivens.ui.components.formatBuildTimestamp
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.nx.CenteredProgress
import hivens.ui.nx.NxButton
import hivens.ui.nx.NxButtonStyle
import hivens.ui.nx.NxField
import hivens.ui.nx.NxIconButton
import hivens.ui.nx.NxMetaChip
import hivens.ui.nx.NxMetaChipTone
import hivens.ui.nx.NxVerticalScrollbar
import hivens.ui.puppet.PuppetClick
import hivens.ui.surface.NxSurface
import hivens.ui.surface.NxSurfaceLevel
import hivens.ui.theme.NxTheme
import hivens.ui.theme.decorativeColor

/**
 * One version as the picker draws it, mapped by whichever host opened it: the
 * catalogue (a pack not installed yet) or an installed instance.
 */
data class PickerVersion(
    val id: String,
    val label: String,
    val channel: VersionChannel,
    val publishedAt: String? = null,
    val changelog: String? = null,
    /** Second line of the row, e.g. "Minecraft 1.12.2 . Forge". */
    val runtimeLine: String? = null,
    /**
     * Download size, when the source publishes one per version. The mirror
     * describes a build rather than a file, so it passes null and the fact is
     * simply absent.
     */
    val sizeLabel: String? = null,
    val installed: Boolean = false,
    val latest: Boolean = false,
    /**
     * Whether this build runs where it is being offered.
     *
     * The host used to drop everything that did not fit, which answers the
     * question by making it unaskable: a reader looking for a build that exists
     * but does not match saw an absence and could not tell it from a project
     * that never shipped one. Marked and hidden by default is the same result
     * with a way back.
     */
    val compatible: Boolean = true,
)

/** Which way picking [target] would move the instance, so the action can say so. */
enum class PickerIntent { Install, Upgrade, Rollback, Switch }

/**
 * The version picker, shared by the catalogue's install flow and an instance's
 * version change.
 *
 * Master and detail, and the proportions are the whole of it. The window used to
 * take 88 percent of the app in both axes with a proportional split, so eight
 * builds arrived inside a hall: four hundred empty pixels under the list and
 * seven hundred beside it. It was then briefly rebuilt as a single column of fat
 * rows, which fixed the emptiness by throwing away the structure -- a list of
 * builds IS master and detail, because the list answers "which one" and the notes
 * answer "why".
 *
 * So the shape is back and the numbers are not: a ceiling rather than a
 * fraction, a rail of a fixed width rather than a share of whatever is going,
 * and a row that carries what a row can read at a glance. The date, the runtime
 * and the notes belong to the detail, where there is room to set them.
 *
 * Builds that cannot run here are MARKED and folded away rather than dropped.
 * Dropping them answers "is there a build for me" by making the question
 * unaskable: an absence reads the same whether the project never shipped one or
 * shipped one for another loader.
 */
@Composable
fun VersionPickerWindow(
    title: String,
    packName: String,
    /**
     * Anything the image loader accepts: a URL for a catalogue pack, the icon
     * bytes a mod jar carries. It used to be typed as a URL, so every mod whose
     * icon came out of its own archive fell through to the initials tile.
     */
    packIcon: Any?,
    versions: List<PickerVersion>,
    intentFor: (PickerVersion) -> PickerIntent,
    onConfirm: (PickerVersion) -> Unit,
    onDismiss: () -> Unit,
    busyVersionId: String? = null,
    warning: String? = null,
    /**
     * The list is still being fetched. A host whose versions are already in hand
     * leaves this alone; one that opens the window and then asks the network
     * would otherwise show an empty list, which reads as "this has no versions".
     */
    loading: Boolean = false,
) {
    val s = LocalStrings.current
    val colors = NxTheme.colors
    val busy = busyVersionId != null

    var query by remember { mutableStateOf("") }
    var showIncompatible by remember(versions) { mutableStateOf(false) }
    var selectedId by remember(versions) {
        mutableStateOf(versions.firstOrNull { it.installed }?.id ?: versions.firstOrNull { it.compatible }?.id)
    }
    val hiddenCount = remember(versions) { versions.count { !it.compatible } }
    val shown = remember(versions, query, showIncompatible) {
        versions
            .filter { showIncompatible || it.compatible || it.installed }
            .filter { query.isBlank() || it.label.contains(query, ignoreCase = true) }
    }
    // A query that hides the selection would leave the detail pane describing a
    // row the reader can no longer see; follow the filter instead.
    LaunchedEffect(shown) {
        if (shown.none { it.id == selectedId }) selectedId = shown.firstOrNull()?.id
    }
    val selected = versions.firstOrNull { it.id == selectedId }

    // In-composition overlay rather than a Popup: the window belongs to the app's
    // own surface stack, so it inherits the theme, sizes against the app window,
    // and cannot outlive its host as a separate top-level layer. Scrim dismisses,
    // Esc dismisses, the card swallows its own clicks.
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown && ev.key == Key.Escape) {
                    onDismiss(); true
                } else {
                    false
                }
            }
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        NxSurface(
            level = NxSurfaceLevel.Raised,
            blurDp = 0f,
            opacity = 1f,
            modifier = Modifier
                // Margin first, then the ceiling, then take what is left. A
                // fraction cannot express this in either order: `fillMax` fixes
                // the size, so a ceiling after it has nothing to clamp, and a
                // ceiling before it makes the fraction a fraction of the ceiling.
                .padding(horizontal = WINDOW_MARGIN, vertical = WINDOW_MARGIN)
                .widthIn(max = CARD_WIDTH)
                .heightIn(max = CARD_HEIGHT)
                .fillMaxWidth()
                .fillMaxHeight()
                .clip(MaterialTheme.shapes.medium)
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = {}),
        ) {
            Column(Modifier.fillMaxSize()) {
                Header(title, packName, packIcon, onDismiss)
                HorizontalDivider(color = colors.outline.copy(alpha = 0.25f))
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    VersionRail(
                        versions = shown,
                        loading = loading,
                        query = query,
                        onQuery = { query = it },
                        selectedId = selectedId,
                        onSelect = { selectedId = it },
                        hiddenCount = hiddenCount,
                        showIncompatible = showIncompatible,
                        onToggleIncompatible = { showIncompatible = !showIncompatible },
                    )
                    Box(Modifier.width(1.dp).fillMaxHeight().background(colors.outline.copy(alpha = 0.25f)))
                    DetailPane(selected, Modifier.weight(1f).fillMaxHeight())
                }
                Footer(
                    warning = warning,
                    selected = selected,
                    intent = selected?.let(intentFor),
                    busy = busy,
                    busyThis = selected != null && selected.id == busyVersionId,
                    onConfirm = { selected?.let(onConfirm) },
                    onDismiss = onDismiss,
                )
            }
        }
    }
}

/**
 * Ceilings, not fractions. The content does not grow with the display, so a
 * window that does only buys more emptiness.
 */
private val CARD_WIDTH = 928.dp
private val CARD_HEIGHT = 550.dp
private val WINDOW_MARGIN = 48.dp

/** The rail is a fixed measure: a list of numbers wants the same room everywhere. */
private val RAIL_WIDTH = 300.dp

/** One row, and it is a constant so the list reads as a column rather than a stack. */
private val ROW_HEIGHT = 40.dp

private const val SEARCH_THRESHOLD = 8

// --- Zones -----------------------------------------------------------------

@Composable
private fun Header(title: String, packName: String, icon: Any?, onDismiss: () -> Unit) {
    val s = LocalStrings.current
    val colors = NxTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PackAvatar(icon, packName)
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = packName,
                style = MaterialTheme.typography.labelSmall,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        NxIconButton(icon = NxIcon.Close, contentDescription = s.packSettingsClose, onClick = onDismiss)
    }
}

@Composable
private fun VersionRail(
    versions: List<PickerVersion>,
    loading: Boolean,
    query: String,
    onQuery: (String) -> Unit,
    selectedId: String?,
    onSelect: (String) -> Unit,
    hiddenCount: Int,
    showIncompatible: Boolean,
    onToggleIncompatible: () -> Unit,
) {
    val s = LocalStrings.current
    // Its own plane. The list and the notes have to read as two places, not as
    // one field with a rule down the middle.
    // No hairline of its own. Every surface draws one by default, which is right
    // for a plane floating on a page and wrong for one sitting flush inside
    // another: the rail meets the card on three sides, so its edge doubled the
    // card's, and the two crossed the header and footer rules at the corners. The
    // tone step is the separation here, which is what the ladder is for -- the
    // hairline is the SECOND signal, and a second signal inside a card is noise.
    NxSurface(
        // Base, not Sunken. The field inside is a Sunken surface, and a Sunken
        // field on a Sunken rail has no tone step between them at all, so the
        // field held itself up on its hairline alone and read as a frame laid on
        // the plane rather than a well cut into it. One rung up gives the card,
        // the rail and the field three tones in order.
        level = NxSurfaceLevel.Base,
        blurDp = 0f,
        borderWidthDp = 0f,
        shape = RectangleShape,
        modifier = Modifier.width(RAIL_WIDTH).fillMaxHeight(),
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                if (versions.size > SEARCH_THRESHOLD || query.isNotBlank()) {
                    NxField(
                        value = query,
                        onValueChange = onQuery,
                        placeholder = s.versionPickerSearch,
                        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 6.dp),
                    )
                }
                val listState = rememberLazyListState()
                val hover = remember { MutableInteractionSource() }
                val hovered by hover.collectIsHoveredAsState()
                Box(Modifier.weight(1f).hoverable(hover)) {
                    if (loading) CenteredProgress(Modifier.fillMaxSize())
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        contentPadding = PaddingValues(top = 4.dp, bottom = if (hiddenCount > 0) 56.dp else 8.dp),
                    ) {
                        items(versions, key = { it.id }) { v ->
                            VersionRow(v, selected = v.id == selectedId, onClick = { onSelect(v.id) })
                            PuppetClick("versionPicker.select.${v.id}") { onSelect(v.id) }
                        }
                    }
                    NxVerticalScrollbar(
                        adapter = rememberScrollbarAdapter(listState),
                        revealed = hovered || listState.isScrollInProgress,
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                    )
                }
            }
            // The control floats over a fade rather than taking a row of its own:
            // the list runs under it and keeps the height it had.
            if (hiddenCount > 0) {
                Box(
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(72.dp)
                        .background(
                            // Solid by seven tenths, then held. A plain two-stop
                            // gradient only reaches the plane's colour at the very
                            // last pixel, so the control sitting above that edge
                            // had a list row showing through it.
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                0.7f to NxTheme.colors.surface,
                                1f to NxTheme.colors.surface,
                            ),
                        ),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    NxButton(
                        label = if (showIncompatible) s.versionPickerHideIncompatible
                                else s.versionPickerShowIncompatible(hiddenCount),
                        onClick = onToggleIncompatible,
                        style = NxButtonStyle.Tertiary,
                        icon = if (showIncompatible) NxIcon.VisibilityOff else NxIcon.Visibility,
                        compact = true,
                        modifier = Modifier.padding(bottom = 10.dp),
                    )
                }
            }
        }
    }
}

/**
 * One line, and the line is the target. The channel dot reads before a word
 * does; the badge on the right carries state. Nothing else fits at this measure
 * and nothing else needs to: the date, the runtime and the notes are one click
 * away in a pane built to hold them.
 */
@Composable
private fun VersionRow(v: PickerVersion, selected: Boolean, onClick: () -> Unit) {
    val s = LocalStrings.current
    val colors = NxTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .clip(MaterialTheme.shapes.medium)
            .background(if (selected) colors.primary.copy(alpha = 0.16f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(channelColor(v.channel)))
        Text(
            text = v.label,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (selected || v.installed) FontWeight.SemiBold else FontWeight.Normal,
            color = if (v.compatible) colors.textPrimary else colors.textSecondary,
            maxLines = 1,
            // Middle, not tail: what separates one `SNAPSHOT-0.0.0-...` from the
            // next is the date at its END, and a tail ellipsis turns the whole
            // snapshot chain into identical rows.
            overflow = TextOverflow.MiddleEllipsis,
            modifier = Modifier.weight(1f),
        )
        when {
            !v.compatible -> Symbol(
                NxIcon.Warning,
                contentDescription = s.versionPickerIncompatible,
                tint = colors.warnAccent,
                size = 15.dp,
            )
            v.installed -> NxMetaChip(s.packVersionCurrentTag, tone = NxMetaChipTone.Success)
            v.latest -> NxMetaChip(s.packVersionsLatestTag, tone = NxMetaChipTone.Surface)
        }
    }
}

@Composable
private fun DetailPane(v: PickerVersion?, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    val colors = NxTheme.colors
    if (v == null) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(s.versionPickerEmpty, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        }
        return
    }
    Column(modifier) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = v.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                ChannelChip(v.channel)
                // Not dimmed. The date is one of the two facts a reader came for,
                // and a fact set in the caption colour reads as an aside.
                formatBuildTimestamp(v.publishedAt)?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = colors.textPrimary)
                }
            }
            // Runtime and size on one line, separated by a dot rather than by a
            // gap: two facts of the same kind, read together or not at all.
            val facts = listOfNotNull(v.runtimeLine, v.sizeLabel)
            if (facts.isNotEmpty()) {
                Text(
                    facts.joinToString("  ·  "),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textSecondary,
                )
            }
            if (!v.compatible) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Symbol(NxIcon.Warning, contentDescription = null, tint = colors.warnAccent, size = 16.dp)
                    Text(
                        s.versionPickerIncompatible,
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.warnAccent,
                    )
                }
            }
        }
        HorizontalDivider(color = colors.outline.copy(alpha = 0.2f))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val scroll = rememberScrollState()
            val notes = v.changelog?.takeIf { it.isNotBlank() }
            if (notes != null) {
                Box(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 18.dp, vertical = 14.dp)) {
                    ReleaseNotes(notes, Modifier.fillMaxWidth())
                }
                // Text fades out at the bottom edge instead of being cut by it.
                Box(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(48.dp)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, colors.surface))),
                )
            } else {
                // Most mirror builds ship no notes, so this is the pane's ordinary
                // state rather than an exception.
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(s.versionPickerNoChangelog, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                }
            }
        }
    }
}

@Composable
private fun Footer(
    warning: String?,
    selected: PickerVersion?,
    intent: PickerIntent?,
    busy: Boolean,
    busyThis: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val s = LocalStrings.current
    val colors = NxTheme.colors
    HorizontalDivider(color = colors.outline.copy(alpha = 0.25f))
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (warning != null) {
            Symbol(NxIcon.Warning, contentDescription = null, tint = colors.warnAccent, size = 16.dp)
            Text(
                text = warning,
                style = MaterialTheme.typography.labelSmall,
                color = colors.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        NxButton(
            label = s.createPackCancel,
            onClick = onDismiss,
            style = NxButtonStyle.Tertiary,
            enabled = !busy,
            compact = true,
        )
        if (busyThis) {
            CircularProgressIndicator(color = colors.primary, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
        }
        // ONE action, and its label states the outcome including direction. The
        // build already on disk is not somewhere to go: the row says "current"
        // and the action refuses, rather than offering a download that changes
        // nothing and a minute of wondering whether it worked.
        val label = when {
            selected?.installed == true -> s.packVersionCurrentTag
            else -> when (intent) {
                PickerIntent.Install -> s.versionPickerInstall(selected?.label.orEmpty())
                PickerIntent.Upgrade -> s.versionPickerUpgrade(selected?.label.orEmpty())
                PickerIntent.Rollback -> s.versionPickerRollback(selected?.label.orEmpty())
                PickerIntent.Switch, null -> s.versionPickerSwitch(selected?.label.orEmpty())
            }
        }
        val actionable = selected != null && !selected.installed && !busy
        PuppetClick("versionPicker.confirm", enabled = actionable) { onConfirm() }
        NxButton(
            label = label,
            onClick = onConfirm,
            icon = NxIcon.Download.takeIf { actionable },
            enabled = actionable,
            compact = true,
        )
    }
}

@Composable
private fun PackAvatar(icon: Any?, name: String) {
    val box = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
    val tint = NxTheme.colors.decorativeColor(name)
    SubcomposeAsyncImage(
        model = icon,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = box,
        loading = { Box(Modifier.fillMaxSize().background(tint)) },
        error = {
            Box(Modifier.fillMaxSize().background(tint), contentAlignment = Alignment.Center) {
                Text(
                    text = name.firstOrNull()?.uppercase() ?: "?",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
    )
}

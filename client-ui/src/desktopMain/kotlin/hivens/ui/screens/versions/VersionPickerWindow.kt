package hivens.ui.screens.versions

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.Color
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
import com.mikepenz.markdown.m3.Markdown
import hivens.core.update.VersionChannel
import hivens.ui.components.ChannelChip
import hivens.ui.components.channelColor
import hivens.ui.components.formatBuildTimestamp
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
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
import hivens.ui.theme.Form
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
    /** Second line of the detail pane, e.g. "Minecraft 1.12.2 . Forge". */
    val runtimeLine: String? = null,
    val installed: Boolean = false,
    val latest: Boolean = false,
)

/** Which way picking [target] would move the instance, so the action can say so. */
enum class PickerIntent { Install, Upgrade, Rollback, Switch }

/**
 * The version picker, shared by the catalogue's install flow and an installed
 * instance's version change. One surface, two hosts: the two used to be a rich
 * screen and a stunted list of download buttons, and the stunted one was what a
 * new user met first.
 *
 * Composed as four zones rather than one slab, because that division IS the
 * drawing: a header that names the action, a list panel that carries state
 * (channel, current, date), a detail panel that answers "what changes", and a
 * footer that holds the single labelled action. A row is one line and the row
 * itself is the target, so the eye lands on the version rather than on a column
 * of identical buttons.
 */
@Composable
fun VersionPickerWindow(
    title: String,
    packName: String,
    packIconUrl: String?,
    versions: List<PickerVersion>,
    intentFor: (PickerVersion) -> PickerIntent,
    onConfirm: (PickerVersion) -> Unit,
    onDismiss: () -> Unit,
    busyVersionId: String? = null,
    warning: String? = null,
) {
    val colors = NxTheme.colors
    val busy = busyVersionId != null

    var query by remember { mutableStateOf("") }
    var selectedId by remember(versions) {
        mutableStateOf(versions.firstOrNull { it.installed }?.id ?: versions.firstOrNull()?.id)
    }
    val shown = remember(versions, query) {
        if (query.isBlank()) versions
        else versions.filter { it.label.contains(query, ignoreCase = true) }
    }
    // A query that hides the selection would leave the detail pane describing a
    // row the user can no longer see; follow the filter instead.
    LaunchedEffect(shown) {
        if (shown.none { it.id == selectedId }) selectedId = shown.firstOrNull()?.id
    }
    val selected = versions.firstOrNull { it.id == selectedId }

    // In-composition overlay rather than a Popup: the window belongs to the app's
    // own surface stack, so it inherits the theme, sizes against the app window,
    // and cannot outlive its host as a separate top-level layer. Same grammar as
    // the pack-settings window -- scrim dismisses, Esc dismisses, the card
    // swallows its own clicks.
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
            // Fraction of the app window with no dp ceiling: a bigger screen gets a
            // bigger window, not the same island floating in more emptiness.
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .fillMaxHeight(0.90f)
                .clip(RoundedCornerShape(Form.cardCorner))
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = {}),
        ) {
            Column(Modifier.fillMaxSize()) {
                Header(title, packName, packIconUrl, onDismiss)
                HorizontalDivider(color = colors.outline.copy(alpha = 0.25f))
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    // Proportional, not a fixed 300dp rail: the list has to grow
                    // with the window or it turns into a slot in a field of notes.
                    ListPanel(
                        versions = shown,
                        total = versions.size,
                        query = query,
                        onQuery = { query = it },
                        selectedId = selectedId,
                        onSelect = { selectedId = it },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                    DetailPanel(selected, Modifier.weight(2f).fillMaxHeight())
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

// --- Zones -----------------------------------------------------------------

@Composable
private fun Header(title: String, packName: String, iconUrl: String?, onDismiss: () -> Unit) {
    val s = LocalStrings.current
    val colors = NxTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PackAvatar(iconUrl, packName)
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
private fun PackAvatar(iconUrl: String?, name: String) {
    val box = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp))
    val tint = NxTheme.colors.decorativeColor(name)
    SubcomposeAsyncImage(
        model = iconUrl,
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

@Composable
private fun ListPanel(
    versions: List<PickerVersion>,
    total: Int,
    query: String,
    onQuery: (String) -> Unit,
    selectedId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    val colors = NxTheme.colors
    // Its own sunken plane: the list and the detail must read as two places, not
    // as one field with a gap down the middle.
    NxSurface(level = NxSurfaceLevel.Sunken, blurDp = 0f, modifier = modifier) {
        Column(Modifier.fillMaxSize()) {
            // Search earns its place at 39 builds; without it the only way to a
            // year-old version is scrolling.
            if (total > SEARCH_THRESHOLD) {
                NxField(
                    value = query,
                    onValueChange = onQuery,
                    placeholder = s.versionPickerSearch,
                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                )
            }
            val listState = rememberLazyListState()
            // The bar is the only cue for how deep the list runs, so it has to show
            // on hover too -- scroll-only means it appears once you already guessed.
            val hover = remember { MutableInteractionSource() }
            val hovered by hover.collectIsHoveredAsState()
            Box(Modifier.weight(1f).hoverable(hover)) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
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
            Text(
                text = s.versionPickerCount(total),
                style = MaterialTheme.typography.labelSmall,
                color = colors.textSecondary,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

/**
 * One line, and the line is the target. The leading dot carries the channel, so
 * a beta is legible before reading a word; the trailing chip carries state. The
 * action is NOT here: repeating it per row is what turned the old picker into a
 * column of identical buttons.
 */
@Composable
private fun VersionRow(v: PickerVersion, selected: Boolean, onClick: () -> Unit) {
    val s = LocalStrings.current
    val colors = NxTheme.colors
    val shape = RoundedCornerShape(Form.buttonCorner)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) colors.primary.copy(alpha = 0.16f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.size(7.dp).clip(RoundedCornerShape(2.dp)).background(channelColor(v.channel)),
        )
        Text(
            text = v.label,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (selected || v.installed) FontWeight.SemiBold else FontWeight.Normal,
            color = colors.textPrimary,
            maxLines = 1,
            // Middle, not tail: what separates one `SNAPSHOT-0.0.0-...` from the
            // next is the date at its END, and a tail ellipsis turns the whole
            // snapshot chain into identical rows.
            overflow = TextOverflow.MiddleEllipsis,
            // The single flexible child. A trailing weighted spacer would split the
            // free space with it, so the label lost half the row to blank whenever
            // a badge was present and ellipsised a version that fit.
            modifier = Modifier.weight(1f),
        )
        when {
            v.installed -> NxMetaChip(s.packVersionCurrentTag, tone = NxMetaChipTone.Success)
            v.latest -> NxMetaChip(s.packVersionsLatestTag, tone = NxMetaChipTone.Surface)
        }
    }
}

@Composable
private fun DetailPanel(v: PickerVersion?, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    val colors = NxTheme.colors
    if (v == null) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(s.versionPickerEmpty, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        }
        return
    }
    Column(modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = v.label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // Same anatomy as a list row: the value owns the flexible space,
                // the badges and the date sit against the right edge.
                modifier = Modifier.weight(1f),
            )
            ChannelChip(v.channel)
            formatBuildTimestamp(v.publishedAt)?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
            }
        }
        // Runtime once, here, instead of repeated on every row of the list.
        v.runtimeLine?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
        }
        HorizontalDivider(color = colors.outline.copy(alpha = 0.2f))
        val notes = v.changelog?.takeIf { it.isNotBlank() }
        if (notes != null) {
            Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                Markdown(content = notes)
            }
        } else {
            // Most mirror builds ship no notes, so this is the pane's ordinary
            // state rather than an exception. Pinned to the top edge it reads as a
            // caption for a paragraph that failed to load.
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(s.versionPickerNoChangelog, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
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
    NxSurface(level = NxSurfaceLevel.Sunken, blurDp = 0f, modifier = Modifier.fillMaxWidth()) {
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
                CircularProgressIndicator(
                    color = colors.primary,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp),
                )
            }
            // ONE action, and its label states the outcome including direction.
            val label = when (intent) {
                PickerIntent.Install -> s.versionPickerInstall(selected?.label.orEmpty())
                PickerIntent.Upgrade -> s.versionPickerUpgrade(selected?.label.orEmpty())
                PickerIntent.Rollback -> s.versionPickerRollback(selected?.label.orEmpty())
                PickerIntent.Switch, null -> s.versionPickerSwitch(selected?.label.orEmpty())
            }
            PuppetClick("versionPicker.confirm", enabled = selected != null && !busy) { onConfirm() }
            NxButton(
                label = label,
                onClick = onConfirm,
                icon = NxIcon.Download,
                enabled = selected != null && !busy,
                compact = true,
            )
        }
    }
}

private const val SEARCH_THRESHOLD = 8



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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
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
import hivens.core.update.VersionChannel
import hivens.ui.components.ReleaseNotes
import hivens.ui.components.channelColor
import hivens.ui.components.formatBuildTimestamp
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.nx.CenteredProgress
import hivens.ui.nx.NxButton
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
)

/** Which way picking [target] would move the instance, so the action can say so. */
enum class PickerIntent { Install, Upgrade, Rollback, Switch }

/**
 * The version picker, shared by the catalogue's install flow and an installed
 * instance's version change.
 *
 * ONE list, and the row carries everything it is. The window used to be a
 * master-detail split across most of the app: a column of numbers on the left, a
 * pane describing the chosen one on the right, and a footer holding the single
 * action. That shape is right when the detail is substantial, and here it is not
 * -- notes on a real project run to one sentence, so the pane held a line of text
 * above seven hundred pixels of nothing, and the list beside it held eight rows
 * above four hundred more. A sentence that short belongs on the row it describes.
 *
 * So the row is the unit: channel, number, badges, date and size, the first line
 * of its notes underneath, and the action arriving in place of the date once the
 * row is chosen. Notes longer than that line unfold under the row rather than
 * moving to a pane, which is the only thing the pane was carrying that a row
 * could not.
 *
 * The card is sized to what it holds, with a ceiling. Sizing it to the window
 * instead is what put eight builds inside a hall.
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
    // Nothing is chosen on open. The installed build is MARKED rather than
    // selected: pre-selecting it armed an action for the one row whose action
    // does nothing, and the reader came here to move somewhere else.
    var selectedId by remember(versions) { mutableStateOf<String?>(null) }
    val shown = remember(versions, query) {
        if (query.isBlank()) versions
        else versions.filter { it.label.contains(query, ignoreCase = true) }
    }
    // A query that hides the chosen row would leave an action armed for something
    // off screen.
    LaunchedEffect(shown) {
        if (shown.none { it.id == selectedId }) selectedId = null
    }

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
            modifier = Modifier
                // Margin first, then the ceiling, then take what is left. A
                // fraction cannot do this job in either order: `fillMaxWidth`
                // fixes the width, so a ceiling after it has nothing to clamp,
                // and a ceiling before it makes the fraction a fraction of the
                // ceiling. The padding is what keeps the card off the edges on a
                // window too narrow to reach the ceiling at all.
                .padding(horizontal = 16.dp)
                .widthIn(max = CARD_WIDTH)
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = {}),
        ) {
            Column(Modifier.fillMaxWidth()) {
                Header(title, packName, packIcon, versions.size, onDismiss)
                HorizontalDivider(color = colors.outline.copy(alpha = 0.25f))
                if (warning != null) WarningLine(warning)
                // Search earns its place once the list is long enough that the
                // only way to a year-old build is scrolling.
                if (versions.size > SEARCH_THRESHOLD) {
                    NxField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = s.versionPickerSearch,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
                when {
                    loading -> CenteredProgress(Modifier.fillMaxWidth().heightIn(min = 160.dp))
                    shown.isEmpty() -> Box(
                        Modifier.fillMaxWidth().heightIn(min = 120.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(s.versionPickerEmpty, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                    }
                    else -> VersionList(
                        versions = shown,
                        selectedId = selectedId,
                        busyVersionId = busyVersionId,
                        busy = busy,
                        intentFor = intentFor,
                        onSelect = { selectedId = if (selectedId == it) null else it },
                        onConfirm = onConfirm,
                    )
                }
                Spacer(Modifier.size(8.dp))
            }
        }
    }
}

/** How wide the card is allowed to get. A list of numbers does not want a hall. */
private val CARD_WIDTH = 680.dp

/** How tall the list may run before it scrolls inside the card. */
private val LIST_MAX_HEIGHT = 460.dp

private const val SEARCH_THRESHOLD = 8

// --- Zones -----------------------------------------------------------------

@Composable
private fun Header(title: String, packName: String, icon: Any?, count: Int, onDismiss: () -> Unit) {
    val s = LocalStrings.current
    val colors = NxTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PackAvatar(icon, packName)
        Column(Modifier.weight(1f)) {
            Text(
                text = packName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // The count belongs here rather than under the list: it is a fact
            // about what you are looking at, and a caption at the bottom of a
            // scrolling column is a fact nobody scrolls to.
            Text(
                text = s.versionPickerCount(count),
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
private fun WarningLine(text: String) {
    val colors = NxTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Symbol(NxIcon.Warning, contentDescription = null, tint = colors.warnAccent, size = 16.dp)
        Text(text, style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
    }
}

@Composable
private fun VersionList(
    versions: List<PickerVersion>,
    selectedId: String?,
    busyVersionId: String?,
    busy: Boolean,
    intentFor: (PickerVersion) -> PickerIntent,
    onSelect: (String) -> Unit,
    onConfirm: (PickerVersion) -> Unit,
) {
    val listState = rememberLazyListState()
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Box(Modifier.fillMaxWidth().heightIn(max = LIST_MAX_HEIGHT).hoverable(hover)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            items(versions, key = { it.id }) { v ->
                VersionRow(
                    v = v,
                    selected = v.id == selectedId,
                    busyThis = v.id == busyVersionId,
                    busy = busy,
                    intent = intentFor(v),
                    onSelect = { onSelect(v.id) },
                    onConfirm = { onConfirm(v) },
                )
                PuppetClick("versionPicker.select.${v.id}") { onSelect(v.id) }
            }
        }
        NxVerticalScrollbar(
            adapter = rememberScrollbarAdapter(listState),
            revealed = hovered || listState.isScrollInProgress,
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 4.dp),
        )
    }
}

/**
 * One build, and everything true about it.
 *
 * The number reads first, the leading dot carries the channel so a beta is
 * legible before a word of it is read, and the date and size ride together at
 * the trailing edge: they are one fact about the build, and split into two
 * columns they left the eye to cross the row to join them up.
 *
 * Choosing the row is what arms the action, and the action lands in the date's
 * slot rather than beside it. A row already carrying a number, two badges, a
 * date and a size has no width left for a button, and adding one clipped both.
 */
@Composable
private fun VersionRow(
    v: PickerVersion,
    selected: Boolean,
    busyThis: Boolean,
    busy: Boolean,
    intent: PickerIntent,
    onSelect: () -> Unit,
    onConfirm: () -> Unit,
) {
    val s = LocalStrings.current
    val colors = NxTheme.colors
    val note = remember(v.changelog, v.label) { firstLineOfNotes(v.changelog, v.label) }
    val hasMore = remember(v.changelog, note) { hasNotesBeyond(v.changelog, note) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(if (selected) colors.primary.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(channelColor(v.channel)))
            Text(
                text = v.label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected || v.installed) FontWeight.SemiBold else FontWeight.Normal,
                color = colors.textPrimary,
                maxLines = 1,
                // Middle, not tail: what separates one `SNAPSHOT-0.0.0-...` from
                // the next is the date at its END, and a tail ellipsis turns the
                // whole snapshot chain into identical rows.
                overflow = TextOverflow.MiddleEllipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            when {
                v.installed -> NxMetaChip(s.packVersionCurrentTag, tone = NxMetaChipTone.Success)
                v.latest -> NxMetaChip(s.packVersionsLatestTag, tone = NxMetaChipTone.Surface)
            }
            Spacer(Modifier.weight(1f))
            when {
                busyThis -> CircularProgressIndicator(
                    color = colors.primary,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp),
                )
                // The build already on disk is not somewhere to go. The row says
                // so and offers nothing, rather than offering a download that
                // changes nothing and a minute of wondering whether it worked.
                selected && !v.installed && !busy -> {
                    PuppetClick("versionPicker.confirm") { onConfirm() }
                    NxButton(
                        label = actionLabel(s, intent, v),
                        onClick = onConfirm,
                        icon = NxIcon.Download,
                        compact = true,
                    )
                }
                else -> Text(
                    text = metaLine(v),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary,
                    maxLines = 1,
                )
            }
        }
        if (note != null) {
            Text(
                text = note,
                style = MaterialTheme.typography.labelSmall,
                color = colors.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 17.dp),
            )
        }
        // Everything past the first line unfolds here. This is the one thing the
        // detail pane carried that a row cannot, so it is the one thing that
        // survived it.
        AnimatedVisibility(visible = selected && hasMore) {
            Column(Modifier.padding(start = 17.dp, top = 4.dp, end = 4.dp)) {
                v.runtimeLine?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
                }
                v.changelog?.let { ReleaseNotes(it, Modifier.fillMaxWidth().padding(top = 6.dp)) }
            }
        }
    }
}

/** The date and the size as one trailing fact, skipping whichever is absent. */
private fun metaLine(v: PickerVersion): String =
    listOfNotNull(formatBuildTimestamp(v.publishedAt)?.substringBefore(' '), v.sizeLabel)
        .joinToString("  ·  ")

private fun actionLabel(s: hivens.ui.i18n.AppStrings, intent: PickerIntent, v: PickerVersion): String =
    when (intent) {
        PickerIntent.Install -> s.versionPickerInstall(v.label)
        PickerIntent.Upgrade -> s.versionPickerUpgrade(v.label)
        PickerIntent.Rollback -> s.versionPickerRollback(v.label)
        PickerIntent.Switch -> s.versionPickerSwitch(v.label)
    }

/**
 * The first line of a changelog, as prose rather than as source.
 *
 * A changelog is markdown and its first line is as often a heading as a
 * sentence, so an unstripped preview prints its hashes. Stripped by hand rather
 * than rendered: this is one line of context on a row, and a markdown parse per
 * row of a long list is work done for text nobody reads in full there.
 */
internal fun firstLineOfNotes(raw: String?, label: String = ""): String? {
    fun clean(line: String) = line
        .removePrefix(">").trimStart()
        .trimStart('#', '*', '-', '+').trimStart()
        .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
        .replace(Regex("`([^`]+)`"), "$1")
        .replace(Regex("\\[(.+?)]\\([^)]*\\)"), "$1")
        .trim()
    // Most changelogs open with their own version number as a heading, which
    // strips down to the number the row already prints two millimetres above it.
    // Skip past anything that only repeats the label and take the first line
    // that actually says something.
    return raw?.lineSequence()
        ?.map { clean(it) }
        ?.firstOrNull { it.isNotEmpty() && !it.equals(label, ignoreCase = true) }
}

/** Whether the notes hold anything the row's one line did not already show. */
internal fun hasNotesBeyond(raw: String?, firstLine: String?): Boolean {
    val lines = raw?.lineSequence()?.filter { it.isNotBlank() }?.toList().orEmpty()
    if (lines.isEmpty()) return false
    if (firstLine == null) return true
    // A heading plus one sentence is not "more": the sentence IS the preview, and
    // unfolding it would show the reader the line they just read.
    return lines.size > 2 || lines.sumOf { it.trim().length } > firstLine.length + 24
}

@Composable
private fun PackAvatar(icon: Any?, name: String) {
    val box = Modifier.size(34.dp).clip(RoundedCornerShape(9.dp))
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

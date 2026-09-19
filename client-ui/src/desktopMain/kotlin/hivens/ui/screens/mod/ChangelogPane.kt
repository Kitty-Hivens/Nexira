package hivens.ui.screens.mod

import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import hivens.core.api.dto.modrinth.ModrinthVersion
import hivens.core.update.VersionChannel
import hivens.ui.components.ChannelChip
import hivens.ui.components.ReleaseNotes
import hivens.ui.components.formatBuildTimestamp
import hivens.ui.i18n.LocalStrings
import hivens.ui.nx.CenteredProgress
import hivens.ui.nx.NxVerticalScrollbar
import hivens.ui.nx.RetryStateBlock
import hivens.ui.theme.NxTheme
import kotlinx.coroutines.launch

/**
 * Every build's notes in one column, newest first.
 *
 * A scroll rather than pages. The reference paginates by twenty because it is a
 * web page and a page of a thousand versions is a page a browser has to render;
 * a lazy list renders what is on screen and nothing else, so paging would be a
 * control that exists to work around a problem this does not have. What a reader
 * does here is run their eye down until they find when something changed, and
 * that is one motion or it is not worth doing.
 */
@Composable
internal fun ChangelogPane(
    state: ModDetailState,
    /** Asks the screen to load the project again; see [VersionsPane]. */
    onReload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    // Same key as the versions tab, for the same reason: the project arrives after
    // the tab mounts, and asking before it does used to leave a spinner forever.
    LaunchedEffect(state, state.project) { state.loadVersions() }

    val all = state.versions
    val entries = remember(all) { changelogEntries(all.orEmpty()) }

    // The same three refusals the versions tab makes, because both panes read the
    // same fetch. Without them a listing that failed left [all] null forever and
    // this drew a spinner that never stopped, which is the one state a reader
    // cannot tell from work still in progress.
    if (state.failed) {
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
    if (state.versionsFailed) {
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
    if (state.loading || state.versionsLoading) {
        CenteredProgress(modifier.fillMaxSize())
        return
    }
    if (!state.knownToCatalogue) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                s.modPageVersionsNoEntry,
                style = MaterialTheme.typography.bodySmall,
                color = NxTheme.colors.textSecondary,
            )
        }
        return
    }
    if (all == null) {
        CenteredProgress(modifier.fillMaxSize())
        return
    }
    if (entries.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                s.versionPickerNoChangelog,
                style = MaterialTheme.typography.bodySmall,
                color = NxTheme.colors.textSecondary,
            )
        }
        return
    }

    val listState = rememberLazyListState()
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Box(modifier.hoverable(hover)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(entries, key = { it.version.id }) { entry -> ChangelogRow(entry) }
        }
        NxVerticalScrollbar(
            adapter = rememberScrollbarAdapter(listState),
            revealed = hovered || listState.isScrollInProgress,
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    }
}

/**
 * One build in the log, and whether its notes were already said.
 *
 * [repeated] marks a build whose notes an OLDER build also carries. A project
 * that ships the same release for two loaders writes the notes once and publishes
 * them twice, and printing them twice makes a log of four entries read as a log
 * of two changes that happened twice. The older copy keeps the text, so the run
 * of headers is followed by the thing they all say.
 */
internal data class ChangelogEntry(
    val version: ModrinthVersion,
    val channel: VersionChannel,
    val repeated: Boolean,
)

/** Builds with notes, newest first, each marked if an older one repeats it. */
internal fun changelogEntries(versions: List<ModrinthVersion>): List<ChangelogEntry> {
    val ordered = versions
        .filter { !it.changelog.isNullOrBlank() }
        .sortedByDescending { it.datePublished }
    return ordered.mapIndexed { index, v ->
        ChangelogEntry(
            version = v,
            channel = VersionChannel.of(v.versionType, v.versionNumber),
            repeated = ordered.drop(index + 1).any { it.changelog == v.changelog },
        )
    }
}

@Composable
private fun ChangelogRow(entry: ChangelogEntry) {
    val colors = NxTheme.colors
    val v = entry.version
    // Height from the tallest child, so the rule beside the notes can be as tall as
    // they are. Inside a lazy item the row's own max height is infinite, and
    // fillMaxHeight against infinity is zero: the rule was there all along and drew
    // nothing.
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // The channel as a rule down the side rather than as a word repeated on
        // every line. A repeated build's rule is dimmed, which is the whole of what
        // "you have read this one" needs to be.
        Box(
            Modifier.width(3.dp).fillMaxHeight()
                .clip(RoundedCornerShape(50))
                .background(channelColor(entry.channel).copy(alpha = if (entry.repeated) 0.3f else 1f)),
        )
        Column(
            Modifier.weight(1f).padding(bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    v.name.ifBlank { v.versionNumber },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                ChannelChip(entry.channel)
                formatBuildTimestamp(v.datePublished)?.let {
                    // One line. This Row has no weighted child, so the date is
                    // measured against whatever the name and the channel chip left,
                    // and a Text that is allowed to wrap grows the row a line
                    // instead of giving way.
                    Text(
                        it,
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // The notes once per distinct text. A repeated build keeps its header,
            // so the reader still sees that it shipped.
            if (!entry.repeated) {
                v.changelog?.let { ReleaseNotes(it, Modifier.fillMaxWidth()) }
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

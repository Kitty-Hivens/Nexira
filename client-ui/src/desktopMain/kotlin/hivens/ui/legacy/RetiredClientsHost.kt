package hivens.ui.legacy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import hivens.launcher.legacy.RetiredClientAdopter
import hivens.launcher.legacy.RetiredClientScanner
import hivens.launcher.legacy.RetiredDataSweeper
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.nx.NxButton
import hivens.ui.nx.NxButtonStyle
import hivens.ui.nx.NxChoiceChip
import hivens.ui.nx.NxField
import hivens.ui.nx.NxVerticalScrollbar
import hivens.ui.surface.NxCard
import hivens.ui.surface.NxSurfaceLevel
import hivens.ui.theme.NxTheme
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * The surface for what the retired server path left on disk.
 *
 * MUST be composed inside [hivens.ui.theme.NxTheme]: it is a `Dialog`, which on
 * desktop gets its own composition, and one raised from outside the theme finds
 * no colours and takes the shell down with it. Same rule as the two-factor
 * prompt beside it in the shell.
 *
 * A dialog and not a screen. This is a chore with an end -- once the folders are
 * dealt with there is nothing to come back to -- so a route in the back stack
 * would outlive its own reason. It is also why there is no wizard with steps:
 * every folder is one row and one choice, and paging that would be ceremony over
 * a table somebody reads in ten seconds.
 */
@Composable
fun RetiredClientsHost() {
    val gate: RetiredClientsGate = koinInject()
    val open by gate.open.collectAsState()
    if (!open) return

    val scanner: RetiredClientScanner = koinInject()
    val adopter: RetiredClientAdopter = koinInject()
    val sweeper: RetiredDataSweeper = koinInject()
    val state = remember { RetiredClientsState(scanner, adopter, sweeper) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { state.load() }

    Dialog(onDismissRequest = { if (!state.running) gate.dismiss() }) {
        RetiredClientsBody(
            rows = state.rows,
            loading = state.loading,
            finished = state.finished,
            running = state.running,
            reclaimedBytes = state.reclaimedBytes,
            onClose = { gate.dismiss() },
            onApply = { scope.launch { state.run() } },
        )
    }
}

/**
 * The dialog's contents, apart from the dialog.
 *
 * Split out so a render sheet can draw it: a `Dialog` gets its own composition
 * and an off-screen scene has no window to raise one in, so a body that only
 * existed inside one could never be looked at before it shipped.
 */
@Composable
internal fun RetiredClientsBody(
    rows: List<RetiredRow>,
    loading: Boolean,
    finished: Boolean,
    running: Boolean,
    reclaimedBytes: Long,
    onClose: () -> Unit,
    onApply: () -> Unit,
) {
    NxCard(modifier = Modifier.widthIn(min = 560.dp, max = 860.dp), level = NxSurfaceLevel.Raised) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            when {
                loading -> Loading()
                finished -> Done(rows, reclaimedBytes, onClose)
                else -> Chooser(rows, running, onClose, onApply)
            }
        }
    }
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = NxTheme.colors.primary, strokeWidth = 2.dp)
    }
}

@Composable
private fun Done(rows: List<RetiredRow>, reclaimedBytes: Long, onClose: () -> Unit) {
    val s = LocalStrings.current
    Text(s.retiredTitle, style = MaterialTheme.typography.titleLarge, color = NxTheme.colors.textPrimary)
    Text(
        text = if (reclaimedBytes > 0) s.retiredDone(byteSizeLabel(reclaimedBytes)) else s.retiredDoneNothing,
        style = MaterialTheme.typography.bodyMedium,
        color = NxTheme.colors.textSecondary,
    )
    Column(
        modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        rows.forEach { row -> OutcomeLine(row) }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        NxButton(label = s.retiredClose, onClick = onClose, style = NxButtonStyle.Primary)
    }
}

@Composable
private fun OutcomeLine(row: RetiredRow) {
    val s = LocalStrings.current
    val outcome = row.outcome
    val (text, tint) = when (outcome) {
        // A kept source is a success with a condition attached, and green says
        // there is nothing left to read. The reader has to go and look at that one.
        is RetiredOutcome.Adopted -> if (outcome.sourceKept) {
            s.retiredAdoptedSourceKept to NxTheme.colors.warnAccent
        } else {
            s.retiredAdopted to NxTheme.colors.success
        }
        RetiredOutcome.Deleted -> s.retiredDeleted to NxTheme.colors.textSecondary
        is RetiredOutcome.Failed ->
            (if (outcome.reason == "remove") s.retiredRemoveFailed else s.retiredFailed) to NxTheme.colors.error
        // Left alone, which is an outcome and not an instruction -- naming the
        // button they did not press would read as one.
        null -> "\u2014" to NxTheme.colors.textSecondary.copy(alpha = 0.6f)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = row.client.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = NxTheme.colors.textPrimary,
            modifier = Modifier.width(150.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(text, style = MaterialTheme.typography.bodySmall, color = tint)
    }
}

@Composable
private fun Chooser(rows: List<RetiredRow>, running: Boolean, onClose: () -> Unit, onApply: () -> Unit) {
    val s = LocalStrings.current
    val totalBytes = rows.sumOf { it.client.sizeBytes }
    val anyChosen = rows.any { it.choice != RetiredChoice.Keep }
    Text(s.retiredTitle, style = MaterialTheme.typography.titleLarge, color = NxTheme.colors.textPrimary)
    Text(s.retiredIntro, style = MaterialTheme.typography.bodyMedium, color = NxTheme.colors.textSecondary)
    Text(
        text = s.retiredFound(rows.size, byteSizeLabel(totalBytes)),
        style = MaterialTheme.typography.labelLarge,
        color = NxTheme.colors.primary,
    )

    // The list carries its own bar. Without one the surface cut a row in half at
    // the bottom edge and said nothing about the three below it -- on a screen
    // whose whole job is "here is everything you have", which is the one thing it
    // must not do.
    val listState = rememberLazyListState()
    Box(Modifier.heightIn(max = 420.dp)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.padding(end = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(rows, key = { it.client.name }) { row -> ClientRow(row) }
        }
        NxVerticalScrollbar(
            adapter = rememberScrollbarAdapter(listState),
            revealed = listState.canScrollForward || listState.canScrollBackward,
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    }

    // The sentence that has to be read before the button is pressed, in the
    // colour the launcher uses for "this one is on you". Shown only once anything
    // is set to be deleted: a permanent warning nobody can act on is a warning
    // people learn to look past.
    if (rows.any { it.choice == RetiredChoice.Delete }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Symbol(NxIcon.Warning, contentDescription = null, tint = NxTheme.colors.warnAccent, size = 16.dp)
            Text(s.retiredWarning, style = MaterialTheme.typography.bodySmall, color = NxTheme.colors.warnAccent)
        }
    }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (running) {
            CircularProgressIndicator(
                color = NxTheme.colors.primary,
                strokeWidth = 2.dp,
                modifier = Modifier.width(16.dp).height(16.dp),
            )
            Text(s.retiredBusy, style = MaterialTheme.typography.bodySmall, color = NxTheme.colors.textSecondary)
            Spacer(Modifier.width(8.dp))
        }
        NxButton(label = s.retiredClose, onClick = onClose, style = NxButtonStyle.Tertiary, enabled = !running)
        NxButton(
            label = s.retiredApply,
            onClick = onApply,
            style = NxButtonStyle.Primary,
            // Nothing chosen is a real answer -- the reader looked and left -- so
            // the button is simply not live rather than the dialog refusing to close.
            enabled = anyChosen && !running,
        )
    }
}

@Composable
private fun ClientRow(row: RetiredRow) {
    val s = LocalStrings.current
    val colors = NxTheme.colors
    NxCard(modifier = Modifier.fillMaxWidth(), level = NxSurfaceLevel.Base) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = row.client.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(min = 110.dp),
                )
                // What the folder IS, in one line and read-only. The editable
                // version of the same two values appears only where it can change
                // something, which is when this folder is being adopted.
                Text(
                    text = factsOf(row, s),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (row.adoptable) colors.textSecondary else colors.warnAccent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // Two chips and not three. Leaving a folder alone is the absence of
                // a choice rather than one to press, and a selected "leave it" chip
                // was the loudest thing on the row -- the default outshouting the
                // destructive option it sits next to.
                NxChoiceChip(
                    label = s.retiredChoiceAdopt,
                    selected = row.choice == RetiredChoice.Adopt,
                    enabled = row.adoptable,
                ) {
                    row.choice = if (row.choice == RetiredChoice.Adopt) RetiredChoice.Keep else RetiredChoice.Adopt
                }
                NxChoiceChip(
                    label = s.retiredChoiceDelete,
                    selected = row.choice == RetiredChoice.Delete,
                ) {
                    row.choice = if (row.choice == RetiredChoice.Delete) RetiredChoice.Keep else RetiredChoice.Delete
                }
            }

            if (row.choice == RetiredChoice.Adopt) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NxField(
                        value = row.mcVersion,
                        onValueChange = { row.mcVersion = it },
                        placeholder = "1.21.1",
                        modifier = Modifier.width(96.dp),
                    )
                    NxField(
                        value = row.loader,
                        onValueChange = { row.loader = it },
                        placeholder = s.retiredLoaderVanilla,
                        modifier = Modifier.width(120.dp),
                    )
                    Text(
                        text = s.retiredDetected,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/**
 * The folder's facts as one line: how big, how many mods, what it runs on.
 *
 * A folder that names no Minecraft version says so here instead, in the warning
 * colour, because that is the fact that decides whether it can become a pack at
 * all and it belongs beside the other two rather than in a sentence further down.
 */
@Composable
private fun factsOf(row: RetiredRow, s: hivens.ui.i18n.AppStrings): String {
    val parts = buildList {
        add(byteSizeLabel(row.client.sizeBytes))
        if (row.client.modCount > 0) add(s.retiredMods(row.client.modCount))
        if (row.adoptable) {
            add(listOf(row.mcVersion, row.loader.ifBlank { s.retiredLoaderVanilla }).joinToString(" "))
        } else {
            add(s.retiredNoVersion)
        }
    }
    return parts.joinToString(" \u00b7 ")
}

/**
 * A size a person reads, up to whole gigabytes.
 *
 * Megabytes are where the existing file-browser label stops, which reads as
 * "5120 MB" for a client tree and is a number nobody converts in their head.
 */
internal fun byteSizeLabel(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1_048_576L -> "${bytes / 1024L} KB"
    bytes < 1_073_741_824L -> "${bytes / 1_048_576L} MB"
    else -> "%.1f GB".format(bytes / 1_073_741_824.0)
}

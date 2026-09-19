package hivens.ui.legacy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.text.TextStyle
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
        NxCard(modifier = Modifier.widthIn(min = 620.dp, max = 900.dp), level = NxSurfaceLevel.Raised) {
            Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                when {
                    state.loading -> Loading()
                    state.finished -> Done(state) { gate.dismiss() }
                    else -> Chooser(state, onClose = { gate.dismiss() }) { scope.launch { state.run() } }
                }
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
private fun Done(state: RetiredClientsState, onClose: () -> Unit) {
    val s = LocalStrings.current
    Text(s.retiredTitle, style = MaterialTheme.typography.titleLarge, color = NxTheme.colors.textPrimary)
    Text(
        text = if (state.reclaimedBytes > 0) s.retiredDone(byteSizeLabel(state.reclaimedBytes)) else s.retiredDoneNothing,
        style = MaterialTheme.typography.bodyMedium,
        color = NxTheme.colors.textSecondary,
    )
    Column(
        modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        state.rows.forEach { row -> OutcomeLine(row) }
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
        is RetiredOutcome.Adopted ->
            (if (outcome.sourceKept) s.retiredAdoptedSourceKept else s.retiredAdopted) to NxTheme.colors.success
        RetiredOutcome.Deleted -> s.retiredDeleted to NxTheme.colors.textSecondary
        is RetiredOutcome.Failed ->
            (if (outcome.reason == "remove") s.retiredRemoveFailed else s.retiredFailed) to NxTheme.colors.error
        null -> s.retiredChoiceKeep to NxTheme.colors.textSecondary
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
private fun Chooser(state: RetiredClientsState, onClose: () -> Unit, onApply: () -> Unit) {
    val s = LocalStrings.current
    Text(s.retiredTitle, style = MaterialTheme.typography.titleLarge, color = NxTheme.colors.textPrimary)
    Text(s.retiredIntro, style = MaterialTheme.typography.bodyMedium, color = NxTheme.colors.textSecondary)
    Text(
        text = s.retiredFound(state.rows.size, byteSizeLabel(state.totalBytes)),
        style = MaterialTheme.typography.labelLarge,
        color = NxTheme.colors.primary,
    )

    LazyColumn(
        modifier = Modifier.heightIn(max = 380.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(state.rows, key = { it.client.name }) { row -> ClientRow(row) }
    }

    // The sentence that has to be read before the button is pressed, in the
    // colour the launcher uses for "this one is on you".
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Symbol(NxIcon.Warning, contentDescription = null, tint = NxTheme.colors.warnAccent, size = 16.dp)
        Text(s.retiredWarning, style = MaterialTheme.typography.bodySmall, color = NxTheme.colors.warnAccent)
    }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.running) {
            CircularProgressIndicator(
                color = NxTheme.colors.primary,
                strokeWidth = 2.dp,
                modifier = Modifier.width(16.dp).height(16.dp),
            )
            Text(s.retiredBusy, style = MaterialTheme.typography.bodySmall, color = NxTheme.colors.textSecondary)
            Spacer(Modifier.width(8.dp))
        }
        NxButton(label = s.retiredClose, onClick = onClose, style = NxButtonStyle.Tertiary, enabled = !state.running)
        NxButton(
            label = s.retiredApply,
            onClick = onApply,
            style = NxButtonStyle.Primary,
            // Nothing chosen is a real answer -- the reader looked and left -- so
            // the button is simply not live rather than the dialog refusing to close.
            enabled = state.anyChosen && !state.running,
        )
    }
}

@Composable
private fun ClientRow(row: RetiredRow) {
    val s = LocalStrings.current
    NxCard(modifier = Modifier.fillMaxWidth(), level = NxSurfaceLevel.Base) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = row.client.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = NxTheme.colors.textPrimary,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = byteSizeLabel(row.client.sizeBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = NxTheme.colors.textSecondary,
                )
                if (row.client.modCount > 0) {
                    Text(
                        text = s.retiredMods(row.client.modCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = NxTheme.colors.textSecondary,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NxChoiceChip(s.retiredChoiceKeep, row.choice == RetiredChoice.Keep) {
                    row.choice = RetiredChoice.Keep
                }
                NxChoiceChip(s.retiredChoiceAdopt, row.choice == RetiredChoice.Adopt, enabled = row.adoptable) {
                    row.choice = RetiredChoice.Adopt
                }
                NxChoiceChip(s.retiredChoiceDelete, row.choice == RetiredChoice.Delete) {
                    row.choice = RetiredChoice.Delete
                }
            }

            // The version and loader are shown always, not only when adopting: they
            // are what the folder IS, and a reader deciding whether to keep it wants
            // to know that before they choose rather than after.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(row.mcVersion, "1.21.1") { row.mcVersion = it }
                Field(row.loader, s.retiredLoaderVanilla) { row.loader = it }
                Text(
                    text = if (row.adoptable) s.retiredDetected else s.retiredNoVersion,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (row.adoptable) NxTheme.colors.textSecondary else NxTheme.colors.warnAccent,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** A small editable value: what the folder said, which the reader may correct. */
@Composable
private fun Field(value: String, placeholder: String, onChange: (String) -> Unit) {
    val colors = NxTheme.colors
    Box(
        modifier = Modifier.width(110.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) {
            Text(placeholder, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary.copy(alpha = 0.5f))
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(
                color = colors.textPrimary,
                fontSize = MaterialTheme.typography.bodySmall.fontSize,
            ),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.primary),
            modifier = Modifier.fillMaxWidth(),
        )
    }
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

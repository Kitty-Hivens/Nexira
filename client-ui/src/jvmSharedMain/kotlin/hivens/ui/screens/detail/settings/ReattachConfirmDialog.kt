package hivens.ui.screens.detail.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import hivens.packs.api.PackKeys
import hivens.packs.api.ReattachConfirmation
import hivens.packs.api.ReattachPreview
import hivens.packs.api.ReattachPreviewView
import hivens.ui.i18n.nexiraStrings
import hivens.ui.nx.NxAlertDialog
import hivens.ui.nx.NxCalloutBanner
import hivens.ui.nx.NxCalloutTone
import hivens.ui.theme.NxColor
import hivens.ui.theme.NxInk
import hivens.ui.theme.NxTheme
import hivens.ui.theme.OnFill
import hivens.ui.theme.Status
import hivens.ui.utils.shortNameList
import hivens.widget.api.rememberScopedCommand
import hivens.widget.api.rememberScopedSource
import hivens.widget.api.valueOrNull

/**
 * Whether the move is one the update's warning names: another Minecraft or another
 * loader. An unknown grade is not that, and the dialog says what it does not know
 * through the basis line instead of claiming a move nobody measured.
 */
private val ReattachPreview.movesRuntime: Boolean get() = compat == "mc-bump" || compat == "loader-swap"

/** Whether reattaching changes no file, only the record. */
private val ReattachPreview.isEmpty: Boolean
    get() = removed.isEmpty() && restored.isEmpty() && replaced.isEmpty() && turnedOn.isEmpty() && turnedOff.isEmpty() && kept.isEmpty()

/** How many paths a list shows before it folds the rest behind a toggle. */
private const val FOLDED_ROWS = 6

/**
 * The question before a reattach, with its answer measured: the source build the
 * pack returns to, and the files that go and the files that come back.
 *
 * The confirm stays off until the comparison is in. Reattaching drops what the
 * player changed in the pack's mods, so they see exactly what before they agree,
 * and a comparison that failed leaves nothing to agree to. [onConfirm] gets what
 * the player agreed to, and the reattach does exactly that or refuses.
 *
 * The comparison is asked of the packs module through its keys, the way any widget
 * asks, and dropped when the dialog goes, which stops one still running. Where the
 * packs module is not brought in there is nothing to ask, and the dialog says so.
 */
@Composable
internal fun ReattachConfirmDialog(
    packId: String,
    sourceName: String,
    onConfirm: (ReattachConfirmation) -> Unit,
    onDismiss: () -> Unit,
) {
    val s = nexiraStrings()
    val previews = rememberScopedSource(PackKeys.ReattachPreviews).valueOrNull()
    val ask = rememberScopedCommand(PackKeys.PreviewReattach)
    val forget = rememberScopedCommand(PackKeys.ForgetReattachPreview)
    val available = previews != null && ask != null
    DisposableEffect(packId, available) {
        if (available) ask(packId)
        onDispose { if (available) forget?.invoke(packId) }
    }
    val state = previews?.get(packId) ?: ReattachPreviewView.Comparing
    val title = s.text("packSettingsReattach", sourceName)
    val container = NxTheme.colours.step(NxTheme.colours.topStep)
    OnFill(container) {
        NxAlertDialog(
            onDismissRequest = onDismiss,
            paneTitle        = title,
            title = { Text(title) },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    when {
                        !available -> Text(
                            s.text("packSettingsReattachUnavailable"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = NxColor.status(Status.Error),
                        )
                        state == ReattachPreviewView.Comparing -> Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = NxColor.lead())
                            Text(s.text("packSettingsReattachComparing"), style = MaterialTheme.typography.bodyMedium)
                        }
                        state is ReattachPreviewView.Failed -> Text(
                            s.text("packSettingsReattachCompareFailed", state.message),
                            style = MaterialTheme.typography.bodyMedium,
                            color = NxColor.status(Status.Error),
                        )
                        state is ReattachPreviewView.Ready -> PreviewBody(state.preview)
                    }
                }
            },
            confirmButton = {
                // Not while files are left that could not be read: the launcher refuses
                // that reattach, so the dialog does not offer it.
                val ready = (state as? ReattachPreviewView.Ready)?.takeIf { available && it.preview.unreadable.isEmpty() }
                TextButton(
                    onClick = {
                        if (ready != null) {
                            onConfirm(ready.preview.confirmation)
                            onDismiss()
                        }
                    },
                    enabled = ready != null,
                ) {
                    Text(
                        s.text("packSettingsReattachAction"),
                        color = if (ready != null) NxColor.status(Status.Error) else NxInk.quiet,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(s.text("editorCancel")) }
            },
            containerColor    = container,
            titleContentColor = NxInk.main,
            textContentColor  = NxInk.quiet,
        )
    }
}

@Composable
private fun PreviewBody(preview: ReattachPreview) {
    val s = nexiraStrings()
    if (preview.movesRuntime) NxCalloutBanner(body = s.text("packVersionNeedsCare"), tone = NxCalloutTone.Warning)
    if (preview.unreadable.isNotEmpty()) {
        NxCalloutBanner(body = s.text("packSettingsReattachUnreadable", shortNameList(preview.unreadable.map { it.substringAfterLast('/') })), tone = NxCalloutTone.Warning)
    }
    when (preview.basis) {
        "no-baseline" -> NxCalloutBanner(body = s.text("packSettingsReattachNoBaseline"), tone = NxCalloutTone.Warning)
        "names-only" -> Text(s.text("packSettingsReattachNamesOnly"), style = MaterialTheme.typography.bodySmall)
        else -> Unit
    }
    if (preview.isEmpty) {
        Text(s.text("packSettingsReattachNothing"), style = MaterialTheme.typography.bodyMedium)
        return
    }
    Text(s.text("packSettingsReattachBody", preview.toVersion), style = MaterialTheme.typography.bodyMedium)
    if (preview.removed.isNotEmpty()) PathList(s.text("packSettingsReattachRemoved", preview.removed.size), preview.removed)
    val returned = (preview.restored + preview.replaced).sorted()
    if (returned.isNotEmpty()) PathList(s.text("packSettingsReattachReturned", returned.size), returned)
    if (preview.turnedOn.isNotEmpty()) PathList(s.text("packSettingsReattachTurnedOn", preview.turnedOn.size), preview.turnedOn)
    if (preview.turnedOff.isNotEmpty()) PathList(s.text("packSettingsReattachTurnedOff", preview.turnedOff.size), preview.turnedOff)
    if (preview.kept.isNotEmpty()) PathList(s.text("packSettingsReattachKept", preview.kept.size), preview.kept)
}

/**
 * A headed list of paths, folded after [FOLDED_ROWS] so a pack with a hundred
 * changed files does not push the buttons off the dialog.
 */
@Composable
private fun PathList(header: String, paths: List<String>) {
    val s = nexiraStrings()
    var unfolded by remember(paths) { mutableStateOf(false) }
    val shown = if (unfolded) paths else paths.take(FOLDED_ROWS)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(header, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = NxInk.main)
        shown.forEach { path ->
            Text(path, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (paths.size > FOLDED_ROWS) {
            TextButton(onClick = { unfolded = !unfolded }) {
                Text(
                    if (unfolded) s.text("packSettingsReattachLess") else s.text("packSettingsReattachMore", paths.size - FOLDED_ROWS),
                    style = MaterialTheme.typography.labelMedium,
                    color = NxColor.lead(),
                )
            }
        }
    }
}

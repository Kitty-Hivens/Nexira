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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import hivens.core.data.PackInstance
import hivens.core.update.CompatChange
import hivens.launcher.update.ReattachBasis
import hivens.launcher.update.ReattachConfirmation
import hivens.launcher.update.ReattachPreview
import hivens.ui.i18n.LocalStrings
import hivens.ui.nx.NxAlertDialog
import hivens.ui.nx.NxCalloutBanner
import hivens.ui.nx.NxCalloutTone
import hivens.ui.packs.ReattachPreviewState
import hivens.ui.packs.ReattachPreviews
import hivens.ui.theme.NxColor
import hivens.ui.theme.NxInk
import hivens.ui.theme.NxTheme
import hivens.ui.theme.OnFill
import hivens.ui.theme.Status
import hivens.ui.utils.shortNameList
import org.koin.compose.koinInject

/**
 * Whether the move is one the update's warning names: another Minecraft or another
 * loader. An unknown grade is not that, and the dialog says what it does not know
 * through the basis line instead of claiming a move nobody measured.
 */
private val CompatChange.movesRuntime: Boolean get() = this == CompatChange.McBump || this == CompatChange.LoaderSwap

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
 * The comparison is asked of [ReattachPreviews], the same store a widget reads, and
 * dropped when the dialog goes, which stops one still running.
 */
@Composable
internal fun ReattachConfirmDialog(
    pack: PackInstance,
    sourceName: String,
    onConfirm: (ReattachConfirmation) -> Unit,
    onDismiss: () -> Unit,
) {
    val s = LocalStrings.current
    val previews: ReattachPreviews = koinInject()
    DisposableEffect(pack.id) {
        previews.request(pack)
        onDispose { previews.forget(pack.id) }
    }
    val all by previews.previews.collectAsState()
    val state = all[pack.id] ?: ReattachPreviewState.Comparing
    val title = s.packSettingsReattach(sourceName)
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
                    when (state) {
                        ReattachPreviewState.Comparing -> Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = NxColor.lead())
                            Text(s.packSettingsReattachComparing, style = MaterialTheme.typography.bodyMedium)
                        }
                        is ReattachPreviewState.Failed -> Text(
                            s.packSettingsReattachCompareFailed(state.message),
                            style = MaterialTheme.typography.bodyMedium,
                            color = NxColor.status(Status.Error, text = true),
                        )
                        is ReattachPreviewState.Ready -> PreviewBody(state.preview)
                    }
                }
            },
            confirmButton = {
                // Not while files are left that could not be read: the launcher refuses
                // that reattach, so the dialog does not offer it.
                val ready = (state as? ReattachPreviewState.Ready)?.takeIf { it.preview.unreadable.isEmpty() }
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
                        s.packSettingsReattachAction,
                        color = if (ready != null) NxColor.status(Status.Error) else NxInk.quiet,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(s.editorCancel) }
            },
            containerColor    = container,
            titleContentColor = NxInk.main,
            textContentColor  = NxInk.quiet,
        )
    }
}

@Composable
private fun PreviewBody(preview: ReattachPreview) {
    val s = LocalStrings.current
    if (preview.compat.movesRuntime) NxCalloutBanner(body = s.packVersionNeedsCare, tone = NxCalloutTone.Warning)
    if (preview.unreadable.isNotEmpty()) {
        NxCalloutBanner(body = s.packSettingsReattachUnreadable(shortNameList(preview.unreadable.map { it.substringAfterLast('/') })), tone = NxCalloutTone.Warning)
    }
    when (preview.basis) {
        ReattachBasis.NoBaseline -> NxCalloutBanner(body = s.packSettingsReattachNoBaseline, tone = NxCalloutTone.Warning)
        ReattachBasis.NamesOnly -> Text(s.packSettingsReattachNamesOnly, style = MaterialTheme.typography.bodySmall)
        ReattachBasis.Full -> Unit
    }
    if (preview.isEmpty) {
        Text(s.packSettingsReattachNothing, style = MaterialTheme.typography.bodyMedium)
        return
    }
    Text(s.packSettingsReattachBody(preview.toVersion), style = MaterialTheme.typography.bodyMedium)
    if (preview.removed.isNotEmpty()) PathList(s.packSettingsReattachRemoved(preview.removed.size), preview.removed)
    val returned = preview.returned
    if (returned.isNotEmpty()) PathList(s.packSettingsReattachReturned(returned.size), returned)
    if (preview.turnedOn.isNotEmpty()) PathList(s.packSettingsReattachTurnedOn(preview.turnedOn.size), preview.turnedOn)
    if (preview.turnedOff.isNotEmpty()) PathList(s.packSettingsReattachTurnedOff(preview.turnedOff.size), preview.turnedOff)
    if (preview.kept.isNotEmpty()) PathList(s.packSettingsReattachKept(preview.kept.size), preview.kept)
}

/**
 * A headed list of paths, folded after [FOLDED_ROWS] so a pack with a hundred
 * changed files does not push the buttons off the dialog.
 */
@Composable
private fun PathList(header: String, paths: List<String>) {
    val s = LocalStrings.current
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
                    if (unfolded) s.packSettingsReattachLess else s.packSettingsReattachMore(paths.size - FOLDED_ROWS),
                    style = MaterialTheme.typography.labelMedium,
                    color = NxColor.lead(text = true),
                )
            }
        }
    }
}

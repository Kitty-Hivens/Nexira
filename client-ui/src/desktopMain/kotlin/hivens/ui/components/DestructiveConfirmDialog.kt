package hivens.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import hivens.ui.i18n.LocalStrings
import hivens.ui.theme.NxTheme

/**
 * Confirm gate for an irreversible action. [confirmLabel] is the action's own
 * verb (the destructive-tinted button); cancel is the shared `editorCancel`.
 * The host owns the open/closed state -- render this only while pending and pass
 * the flag-clearing as [onDismiss]. [onConfirm] runs the action; [onDismiss] is
 * called right after it so the host does not have to clear the flag twice.
 */
@Composable
fun DestructiveConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val s = LocalStrings.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text  = { Text(body, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) {
                Text(confirmLabel, color = NxTheme.colors.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(s.editorCancel) }
        },
        containerColor = NxTheme.colors.surface,
    )
}

package hivens.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import hivens.ui.i18n.LocalStrings
import hivens.ui.theme.NxTheme

/**
 * Confirm gate for an action worth a second look.
 *
 * [confirmLabel] is the action's own verb rather than "OK", so the button says
 * what will happen; [confirmColor] carries how much it matters -- the accent for
 * an ordinary action, the error colour for one that cannot be undone. The host
 * owns the open/closed flag: render this only while pending and pass the
 * flag-clearing as [onDismiss], which also runs right after [onConfirm] so the
 * caller does not have to clear it twice.
 */
@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmColor: Color = NxTheme.colors.primary,
) {
    val s = LocalStrings.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text  = { Text(body, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) {
                Text(confirmLabel, color = confirmColor)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(s.editorCancel) }
        },
        containerColor = NxTheme.colors.surface,
    )
}

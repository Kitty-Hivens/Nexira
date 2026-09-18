package hivens.ui.components

import androidx.compose.runtime.Composable
import hivens.ui.theme.NxTheme

/**
 * Confirm gate for an irreversible action: [ConfirmDialog] with the error colour
 * on its action, which is the whole of the difference between the two.
 * [confirmLabel] is the action's own verb; cancel is the shared `editorCancel`.
 */
@Composable
fun DestructiveConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ConfirmDialog(
        title        = title,
        body         = body,
        confirmLabel = confirmLabel,
        onConfirm    = onConfirm,
        onDismiss    = onDismiss,
        confirmColor = NxTheme.colors.error,
    )
}

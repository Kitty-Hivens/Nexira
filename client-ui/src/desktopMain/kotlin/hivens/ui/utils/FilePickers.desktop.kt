package hivens.ui.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import hivens.ui.chrome.LocalComposeWindow
import io.github.vinceglb.filekit.dialogs.FileKitDialogParent
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings

/**
 * Dialog settings that name the launcher window as the native dialog's owner.
 *
 * An ownerless Win32 IFileDialog is a top-level window in its own right: it
 * gets its own taskbar button and its z-order is not tied to the launcher, so
 * over a maximized window it can open behind it. Nothing is logged and nothing
 * moves on screen, which reads as a button that does nothing. The XDG portal
 * needs the parent for the same reason, and both take the AWT window the shell
 * already publishes through [LocalComposeWindow].
 */
@Composable
actual fun rememberFileDialogSettings(title: String?): FileKitDialogSettings {
    // A window that is not displayable has no native handle to hand over, and
    // FileKit reads that handle rather than falling back.
    val window = LocalComposeWindow.current?.takeIf { it.isDisplayable }
    return remember(title, window) {
        FileKitDialogSettings(title = title, parent = window?.let(FileKitDialogParent::awt))
    }
}

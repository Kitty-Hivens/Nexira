package hivens.ui.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import hivens.ui.host.LocalHostWindow
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings

@Composable
// The system picker draws its own title and has no owner window to name.
actual fun rememberFileDialogSettings(title: String?): FileDialogSettings {
    val host = LocalHostWindow.current
    return remember(host) { FileDialogSettings(FileKitDialogSettings(), host) }
}

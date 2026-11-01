package hivens.ui.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings

@Composable
// The system picker draws its own title and has no owner window to name.
actual fun rememberFileDialogSettings(title: String?): FileKitDialogSettings = remember { FileKitDialogSettings() }

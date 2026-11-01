package hivens.ui.widgets.about

import androidx.compose.runtime.Composable
import hivens.core.data.LauncherUpdate

/** The screen size and density, as the about card prints them. */
@Composable
internal expect fun rememberDisplayInfo(): String

/** The windowing and render backend in use, as the about card prints it. */
@Composable
internal expect fun rememberRendererInfo(): String

/** The dialog that downloads and applies [update]. A host that cannot apply one draws nothing. */
@Composable
internal expect fun UpdateDialogHost(update: LauncherUpdate, onDismiss: () -> Unit)

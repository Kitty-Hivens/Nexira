package hivens.ui.widgets.about

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import hivens.core.data.LauncherUpdate
import hivens.ui.components.UpdateDialog
import hivens.ui.diag.RenderBackend
import hivens.update.UpdateService
import org.koin.compose.koinInject

@Composable
internal actual fun rememberDisplayInfo(): String = remember {
    runCatching {
        val tk   = java.awt.Toolkit.getDefaultToolkit()
        val size = tk.screenSize
        val dpi  = tk.screenResolution
        val scale = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
            .defaultScreenDevice.defaultConfiguration.defaultTransform.scaleX
        val scaleStr = if (scale % 1.0 == 0.0) "${scale.toInt()}x" else "%.2fx".format(scale)
        "${size.width}x${size.height} · $dpi dpi · $scaleStr"
    }.getOrDefault("Unknown")
}

// Renderer "future hook". Skiko has no native Wayland yet: on a Wayland session
// it renders through XWayland, i.e. still Xorg -- so report Xorg (noting
// XWayland) rather than the session type. Native Wayland is the future hook;
// flip this when Skiko-on-Wayland lands.
@Composable
internal actual fun rememberRendererInfo(): String = remember {
    val windowing = when {
        System.getenv("DISPLAY") != null ->
            if (System.getenv("WAYLAND_DISPLAY") != null) "Xorg (XWayland)" else "Xorg"
        else -> System.getProperty("os.name") ?: "?"
    }
    // The backend in use, not the one requested: the launcher never sets
    // `skiko.renderApi`, so reading that property said "default" on every
    // machine, including one that had fallen back to software.
    "$windowing · ${RenderBackend.current}"
}

@Composable
internal actual fun UpdateDialogHost(update: LauncherUpdate, onDismiss: () -> Unit) {
    val updateService: UpdateService = koinInject()
    UpdateDialog(update = update, updateService = updateService, onDismiss = onDismiss)
}

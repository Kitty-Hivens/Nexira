package hivens.ui.widgets.about

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import hivens.core.data.LauncherUpdate

@Composable
internal actual fun rememberDisplayInfo(): String {
    val metrics = LocalContext.current.resources.displayMetrics
    return remember(metrics) {
        "${metrics.widthPixels}x${metrics.heightPixels} · ${metrics.densityDpi} dpi · ${"%.2f".format(metrics.density)}x"
    }
}

@Composable
internal actual fun rememberRendererInfo(): String = remember { "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})" }

// An Android build is replaced by installing the next package, not by the launcher.
@Composable
internal actual fun UpdateDialogHost(update: LauncherUpdate, onDismiss: () -> Unit) = Unit

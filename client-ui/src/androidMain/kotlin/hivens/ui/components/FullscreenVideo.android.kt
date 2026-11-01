package hivens.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext

// The link goes to the app the person watches video in, and the overlay closes at
// once: nothing is left to show here. An in-app player for direct video files comes
// with the Android player.
@Composable
actual fun FullscreenVideo(url: String, posterUrl: String?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(url) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        onDismiss()
    }
}

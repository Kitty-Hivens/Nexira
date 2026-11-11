package hivens.ui.components

import androidx.compose.runtime.Composable

@Composable
actual fun FullscreenVideo(url: String, posterUrl: String?, onDismiss: () -> Unit) =
    FullscreenVideoWithHandoff(url = url, posterUrl = posterUrl, handoff = null, onDismiss = onDismiss)

package hivens.ui.components

import androidx.compose.runtime.Composable

/**
 * Full-window video, dismissed by [onDismiss]. Takes a URL and plays it with sound
 * and controls, with [posterUrl] showing while it loads.
 *
 * Where the host plays it is its own business: a desktop plays it over a scrim in the
 * window, a phone hands the link to the app the person watches video in.
 */
@Composable
expect fun FullscreenVideo(url: String, posterUrl: String? = null, onDismiss: () -> Unit)

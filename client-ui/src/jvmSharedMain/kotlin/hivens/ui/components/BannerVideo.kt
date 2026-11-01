package hivens.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * A looping, silent video behind a banner, with no controls: what a pack's page
 * shows when its banner is a video rather than a picture.
 *
 * Whatever lies under it stays visible until it plays, and a host that cannot play
 * the video draws nothing, so the banner keeps the art that was already there.
 */
@Composable
expect fun BannerVideo(url: String, modifier: Modifier = Modifier)

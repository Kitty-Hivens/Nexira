package hivens.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.hivens.skinema.compose.VideoScale

@Composable
actual fun BannerVideo(url: String, modifier: Modifier) {
    VideoMedia(
        url          = url,
        modifier     = modifier,
        autoPlay     = true,
        loop         = true,
        audio        = false,
        startMuted   = true,
        showControls = false,
        scale        = VideoScale.Cover,
    )
}

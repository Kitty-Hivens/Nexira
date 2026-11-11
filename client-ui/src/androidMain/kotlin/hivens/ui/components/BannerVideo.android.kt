package hivens.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// No moving banner on a phone yet: the page keeps the art that lies under it.
@Composable
actual fun BannerVideo(url: String, modifier: Modifier) = Unit

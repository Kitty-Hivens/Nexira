package hivens.ui.widgets.bgsettings

import androidx.compose.runtime.Composable
import hivens.widget.model.Widget

// A wallpaper here is a picture, so it has no soundtrack to switch on. The widget exists so the layout
// that names it draws nothing in its place instead of a placeholder.
@Widget(id = "bg.audio", displayName = "widget.bg.audio")
@Composable
fun BgAudioWidget() = Unit

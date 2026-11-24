package hivens.look.preview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import hivens.look.icon.api.Icon
import hivens.look.icon.google.rounded.googleRoundedIcons
import hivens.ui.theme.PreviewLook

/** Previews and tests draw icons from the library the launcher ships. */
class LauncherPreviewLook : PreviewLook {
    @Composable
    override fun Provide(content: @Composable () -> Unit) =
        CompositionLocalProvider(Icon.local provides googleRoundedIcons(), content = content)
}

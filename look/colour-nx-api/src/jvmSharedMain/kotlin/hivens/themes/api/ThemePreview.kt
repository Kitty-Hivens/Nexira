package hivens.themes.api

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import hivens.look.colour.api.Colour
import hivens.ui.theme.Theme
import hivens.ui.theme.colourAnswers

/**
 * [content] with the look's colour axis answering [theme], in its dark scheme or its light one by
 * [dark], whatever the look around answers: a theme the person has not chosen, drawn on purpose, the
 * way a picker previews it.
 *
 * The look can swap the provider of an axis for a subtree, not a theme inside a provider, and choosing
 * a theme is this module's own feature, so its preview is too. It answers the colour axis and nothing
 * else: whatever draws [content] places the bridge from the answers to Compose and Material itself.
 */
@Composable
fun ThemePreview(theme: Theme, dark: Boolean, content: @Composable () -> Unit) {
    val scheme = theme.scheme(dark)
    val answers = remember(scheme) { scheme.colourAnswers() }
    CompositionLocalProvider(Colour.local provides answers, content = content)
}

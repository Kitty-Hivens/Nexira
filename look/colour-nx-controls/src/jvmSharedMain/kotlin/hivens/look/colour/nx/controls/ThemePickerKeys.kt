package hivens.look.colour.nx.controls

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import hivens.i18n.api.strings
import hivens.themes.api.ThemePreview
import hivens.themes.api.ThemesApi
import hivens.ui.theme.NxTheme
import hivens.themes.api.Theme
import hivens.widget.model.ScopeLevel
import hivens.widget.model.SourceKey

/**
 * The theme the picker has chosen and not yet applied. The grid writes it on a click, the preview
 * draws it and the header applies it. Null where nothing chose one yet, the palette's sample of it
 * among them, which reads as the theme in use.
 */
@Stable
internal class ThemeSelection(initial: Theme?) {
    var theme by mutableStateOf(initial)

    /** What is chosen, the theme in use where nothing is. */
    fun or(active: Theme): Theme = theme ?: active
}

/**
 * The picker page's pending choice: one per page, made the first time a widget of the page reads it,
 * starting at the theme in use then, and kept for as long as the page is. A page lives while its entry
 * is in the history, so a choice not yet applied is still there after going back and forward again.
 */
internal val ThemeSelectionSource: SourceKey<ThemeSelection> = SourceKey("theme.picker.selection", ScopeLevel.Page)

/** What the picker calls [this]: the theme's own name, or this module's word for the one made from the wallpaper. */
@Composable
internal fun Theme.label(): String =
    if (id == ThemesApi.WALLPAPER_THEME_ID) strings(ColourNxControls.ID).text("themeFromWallpaper") else name

/**
 * [content] drawn in [theme], in its dark or light scheme by [dark]: the themes module's preview of the
 * theme, and the design system's bridge from its answers, in the faces of the interface around it.
 */
@Composable
internal fun InTheme(theme: Theme, dark: Boolean, content: @Composable () -> Unit) {
    ThemePreview(theme, dark) {
        NxTheme(content = content)
    }
}

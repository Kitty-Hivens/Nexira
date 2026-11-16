package hivens.ui.widgets.themepicker

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import hivens.themes.api.ThemesApi
import hivens.ui.i18n.nexiraStrings
import hivens.ui.theme.NeutralTheme
import hivens.ui.theme.Theme
import hivens.widget.model.ScopeLevel
import hivens.widget.model.SourceKey

/**
 * The theme the picker has chosen and not yet applied. The grid writes it on a
 * click and the preview draws it. Applying is not here: the button is surface
 * chrome, not a widget, and fires the apply command itself.
 */
@Stable
internal class ThemeSelection(initial: Theme) {
    var theme by mutableStateOf(initial)
}

/**
 * The picker page's pending choice, put over its page level by the surface, which
 * starts it at the theme in use. Only the page that holds the choice can answer it.
 */
internal val ThemeSelectionSource: SourceKey<ThemeSelection> = SourceKey("theme.picker.selection", ScopeLevel.Page)

/**
 * The choice the picker's widgets are shown on where no picker holds one: [theme], the sample of the
 * theme in use, or the neutral theme where no themes module brings one. Display data only.
 */
internal fun sampleThemeSelection(theme: Theme?): ThemeSelection = ThemeSelection(theme ?: NeutralTheme)

/** What the picker calls [this]: the theme's own name, or this launcher's word for the one made from the wallpaper. */
@Composable
internal fun Theme.label(): String = if (id == ThemesApi.WALLPAPER_THEME_ID) nexiraStrings().text("themeFromWallpaper") else name

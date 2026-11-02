package hivens.ui.widgets.modules

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import hivens.ui.i18n.AppLocale
import hivens.ui.i18n.AppStrings
import hivens.ui.i18n.LocalAppLocale
import hivens.ui.i18n.LocalStrings
import hivens.widget.api.ModuleStrings
import hivens.widget.model.WidgetKind

/**
 * Turns a widget's name or a property's label into words.
 *
 * A built-in widget names itself by a key into the launcher's own string tables. A module's widget
 * names itself by a key into the words its module ships, which this looks at first, in the person's
 * language, and a key the module has no entry for falls through to the launcher's tables and then
 * shows as itself. So a module in one language can write its names in plain text and ship nothing.
 */
class WidgetLabels(private val moduleStrings: (WidgetKind) -> ModuleStrings?) {

    fun of(kind: WidgetKind, key: String, builtIn: AppStrings, locale: AppLocale): String {
        val own = moduleStrings(kind)?.get(key, locale.tag)
        return if (own != null && own != key) own else builtIn.widgetLabel(key)
    }

    companion object {
        /** Knows no module: every key is the launcher's own. */
        val BUILT_IN = WidgetLabels { null }
    }
}

val LocalWidgetLabels = staticCompositionLocalOf { WidgetLabels.BUILT_IN }

/** The words for [key], a widget name or a property label, as the widget of [kind] says it. */
@Composable
fun widgetLabel(kind: WidgetKind, key: String): String =
    LocalWidgetLabels.current.of(kind, key, LocalStrings.current, LocalAppLocale.current)

package hivens.ui.widgets.modules

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import hivens.i18n.api.LocalTranslator
import hivens.i18n.api.Translator
import hivens.ui.i18n.NexiraStrings
import hivens.widget.model.WidgetKind

/**
 * Turns a widget's name or a property's label into words.
 *
 * A built-in widget names itself by a key into the launcher's own words. A module's widget names
 * itself by a key into the words its module ships, which this looks at first, under the module's id
 * as their namespace, and a key the module has no entry for falls through to the launcher's words and
 * then shows as itself. So a module in one language can write its names in plain text and ship nothing.
 */
class WidgetLabels(private val moduleOf: (WidgetKind) -> String?) {

    /** [key] as the widget of [kind] says it, in the language of [words]. */
    fun of(kind: WidgetKind, key: String, words: Translator): String {
        moduleOf(kind)?.let { module ->
            val own = words.strings(module)
            if (own.has(key)) return own.text(key)
        }
        return words.strings(NexiraStrings.NAMESPACE).text(key)
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
    LocalWidgetLabels.current.of(kind, key, LocalTranslator.current)

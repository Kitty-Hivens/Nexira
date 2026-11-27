package hivens.pages.api

import hivens.widget.model.WidgetKind
import kotlinx.serialization.Serializable

/**
 * A screen as a list of them shows it. [title] is a key into the words of the module [owner] where [owner] is one,
 * and the person's own name for it where it is null: one they made, or one a module brought that they renamed. [icon]
 * is a Material Symbols name, or null for none. [kind] is the widget that draws a screen a module brought where the
 * host holds nothing of its own for it, null for one a person [made].
 */
data class ScreenView(
    val id: ScreenId,
    val title: String,
    val owner: String?,
    val icon: String? = null,
    val kind: WidgetKind? = null,
    val made: Boolean = false,
)

/**
 * The pages host: the widget that draws the page shown, and keeps in its own record what a person made of the pages.
 * Each screen's content stands in the host's slot named by the screen's id. A screen a module brings and the host
 * holds nothing for draws the widget the module named.
 */
object PagesHost {
    val KIND: WidgetKind = WidgetKind("pages.host")
}

/**
 * The host's own settings, kept in its record: the screen it starts on, its id or blank for the first there is, the
 * screens a person made, in the order they made them, and what a person called and drew for a screen a module brought.
 */
@Serializable
data class PagesHostProps(
    val start: String = "",
    val made: List<MadeScreen> = emptyList(),
    val named: List<MadeScreen> = emptyList(),
)

/**
 * A screen as a person set it: its id, which names its slot of the host, and what they called it and drew for it, blank
 * for what the screen has without them.
 */
@Serializable
data class MadeScreen(
    val id: String,
    val title: String = "",
    val icon: String = "",
)

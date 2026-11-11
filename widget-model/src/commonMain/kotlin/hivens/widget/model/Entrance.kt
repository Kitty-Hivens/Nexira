package hivens.widget.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * How a widget arrives when the surface it sits on is opened.
 *
 * A closed set of characters rather than a duration and a curve. The numbers
 * belong to the interface's motion scale, which the renderer reads, so a widget
 * says what kind of arrival it makes and the scale decides how long that takes.
 * A set of knobs would make every arrival as reachable as every other, the bad
 * ones included; a name keeps them to the few that read as intended.
 *
 * Stored by [id] rather than by name, so a later build that adds a character
 * reads an older file unchanged, and an older build reading a newer file falls
 * back to the widget's own declaration instead of failing.
 */
enum class Entrance(val id: String) {
    /** Already there when the surface opens. */
    None("none"),

    /** Comes up on opacity alone. */
    Fade("fade"),

    /** Comes up while travelling a short way into place from below. */
    Rise("rise"),

    /** Lands: grows the last few percent into place with an overshoot. */
    Settle("settle"),
    ;

    companion object {
        /** What a widget that declares nothing does. */
        val DEFAULT = Fade

        /** The character [id] names, or null for a blank or unknown one. */
        fun parse(id: String?): Entrance? {
            val wanted = id?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            return entries.firstOrNull { it.id == wanted }
        }
    }
}

/**
 * One instance's own say over its arrival, over what its widget declares.
 *
 * Both fields optional and both null meaning "nothing said", which is what lets
 * the declaration show through. [delayMs] null is the slot's own stagger, by the
 * widget's place in it; a number pins it.
 */
@Serializable
data class WidgetMotion(
    val enter: String? = null,
    @SerialName("delay_ms") val delayMs: Int? = null,
) {
    val isEmpty: Boolean get() = enter.isNullOrBlank() && delayMs == null
}

package hivens.look.type.api

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

/** What text is, which decides the face it is drawn in. */
enum class TextRole {
    /** Large decorative text: a clock across a screen, a hero's word. */
    Display,

    /** What a screen or a dialog is about. */
    Heading,

    /** What a section, a card or a row is called. */
    Title,

    /** What is read: sentences, descriptions, values. */
    Body,

    /** What sits on a control or names a field: a button's word, a tag. */
    Label,
}

/**
 * One answer of the type axis: a face, the family text is drawn in, with the weight and the style
 * the look gives it where it gives one. A null [weight] or [style] keeps what the component asks for,
 * so a component that sets a heavier word keeps it. Never a size or a line height: those are the
 * component's, so no face moves a layout by more than its own letters.
 *
 * The family is handed out in composition ([family]), since a face bundled as a resource is read
 * there. A provider answers a face with [of] for a family it already holds, or by extending this class.
 */
@Immutable
abstract class TypeFace {

    /** The family, the same instance on every call wherever the face is the same. */
    @Composable
    abstract fun family(): FontFamily

    /** The weight the look gives this face, null where the component's own weight holds. */
    open val weight: FontWeight? get() = null

    /** The style the look gives this face, null where the component's own style holds. */
    open val style: FontStyle? get() = null

    companion object {
        /** A face of [family], with [weight] and [style] where given. */
        fun of(family: FontFamily, weight: FontWeight? = null, style: FontStyle? = null): TypeFace = HeldFace(family, weight, style)
    }
}

private class HeldFace(
    private val family: FontFamily,
    override val weight: FontWeight?,
    override val style: FontStyle?,
) : TypeFace() {
    @Composable
    override fun family(): FontFamily = family

    override fun equals(other: Any?): Boolean =
        other is HeldFace && other.family == family && other.weight == weight && other.style == style

    override fun hashCode(): Int = listOf(family, weight, style).hashCode()

    override fun toString(): String = "TypeFace.of($family, $weight, $style)"
}

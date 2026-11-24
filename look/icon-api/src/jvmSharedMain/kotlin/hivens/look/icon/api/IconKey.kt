package hivens.look.icon.api

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * An icon a component draws: one of the look's library, by its [name], or a drawing of the component's
 * [own].
 *
 * A name is a Material Symbols name as fonts.google.com/icons shows it, `delete` or `rocket_launch`, any
 * of them. That set is the vocabulary of the axis: no word of it is made up here, so a component never
 * guesses what a library calls an icon, and a provider of another library translates these names to its
 * own. What the launcher itself draws is listed in [Icons].
 *
 * A library need not have a name. Where it does not, [own] is drawn, and where there is none, the icon
 * the contract itself draws for the name, and else nothing, in the same room. A component that needs one
 * exact drawing under every look carries it with [of].
 */
@Immutable
class IconKey private constructor(
    /** The name it is asked by, null for a drawing that asks no library. */
    val name: String?,
    /** The component's own drawing: the icon itself without a [name], what is drawn instead with one. */
    val own: IconDrawing?,
) {
    override fun equals(other: Any?): Boolean = other is IconKey && other.name == name && other.own == own

    override fun hashCode(): Int = 31 * (name?.hashCode() ?: 0) + (own?.hashCode() ?: 0)

    override fun toString(): String = "IconKey(${name ?: own})"

    companion object {
        /** The icon the look's library has by [name], and [otherwise] where it has none. */
        fun named(name: String, otherwise: IconDrawing? = null): IconKey = IconKey(name, otherwise)

        /** [drawing] under every look. */
        fun of(drawing: IconDrawing): IconKey = IconKey(null, drawing)
    }
}

/** How a component wants an icon drawn. Made by [Icon.Draw] and [drawIcon], and read by a drawing. */
@Immutable
class IconStyle internal constructor(
    /** The colour of the icon. A drawing in colours of its own may leave it. */
    val tint: Color,
    /** From 0, an outline, to 1, filled. It may be anything between while it moves from one to the other. */
    val fill: Float,
    /** How heavy its strokes are, from 100 to 700, 400 being regular. */
    val weight: Int,
) {
    override fun equals(other: Any?): Boolean = other is IconStyle && other.tint == tint && other.fill == fill && other.weight == weight

    override fun hashCode(): Int = listOf(tint, fill, weight).hashCode()

    override fun toString(): String = "IconStyle($tint, fill=$fill, weight=$weight)"
}

/**
 * One icon as a library or a component draws it.
 *
 * It draws and lays nothing out, so no library moves a layout, and a component that draws itself puts
 * one into its own drawing ([drawIcon]). It may be drawn on any thread. What it cannot draw of the
 * [IconStyle], a filling or a weight its library has no shape for, it draws as the nearest it has.
 */
@Immutable
abstract class IconDrawing {
    /** Draws the icon in the middle of this scope, which is all the room it has, as [style] wants it. */
    abstract fun DrawScope.draw(style: IconStyle)
}

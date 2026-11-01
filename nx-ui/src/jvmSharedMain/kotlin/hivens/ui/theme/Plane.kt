package hivens.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

/** What is behind a plane. */
enum class Backdrop {
    /** The theme's own page. */
    Page,

    /** A user's picture, darkened with the colour of the page. */
    Wallpaper,

    /** Another plane. */
    Plane,
}

/**
 * The plane content is drawn on: which step of the ladder it is, the colour actually
 * under the content, and what is behind it.
 *
 * Every surface provides one to what it contains, which is what makes depth relative:
 * a card is one step above whatever holds it, and text is made readable against the
 * colour it is really on rather than against a guess.
 */
@Immutable
data class Plane(val step: Int, val color: Color, val over: Backdrop)

/** Null outside a surface; [NxTheme] provides the page. */
val LocalPlane = compositionLocalOf<Plane?> { null }

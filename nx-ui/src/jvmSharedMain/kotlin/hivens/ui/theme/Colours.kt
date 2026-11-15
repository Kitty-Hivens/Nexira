package hivens.ui.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * Text and lines.
 *
 * A screen says which ink it means and never which colour. [main] and [quiet] are the
 * theme's inks exactly as its author gave them, the same on the page, on a card and on
 * a popup: whether they read on each is the author's to answer for. [line] is made on
 * the plane it is drawn on, from that plane and the quiet ink.
 */
object NxInk {
    val main: Color
        @Composable @ReadOnlyComposable
        get() = LocalScheme.current.inkMain

    val quiet: Color
        @Composable @ReadOnlyComposable
        get() = LocalScheme.current.inkQuiet

    /** Unavailable. Faded on purpose: it is there to be seen as off, not read. */
    val off: Color
        @Composable @ReadOnlyComposable
        get() = quiet.copy(alpha = OFF_ALPHA)

    /** A divider or an edge on this plane. */
    val line: Color
        @Composable @ReadOnlyComposable
        get() = LocalScheme.current.line(ground())
}

/**
 * Colours with a meaning, asked for by what they mean.
 *
 * Three kinds of request and no more: the theme's [lead], a [status], and a member of
 * a feature's own [ColorCategory]. Every answer is the theme's own colour as its author
 * gave it, or, where the theme has none to give, one nx-ui made beside it. None is
 * adjusted to the plane it is drawn on.
 */
object NxColor {
    @Composable @ReadOnlyComposable
    fun lead(): Color = LocalScheme.current.lead

    @Composable @ReadOnlyComposable
    fun status(status: Status): Color = LocalScheme.current.status(status)

    @Composable @ReadOnlyComposable
    fun <E> of(member: E): Color where E : Enum<E>, E : ColorCategory {
        val count = checkNotNull(member.declaringJavaClass.enumConstants) { "${member.declaringJavaClass} is not an enum" }.size
        return LocalScheme.current.distinct(count)[member.ordinal]
    }

    /** Stable variety for things that only need to differ, such as an avatar per name. */
    @Composable @ReadOnlyComposable
    fun hashed(key: String): Color = LocalScheme.current.hashed(key)

    /** Ink for content laid on [fill]. */
    @Composable @ReadOnlyComposable
    fun on(fill: Color): Color = LocalScheme.current.on(fill)

    /**
     * [colour] mixed into the current plane by [amount], opaque. What a tinted fill is:
     * a chip that is selected, a row under the pointer, a banner in a status colour.
     * Opaque so it reads the same over a wallpaper as over the page.
     */
    @Composable @ReadOnlyComposable
    fun wash(colour: Color, amount: Float): Color = lerp(ground(), colour.copy(alpha = 1f), amount)

    /** The page colour: what a scrim or a darkened wallpaper is made of. */
    val page: Color
        @Composable @ReadOnlyComposable
        get() = LocalScheme.current.step(0)
}

/**
 * Content drawn on [fill] rather than on the plane: a chip's label, a badge's text, a
 * banner's words. Inside, the fill is the plane, so a [NxInk.line] or a [NxColor.wash]
 * is made on it, and the ambient content colour is [NxColor.on] the fill: the theme's
 * main ink or its page, whichever stands further from it. A translucent fill is taken
 * as what it looks like over the current plane.
 */
@Composable
fun OnFill(fill: Color, content: @Composable () -> Unit) {
    val scheme = LocalScheme.current
    val parent = LocalPlane.current
    val base = parent?.color ?: scheme.step(0)
    val under = if (fill.alpha >= 1f) fill else lerp(base.copy(alpha = 1f), fill.copy(alpha = 1f), fill.alpha)
    CompositionLocalProvider(
        LocalPlane provides Plane(parent?.step ?: 0, under, Backdrop.Plane),
        LocalContentColor provides scheme.on(under),
    ) {
        content()
    }
}

@Composable @ReadOnlyComposable
internal fun ground(): Color = LocalPlane.current?.color ?: LocalScheme.current.step(0)

private const val OFF_ALPHA = 0.45f

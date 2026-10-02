package hivens.ui.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * Text and lines, resolved against the plane they are drawn on.
 *
 * A screen says which ink it means and never which colour: the same `NxInk.quiet` is
 * a different value on the page, on a card and on a popup, and is readable on each.
 */
object NxInk {
    val main: Color
        @Composable @ReadOnlyComposable
        get() = LocalScheme.current.inkMain(ground())

    val quiet: Color
        @Composable @ReadOnlyComposable
        get() = LocalScheme.current.inkQuiet(ground())

    /** Unavailable. Below the reading floor on purpose: it is there to be seen as off, not read. */
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
 * a feature's own [ColorCategory]. Every answer comes fitted to the plane it will be
 * drawn on, three to one for a mark and four and a half for [text], by moving its tone
 * and nothing else, so it stays the theme's colour.
 */
object NxColor {
    @Composable @ReadOnlyComposable
    fun lead(text: Boolean = false): Color = fitted(LocalScheme.current.lead, text)

    @Composable @ReadOnlyComposable
    fun status(status: Status, text: Boolean = false): Color = fitted(LocalScheme.current.status(status), text)

    @Composable @ReadOnlyComposable
    fun <E> of(member: E, text: Boolean = false): Color where E : Enum<E>, E : ColorCategory {
        val count = member.declaringJavaClass.enumConstants.size
        return fitted(LocalScheme.current.distinct(count)[member.ordinal], text)
    }

    /** Stable variety for things that only need to differ, such as an avatar per name. */
    @Composable @ReadOnlyComposable
    fun hashed(key: String, text: Boolean = false): Color = fitted(LocalScheme.current.hashed(key), text)

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
 * banner's words. Inside, every [NxInk] and [NxColor] request is made readable against
 * the fill, and so is the ambient content colour. A translucent fill is taken as what it
 * looks like over the current plane.
 */
@Composable
fun OnFill(fill: Color, content: @Composable () -> Unit) {
    val scheme = LocalScheme.current
    val parent = LocalPlane.current
    val base = parent?.color ?: scheme.step(0)
    val under = if (fill.alpha >= 1f) fill else lerp(base.copy(alpha = 1f), fill.copy(alpha = 1f), fill.alpha)
    CompositionLocalProvider(
        LocalPlane provides Plane(parent?.step ?: 0, under, Backdrop.Plane),
        LocalContentColor provides scheme.inkMain(under),
    ) {
        content()
    }
}

@Composable @ReadOnlyComposable
internal fun ground(): Color = LocalPlane.current?.color ?: LocalScheme.current.step(0)

@Composable @ReadOnlyComposable
private fun fitted(colour: Color, text: Boolean): Color =
    fit(colour, ground(), if (text) SchemeColours.TEXT else SchemeColours.MARK)

private const val OFF_ALPHA = 0.45f

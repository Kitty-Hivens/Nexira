package hivens.ui.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import hivens.look.colour.api.Colour
import hivens.look.colour.api.ColourAnswers

// NxInk and NxColor are nx-ui's names for what the colour axis answers (hivens.look.colour.api), read
// from the answers provided where they are asked and over the plane they are asked on.

/**
 * Text and lines.
 *
 * A screen says which ink it means and never which colour. [main] and [quiet] are the
 * look's inks exactly as it answers them, the same on the page, on a card and on a popup:
 * whether they read on each is the look's to answer for. [line] is made on the plane it
 * is drawn on.
 */
object NxInk {
    /** The colour axis's main ink. */
    val main: Color
        @Composable @ReadOnlyComposable
        get() = answers().inkMain

    /** The colour axis's quiet ink. */
    val quiet: Color
        @Composable @ReadOnlyComposable
        get() = answers().inkQuiet

    /** Unavailable. Faded on purpose: it is there to be seen as off, not read. */
    val off: Color
        @Composable @ReadOnlyComposable
        get() = answers().inkOff

    /** A divider or an edge on this plane. */
    val line: Color
        @Composable @ReadOnlyComposable
        get() = answers().lineDivider(ground())
}

/**
 * Colours with a meaning, asked for by what they mean.
 *
 * Three kinds of request and no more: the look's [lead], a [status], and a member of a
 * feature's own [ColorCategory]. Every answer is the look's own, and none is adjusted to
 * the plane it is drawn on. Beside them the ladder planes are cut from, for the few that
 * draw a plane of their own.
 */
object NxColor {
    @Composable @ReadOnlyComposable
    fun lead(): Color = answers().paintLead

    /** A third colour beside the lead and its partner, for art in three colours and the far end of a gradient. */
    val third: Color
        @Composable @ReadOnlyComposable
        get() = answers().paintThird

    @Composable @ReadOnlyComposable
    fun status(status: Status): Color = answers().paintStatus(status.inLook())

    @Composable @ReadOnlyComposable
    fun <E> of(member: E): Color where E : Enum<E>, E : ColorCategory {
        val count = checkNotNull(member.declaringJavaClass.enumConstants) { "${member.declaringJavaClass} is not an enum" }.size
        return answers().paintCategory(member.ordinal, count)
    }

    /** Member [index] of a set of [count] colours that read as different from each other. */
    @Composable @ReadOnlyComposable
    fun category(index: Int, count: Int): Color = answers().paintCategory(index, count)

    /** Stable variety for things that only need to differ, such as an avatar per name. */
    @Composable @ReadOnlyComposable
    fun hashed(key: String): Color = answers().paintHashed(key)

    /** Ink for content laid on [fill]. */
    @Composable @ReadOnlyComposable
    fun on(fill: Color): Color = answers().inkOn(fill)

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
        get() = answers().groundPage

    /** Plane [n] of the ladder outward from the page, 0 being the page, past the authored ends too. */
    @Composable @ReadOnlyComposable
    fun depth(n: Int): Color = answers().groundDepth(n)

    /** The depth floating planes sit at: a popup, a dialog, a menu. */
    val floating: Int
        @Composable @ReadOnlyComposable
        get() = answers().groundFloating
}

/**
 * Content drawn on [fill] rather than on the plane: a chip's label, a badge's text, a
 * banner's words. Inside, the fill is the plane, so a [NxInk.line] or a [NxColor.wash]
 * is made on it, and the ambient content colour is [NxColor.on] the fill: the look's
 * main ink or its page, whichever stands further from it. A translucent fill is taken
 * as what it looks like over the current plane.
 */
@Composable
fun OnFill(fill: Color, content: @Composable () -> Unit) {
    val answers = answers()
    val parent = LocalPlane.current
    val base = parent?.color ?: answers.groundPage
    val under = if (fill.alpha >= 1f) fill else lerp(base.copy(alpha = 1f), fill.copy(alpha = 1f), fill.alpha)
    CompositionLocalProvider(
        LocalPlane provides Plane(parent?.step ?: 0, under, Backdrop.Plane),
        LocalContentColor provides answers.inkOn(under),
    ) {
        content()
    }
}

@Composable @ReadOnlyComposable
internal fun answers(): ColourAnswers = Colour.local.current

@Composable @ReadOnlyComposable
internal fun ground(): Color = LocalPlane.current?.color ?: answers().groundPage

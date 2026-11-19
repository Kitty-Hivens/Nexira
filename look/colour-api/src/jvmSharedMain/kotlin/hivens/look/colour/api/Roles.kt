package hivens.look.colour.api

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import hivens.look.manager.api.Status

// What a widget asks of colour, by what it draws: text and glyphs (Ink), colours that mean something
// (Paint), areas (Fill), strokes (Line), what content sits on (Ground) and what goes over a picture
// (Media). Each reads the answers of the provider that draws the axis where the widget is, and the
// base answers outside any. A role made over the ground is handed Ground.current.

/**
 * The colour content really sits on here, what every fill, rule and mix is made over: set by whatever
 * draws a plane for what it holds. Null outside any plane, where the content sits on the page.
 */
val LocalGround = compositionLocalOf<Color?> { null }

@Composable
@ReadOnlyComposable
private fun answers(): ColourAnswers = Colour.local.current

/** Text and glyphs. See [ColourAnswers] for what each answers. */
object Ink {
    /** See [ColourAnswers.inkMain]. */
    val main: Color
        @Composable @ReadOnlyComposable
        get() = answers().inkMain

    /** See [ColourAnswers.inkQuiet]. */
    val quiet: Color
        @Composable @ReadOnlyComposable
        get() = answers().inkQuiet

    /** See [ColourAnswers.inkFaint], on the ground here. */
    val faint: Color
        @Composable @ReadOnlyComposable
        get() = answers().inkFaint(Ground.current)

    /** See [ColourAnswers.inkOff]. */
    val off: Color
        @Composable @ReadOnlyComposable
        get() = answers().inkOff

    /** See [ColourAnswers.inkLead]. */
    val lead: Color
        @Composable @ReadOnlyComposable
        get() = answers().inkLead

    /** See [ColourAnswers.inkLink]. */
    val link: Color
        @Composable @ReadOnlyComposable
        get() = answers().inkLink

    /** See [ColourAnswers.inkProse]. */
    val prose: Color
        @Composable @ReadOnlyComposable
        get() = answers().inkProse

    /** See [ColourAnswers.inkCaret]. */
    val caret: Color
        @Composable @ReadOnlyComposable
        get() = answers().inkCaret

    /** See [ColourAnswers.inkInverse]. */
    val inverse: Color
        @Composable @ReadOnlyComposable
        get() = answers().inkInverse

    /** See [ColourAnswers.inkMatch]. */
    val match: Color
        @Composable @ReadOnlyComposable
        get() = answers().inkMatch

    /** See [ColourAnswers.inkOn]. */
    @Composable
    @ReadOnlyComposable
    fun on(fill: Color): Color = answers().inkOn(fill)
}

/** Colours with a meaning, usable as a fill, a mark or text. See [ColourAnswers] for what each answers. */
object Paint {
    /** See [ColourAnswers.paintLead]. */
    val lead: Color
        @Composable @ReadOnlyComposable
        get() = answers().paintLead

    /** See [ColourAnswers.paintMark]. */
    val mark: Color
        @Composable @ReadOnlyComposable
        get() = answers().paintMark

    /** See [ColourAnswers.paintPartner]. */
    val partner: Color
        @Composable @ReadOnlyComposable
        get() = answers().paintPartner

    /** See [ColourAnswers.paintThird]. */
    val third: Color
        @Composable @ReadOnlyComposable
        get() = answers().paintThird

    /** See [ColourAnswers.paintPalette]. */
    val palette: List<Color>
        @Composable @ReadOnlyComposable
        get() = answers().paintPalette

    /** See [ColourAnswers.paintShadow]. */
    val shadow: Color
        @Composable @ReadOnlyComposable
        get() = answers().paintShadow

    /** See [ColourAnswers.paintStatus]. */
    @Composable
    @ReadOnlyComposable
    fun status(status: Status): Color = answers().paintStatus(status)

    /** [member]'s own colour among its enum's, see [ColourAnswers.paintCategory]. */
    @Composable
    @ReadOnlyComposable
    fun <E> category(member: E): Color where E : Enum<E>, E : ColourCategory {
        val count = checkNotNull(member.declaringJavaClass.enumConstants) { "${member.declaringJavaClass} is not an enum" }.size
        return answers().paintCategory(member.ordinal, count)
    }

    /** See [ColourAnswers.paintHashed]. */
    @Composable
    @ReadOnlyComposable
    fun hashed(key: String): Color = answers().paintHashed(key)

    /** See [ColourAnswers.paintData]. */
    @Composable
    @ReadOnlyComposable
    fun data(colour: Color): Color = answers().paintData(colour)

    /** See [ColourAnswers.paintData] for a colour authored per mode. */
    @Composable
    @ReadOnlyComposable
    fun data(dark: Color, light: Color): Color = answers().paintData(dark, light)

    /** See [ColourAnswers.paintShadowOn]. */
    @Composable
    @ReadOnlyComposable
    fun shadow(fill: Color): Color = answers().paintShadowOn(fill)
}

/** Areas on the ground here. See [ColourAnswers] for what each answers. */
object Fill {
    /** See [ColourAnswers.fillLead]. */
    val lead: Color
        @Composable @ReadOnlyComposable
        get() = answers().fillLead(Ground.current)

    /** See [ColourAnswers.fillSelected]. */
    val selected: Color
        @Composable @ReadOnlyComposable
        get() = answers().fillSelected(Ground.current)

    /** See [ColourAnswers.fillQuiet]. */
    val quiet: Color
        @Composable @ReadOnlyComposable
        get() = answers().fillQuiet(Ground.current)

    /** See [ColourAnswers.fillTrack]. */
    val track: Color
        @Composable @ReadOnlyComposable
        get() = answers().fillTrack(Ground.current)

    /** See [ColourAnswers.fillOff]. */
    val off: Color
        @Composable @ReadOnlyComposable
        get() = answers().fillOff(Ground.current)

    /** See [ColourAnswers.fillInverse]. */
    val inverse: Color
        @Composable @ReadOnlyComposable
        get() = answers().fillInverse(Ground.current)

    /** See [ColourAnswers.fillSelection]. */
    val selection: Color
        @Composable @ReadOnlyComposable
        get() = answers().fillSelection(Ground.current)

    /** See [ColourAnswers.fillMatch]. */
    val match: Color
        @Composable @ReadOnlyComposable
        get() = answers().fillMatch(Ground.current)

    /** See [ColourAnswers.fillPlaceholder]. */
    val placeholder: Color
        @Composable @ReadOnlyComposable
        get() = answers().fillPlaceholder(Ground.current)

    /** See [ColourAnswers.fillStatus]. */
    @Composable
    @ReadOnlyComposable
    fun status(status: Status): Color = answers().fillStatus(status, Ground.current)
}

/** Stroke colours on the ground here. See [ColourAnswers] for what each answers. */
object Line {
    /** See [ColourAnswers.lineDivider]. */
    val divider: Color
        @Composable @ReadOnlyComposable
        get() = answers().lineDivider(Ground.current)

    /** See [ColourAnswers.lineFaint]. */
    val faint: Color
        @Composable @ReadOnlyComposable
        get() = answers().lineFaint(Ground.current)

    /** See [ColourAnswers.lineControl]. */
    val control: Color
        @Composable @ReadOnlyComposable
        get() = answers().lineControl(Ground.current)

    /** See [ColourAnswers.lineActive]. */
    val active: Color
        @Composable @ReadOnlyComposable
        get() = answers().lineActive(Ground.current)

    /** See [ColourAnswers.lineFocus]. */
    val focus: Color
        @Composable @ReadOnlyComposable
        get() = answers().lineFocus(Ground.current)

    /** See [ColourAnswers.lineOff]. */
    val off: Color
        @Composable @ReadOnlyComposable
        get() = answers().lineOff(Ground.current)

    /** See [ColourAnswers.lineStatus]. */
    @Composable
    @ReadOnlyComposable
    fun status(status: Status): Color = answers().lineStatus(status, Ground.current)
}

/** What content sits on. See [ColourAnswers] for what each answers. */
object Ground {
    /** The colour content sits on here: what [LocalGround] holds, the page outside any plane. */
    val current: Color
        @Composable @ReadOnlyComposable
        get() = LocalGround.current ?: answers().groundPage

    /** See [ColourAnswers.groundPage]. */
    val page: Color
        @Composable @ReadOnlyComposable
        get() = answers().groundPage

    /** See [ColourAnswers.groundFloating]. */
    val floating: Int
        @Composable @ReadOnlyComposable
        get() = answers().groundFloating

    /** See [ColourAnswers.groundDark]. */
    val dark: Boolean
        @Composable @ReadOnlyComposable
        get() = answers().groundDark

    /** See [ColourAnswers.groundDepth]. */
    @Composable
    @ReadOnlyComposable
    fun depth(n: Int): Color = answers().groundDepth(n)

    /**
     * [colour], made opaque, laid over the ground here by [amount] and kept opaque, so it reads the same
     * over a wallpaper as over the page. Plain arithmetic and no question of the look: for a widget that
     * draws itself with a tint of its own, where no role says what it draws.
     */
    @Composable
    @ReadOnlyComposable
    fun mix(colour: Color, amount: Float): Color = mixed(current, colour, amount)
}

/** Colours over a picture, whatever the look. See [ColourAnswers] for what each answers. */
object Media {
    /** See [ColourAnswers.mediaInk]. */
    val ink: Color
        @Composable @ReadOnlyComposable
        get() = answers().mediaInk

    /** See [ColourAnswers.mediaQuiet]. */
    val quiet: Color
        @Composable @ReadOnlyComposable
        get() = answers().mediaQuiet

    /** See [ColourAnswers.mediaFill]. */
    val fill: Color
        @Composable @ReadOnlyComposable
        get() = answers().mediaFill

    /** See [ColourAnswers.mediaLine]. */
    val line: Color
        @Composable @ReadOnlyComposable
        get() = answers().mediaLine

    /** See [ColourAnswers.mediaScrim]. */
    val scrim: Color
        @Composable @ReadOnlyComposable
        get() = answers().mediaScrim

    /** See [ColourAnswers.mediaStatus]. */
    @Composable
    @ReadOnlyComposable
    fun status(status: Status): Color = answers().mediaStatus(status)
}

package hivens.look.colour.api

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import hivens.look.manager.api.Status

/**
 * Every answer of the colour axis, one per role a widget asks by. A widget reads them through [Ink],
 * [Paint], [Fill], [Line], [Ground] and [Media], which hand the ground content sits on to the roles
 * that are made over it. A provider fills them in with [build].
 *
 * A role a provider leaves unfilled keeps its base answer. The roles that stand alone, the inks, the
 * lead, the page, are colourless greys for a dark ground. The roles the vocabulary makes of others, an
 * ink in the lead, a tinted fill, a rule, follow the base rule over this answer's own roles, so a
 * provider that fills only its lead has fills, marks and edges in that lead. [BASE] is every role at
 * its base answer: a look with no colour at all, which reads as drawn by no provider.
 *
 * "Mixed k into the ground" below means the colour, made opaque, laid over the ground by k and kept
 * opaque, which is what [Ground.mix] does.
 *
 * Answers are never changed: a provider hands new ones whenever one of its roles changes, and two
 * answers are the same only where they are the very same value.
 */
@Immutable
class ColourAnswers private constructor(filled: Builder) {

    /** Body text, titles and primary glyphs, the same on every plane. Base #E8E8E8. */
    val inkMain: Color = filled.inkMain ?: Neutral.INK_MAIN

    /** Secondary text, labels and idle icons. Base #A6A6A6. */
    val inkQuiet: Color = filled.inkQuiet ?: Neutral.INK_QUIET

    /** The look's lead as a solid fill: a primary button, a play disc. Base #BDBDBD, a grey and no accent. */
    val paintLead: Color = filled.paintLead ?: Neutral.LEAD

    /** The page: what scrims, the darkening over a wallpaper and root fills are made of. Base #161616. */
    val groundPage: Color = filled.groundPage ?: Neutral.PAGE

    /** The depth floating planes sit at, on the ladder of [groundDepth]. Base 3. */
    val groundFloating: Int = filled.groundFloating ?: Neutral.FLOATING

    /** Whether the look is drawn dark now: the one answer of the whole look that says so. Base true. */
    val groundDark: Boolean = filled.groundDark ?: true

    /** Disabled text and glyphs. Base the quiet ink at 0.45 alpha. */
    val inkOff: Color = filled.inkOff ?: inkQuiet.copy(alpha = OFF_ALPHA)

    /** Text or a glyph in the lead: a selected label, a section header, an active icon. Base [paintLead]. */
    val inkLead: Color = filled.inkLead ?: paintLead

    /** Link text at rest. Base [inkLead]. */
    val inkLink: Color = filled.inkLink ?: inkLead

    /** Running prose in long text, apart from its headings. Base [inkMain]. */
    val inkProse: Color = filled.inkProse ?: inkMain

    /** The text caret. Base [inkLead]. */
    val inkCaret: Color = filled.inkCaret ?: inkLead

    /** Text and glyphs on [fillInverse]. Base [groundPage]. */
    val inkInverse: Color = filled.inkInverse ?: groundPage

    /** Text on [fillMatch]. Base #1A1A1A. */
    val inkMatch: Color = filled.inkMatch ?: Neutral.MATCH_INK

    /** An indicator that says on or chosen: a switch on, a slider's fill, a tab's underline, a check. Base [paintLead]. */
    val paintMark: Color = filled.paintMark ?: paintLead

    /** A second colour that goes with the lead, for art in two colours and gradients. Base #8C8C8C. */
    val paintPartner: Color = filled.paintPartner ?: Neutral.PARTNER

    /** A third colour beside the lead and the partner, for art in three colours and the far end of a gradient. Base [paintPartner]. */
    val paintThird: Color = filled.paintThird ?: paintPartner

    /**
     * The look's own colours in the order of their weight, the lead first, for decoration that shows them
     * all, such as a field of particles. Never empty. Base the lead, then the partner.
     */
    val paintPalette: List<Color> = filled.paintPalette?.takeIf { it.isNotEmpty() }?.toList() ?: listOf(paintLead, paintPartner)

    /** The colour of a cast shadow. Base black. */
    val paintShadow: Color = filled.paintShadow ?: Color.Black

    /** Text and glyphs over a picture, whatever the look. Base white. */
    val mediaInk: Color = filled.mediaInk ?: Color.White

    /** Secondary text over a picture. Base white at 0.75 alpha. */
    val mediaQuiet: Color = filled.mediaQuiet ?: Color.White.copy(alpha = 0.75f)

    /** A chip or a plate over a picture. Base black at 0.35 alpha. */
    val mediaFill: Color = filled.mediaFill ?: Color.Black.copy(alpha = 0.35f)

    /** An edge over a picture. Base white at 0.25 alpha. */
    val mediaLine: Color = filled.mediaLine ?: Color.White.copy(alpha = 0.25f)

    /** What a scrim over a picture is made of. How strong it is, is not colour's to say. Base black. */
    val mediaScrim: Color = filled.mediaScrim ?: Color.Black

    private val inkFaintRule = filled.inkFaint
    private val inkOnRule = filled.inkOn
    private val paintStatusRule = filled.paintStatus
    private val paintCategoryRule = filled.paintCategory
    private val paintHashedRule = filled.paintHashed
    private val paintDataRule = filled.paintData
    private val paintShadowOnRule = filled.paintShadowOn
    private val groundDepthRule = filled.groundDepth
    private val mediaStatusRule = filled.mediaStatus
    private val fillLeadRule = filled.fillLead
    private val fillSelectedRule = filled.fillSelected
    private val fillQuietRule = filled.fillQuiet
    private val fillTrackRule = filled.fillTrack
    private val fillStatusRule = filled.fillStatus
    private val fillOffRule = filled.fillOff
    private val fillInverseRule = filled.fillInverse
    private val fillSelectionRule = filled.fillSelection
    private val fillMatchRule = filled.fillMatch
    private val fillPlaceholderRule = filled.fillPlaceholder
    private val lineDividerRule = filled.lineDivider
    private val lineFaintRule = filled.lineFaint
    private val lineControlRule = filled.lineControl
    private val lineActiveRule = filled.lineActive
    private val lineFocusRule = filled.lineFocus
    private val lineStatusRule = filled.lineStatus
    private val lineOffRule = filled.lineOff

    /** Hints, placeholders, timestamps, decorative glyphs on [ground]. Base the quiet ink mixed 0.6 into the ground. */
    fun inkFaint(ground: Color): Color = inkFaintRule?.invoke(ground) ?: mixed(ground, inkQuiet, 0.6f)

    /** Text and glyphs laid on [fill]. Base the main ink or the page, whichever stands further from the fill. */
    fun inkOn(fill: Color): Color = inkOnRule?.invoke(fill) ?: listOf(inkMain, groundPage).maxBy { contrast(it, fill) }

    /** A mark, a glyph or text in [status]. Base one neutral grey for every status, as a look with no colour carries none. */
    fun paintStatus(status: Status): Color = paintStatusRule?.invoke(status) ?: Neutral.STATUS

    /**
     * The colour of member [index] of a feature's own set of [count], each member one of its own in
     * order. Base eight greys of different tones, in turn.
     */
    fun paintCategory(index: Int, count: Int): Color = paintCategoryRule?.invoke(index, count) ?: Neutral.SPREAD[index.mod(Neutral.SPREAD.size)]

    /** A stable colour for a name: an avatar, initials, a banner. Base one of the eight of [paintCategory], by the key's hash. */
    fun paintHashed(key: String): Color = paintHashedRule?.invoke(key) ?: paintCategory(key.hashCode().mod(Neutral.SPREAD.size), Neutral.SPREAD.size)

    /** A colour that is data, a source's brand, a loader's, a person's own, as drawn on this look. Base as given. */
    fun paintData(colour: Color): Color = paintDataRule?.invoke(colour) ?: colour

    /** The same for a colour authored once per mode: the one [groundDark] picks, as [paintData] draws it. */
    fun paintData(dark: Color, light: Color): Color = paintData(if (groundDark) dark else light)

    /** The shadow a plane of [fill] casts. Base [paintShadow]. */
    fun paintShadowOn(fill: Color): Color = paintShadowOnRule?.invoke(fill) ?: paintShadow

    /**
     * Plane [n] of the ladder outward from the page, 0 being the page, for a plane or a widget drawing
     * its own. Every step answers, past the authored ends too. Base the neutral steps #161616, #1D1D1D,
     * #242424 and #2C2C2C, carried on by their mean step.
     */
    fun groundDepth(n: Int): Color = groundDepthRule?.invoke(n) ?: Neutral.depth(n)

    /** A status mark over a picture, whatever the look. Base the neutral status grey. */
    fun mediaStatus(status: Status): Color = mediaStatusRule?.invoke(status) ?: Neutral.STATUS

    /** A soft container in the lead on [ground]: a tonal button, a highlighted tile. Base the lead mixed 0.16 into the ground. */
    fun fillLead(ground: Color): Color = fillLeadRule?.invoke(ground) ?: mixed(ground, paintLead, 0.16f)

    /** The chosen one among siblings on [ground]: a chip, a row, a nav entry. Base the lead mixed 0.18 into the ground. */
    fun fillSelected(ground: Color): Color = fillSelectedRule?.invoke(ground) ?: mixed(ground, paintLead, 0.18f)

    /** A neutral fill that sets a control, a tag or a well apart from [ground]. Base the quiet ink mixed 0.10 into the ground. */
    fun fillQuiet(ground: Color): Color = fillQuietRule?.invoke(ground) ?: mixed(ground, inkQuiet, 0.10f)

    /** The unfilled part of a range on [ground]: a progress track, a switch off. Base the quiet ink mixed 0.25 into the ground. */
    fun fillTrack(ground: Color): Color = fillTrackRule?.invoke(ground) ?: mixed(ground, inkQuiet, 0.25f)

    /** The soft container of a notice or a badge in [status] on [ground]. Base the status mixed 0.12 into the ground. */
    fun fillStatus(status: Status, ground: Color): Color = fillStatusRule?.invoke(status, ground) ?: mixed(ground, paintStatus(status), 0.12f)

    /** The fill of a disabled control on [ground]. Base none. */
    fun fillOff(ground: Color): Color = fillOffRule?.invoke(ground) ?: Color.Transparent

    /** A fill that turns the look over on [ground], a dark chip on a light page. Base [inkMain]. */
    fun fillInverse(ground: Color): Color = fillInverseRule?.invoke(ground) ?: inkMain

    /** Selected text on [ground]. Base the lead at 0.4 alpha. */
    fun fillSelection(ground: Color): Color = fillSelectionRule?.invoke(ground) ?: paintLead.copy(alpha = 0.4f)

    /** A find match in text on [ground]. Base #D4D4D4. */
    fun fillMatch(ground: Color): Color = fillMatchRule?.invoke(ground) ?: Neutral.MATCH

    /** A block that stands in for content while it loads on [ground]. Base the quiet ink mixed 0.08 into the ground. */
    fun fillPlaceholder(ground: Color): Color = fillPlaceholderRule?.invoke(ground) ?: mixed(ground, inkQuiet, 0.08f)

    /** A divider, a rule inside a plane of [ground]. Base the quiet ink mixed 0.28 into the ground. */
    fun lineDivider(ground: Color): Color = lineDividerRule?.invoke(ground) ?: mixed(ground, inkQuiet, 0.28f)

    /** A quieter rule between rows on [ground]. Base the quiet ink mixed 0.12 into the ground. */
    fun lineFaint(ground: Color): Color = lineFaintRule?.invoke(ground) ?: mixed(ground, inkQuiet, 0.12f)

    /** The edge a control needs to be found at rest on [ground]: a field, a select, an outlined button. Base the quiet ink mixed 0.5 into the ground. */
    fun lineControl(ground: Color): Color = lineControlRule?.invoke(ground) ?: mixed(ground, inkQuiet, 0.5f)

    /** The edge of an open or focused control, a chosen outline, on [ground]. Base [paintLead]. */
    fun lineActive(ground: Color): Color = lineActiveRule?.invoke(ground) ?: paintLead

    /** The ring keyboard focus draws on [ground]. Base [paintLead]. */
    fun lineFocus(ground: Color): Color = lineFocusRule?.invoke(ground) ?: paintLead

    /** The edge of a container in [status] on [ground]. Base the status mixed 0.4 into the ground. */
    fun lineStatus(status: Status, ground: Color): Color = lineStatusRule?.invoke(status, ground) ?: mixed(ground, paintStatus(status), 0.4f)

    /** The edge of a disabled control on [ground]. Base [lineControl] at half its alpha. */
    fun lineOff(ground: Color): Color = lineOffRule?.invoke(ground) ?: lineControl(ground).let { it.copy(alpha = it.alpha * 0.5f) }

    /**
     * Where a provider fills in the roles it answers, every one empty at first, which keeps its base
     * answer. Each is named as the role it fills on [ColourAnswers], and a role asked over a value is
     * filled with how to answer it for that value.
     */
    class Builder internal constructor() {
        /** See [ColourAnswers.inkMain]. */
        var inkMain: Color? = null

        /** See [ColourAnswers.inkQuiet]. */
        var inkQuiet: Color? = null

        /** See [ColourAnswers.inkFaint]. */
        var inkFaint: ((ground: Color) -> Color)? = null

        /** See [ColourAnswers.inkOff]. */
        var inkOff: Color? = null

        /** See [ColourAnswers.inkLead]. */
        var inkLead: Color? = null

        /** See [ColourAnswers.inkLink]. */
        var inkLink: Color? = null

        /** See [ColourAnswers.inkProse]. */
        var inkProse: Color? = null

        /** See [ColourAnswers.inkCaret]. */
        var inkCaret: Color? = null

        /** See [ColourAnswers.inkOn]. */
        var inkOn: ((fill: Color) -> Color)? = null

        /** See [ColourAnswers.inkInverse]. */
        var inkInverse: Color? = null

        /** See [ColourAnswers.inkMatch]. */
        var inkMatch: Color? = null

        /** See [ColourAnswers.paintLead]. */
        var paintLead: Color? = null

        /** See [ColourAnswers.paintMark]. */
        var paintMark: Color? = null

        /** See [ColourAnswers.paintPartner]. */
        var paintPartner: Color? = null

        /** See [ColourAnswers.paintThird]. */
        var paintThird: Color? = null

        /** See [ColourAnswers.paintPalette]. An empty list keeps the base answer. */
        var paintPalette: List<Color>? = null

        /** See [ColourAnswers.paintStatus]. */
        var paintStatus: ((status: Status) -> Color)? = null

        /** See [ColourAnswers.paintCategory]. */
        var paintCategory: ((index: Int, count: Int) -> Color)? = null

        /** See [ColourAnswers.paintHashed]. */
        var paintHashed: ((key: String) -> Color)? = null

        /** See [ColourAnswers.paintData]. */
        var paintData: ((colour: Color) -> Color)? = null

        /** See [ColourAnswers.paintShadow]. */
        var paintShadow: Color? = null

        /** See [ColourAnswers.paintShadowOn]. */
        var paintShadowOn: ((fill: Color) -> Color)? = null

        /** See [ColourAnswers.fillLead]. */
        var fillLead: ((ground: Color) -> Color)? = null

        /** See [ColourAnswers.fillSelected]. */
        var fillSelected: ((ground: Color) -> Color)? = null

        /** See [ColourAnswers.fillQuiet]. */
        var fillQuiet: ((ground: Color) -> Color)? = null

        /** See [ColourAnswers.fillTrack]. */
        var fillTrack: ((ground: Color) -> Color)? = null

        /** See [ColourAnswers.fillStatus]. */
        var fillStatus: ((status: Status, ground: Color) -> Color)? = null

        /** See [ColourAnswers.fillOff]. */
        var fillOff: ((ground: Color) -> Color)? = null

        /** See [ColourAnswers.fillInverse]. */
        var fillInverse: ((ground: Color) -> Color)? = null

        /** See [ColourAnswers.fillSelection]. */
        var fillSelection: ((ground: Color) -> Color)? = null

        /** See [ColourAnswers.fillMatch]. */
        var fillMatch: ((ground: Color) -> Color)? = null

        /** See [ColourAnswers.fillPlaceholder]. */
        var fillPlaceholder: ((ground: Color) -> Color)? = null

        /** See [ColourAnswers.lineDivider]. */
        var lineDivider: ((ground: Color) -> Color)? = null

        /** See [ColourAnswers.lineFaint]. */
        var lineFaint: ((ground: Color) -> Color)? = null

        /** See [ColourAnswers.lineControl]. */
        var lineControl: ((ground: Color) -> Color)? = null

        /** See [ColourAnswers.lineActive]. */
        var lineActive: ((ground: Color) -> Color)? = null

        /** See [ColourAnswers.lineFocus]. */
        var lineFocus: ((ground: Color) -> Color)? = null

        /** See [ColourAnswers.lineStatus]. */
        var lineStatus: ((status: Status, ground: Color) -> Color)? = null

        /** See [ColourAnswers.lineOff]. */
        var lineOff: ((ground: Color) -> Color)? = null

        /** See [ColourAnswers.groundPage]. */
        var groundPage: Color? = null

        /** See [ColourAnswers.groundDepth]. */
        var groundDepth: ((n: Int) -> Color)? = null

        /** See [ColourAnswers.groundFloating]. */
        var groundFloating: Int? = null

        /** See [ColourAnswers.groundDark]. */
        var groundDark: Boolean? = null

        /** See [ColourAnswers.mediaInk]. */
        var mediaInk: Color? = null

        /** See [ColourAnswers.mediaQuiet]. */
        var mediaQuiet: Color? = null

        /** See [ColourAnswers.mediaFill]. */
        var mediaFill: Color? = null

        /** See [ColourAnswers.mediaLine]. */
        var mediaLine: Color? = null

        /** See [ColourAnswers.mediaScrim]. */
        var mediaScrim: Color? = null

        /** See [ColourAnswers.mediaStatus]. */
        var mediaStatus: ((status: Status) -> Color)? = null
    }

    companion object {
        /** Every role at its base answer: the colour axis where no provider answers it. */
        val BASE: ColourAnswers = ColourAnswers(Builder())

        /** The answers [block] fills in, every role it leaves alone at its base answer. */
        fun build(block: Builder.() -> Unit): ColourAnswers = ColourAnswers(Builder().apply(block))

        private const val OFF_ALPHA = 0.45f
    }
}

/** [colour], made opaque, laid over [ground] by [amount]: what "mixed into the ground" means. */
internal fun mixed(ground: Color, colour: Color, amount: Float): Color = lerp(ground, colour.copy(alpha = 1f), amount)

/** The WCAG contrast ratio between [a] and [b], each taken as opaque. */
private fun contrast(a: Color, b: Color): Float {
    val x = a.copy(alpha = 1f).luminance()
    val y = b.copy(alpha = 1f).luminance()
    return (maxOf(x, y) + 0.05f) / (minOf(x, y) + 0.05f)
}

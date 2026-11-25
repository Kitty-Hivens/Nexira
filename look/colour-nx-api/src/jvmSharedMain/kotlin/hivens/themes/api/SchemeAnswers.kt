package hivens.themes.api

import hivens.look.colour.api.ColourAnswers

/**
 * This scheme as the colour axis's answers: every role nx draws, answered from the scheme the way
 * [SchemeColours] answers it, and every other role left to the axis's own rule over these.
 *
 * What the nx colour provider answers for the theme in use, and what a preview draws a theme in. The
 * sets [SchemeColours] works out once per scheme are worked out under a lock, since the answers are
 * read off the drawing thread too.
 */
fun Scheme.colourAnswers(): ColourAnswers {
    val colours = SchemeColours(this)
    return ColourAnswers.build {
        inkMain = colours.inkMain
        inkQuiet = colours.inkQuiet
        inkOff = colours.inkQuiet.copy(alpha = OFF_ALPHA)
        inkOn = colours::on
        inkInverse = colours.step(0)
        paintLead = colours.lead
        paintPartner = colors.getOrElse(1) { colours.lead }
        paintThird = colors.getOrNull(2)
        paintPalette = colors
        paintStatus = { status -> synchronized(colours) { colours.status(status) } }
        paintCategory = { index, count -> synchronized(colours) { colours.distinct(maxOf(count, index + 1))[index.coerceAtLeast(0)] } }
        paintHashed = { key -> synchronized(colours) { colours.hashed(key) } }
        fillInverse = { colours.inkMain }
        lineDivider = colours::line
        groundPage = colours.step(0)
        groundDepth = colours::step
        groundFloating = colours.topStep
        groundDark = colours.isDark
    }
}

/** How faded the ink of what is off draws the quiet ink. */
private const val OFF_ALPHA = 0.45f

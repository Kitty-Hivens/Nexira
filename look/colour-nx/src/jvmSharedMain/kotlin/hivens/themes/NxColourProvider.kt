package hivens.themes

import hivens.look.colour.api.Colour
import hivens.look.colour.api.ColourAnswers
import hivens.look.colour.api.ColourApi
import hivens.look.manager.api.LookAnswers
import hivens.look.manager.api.LookContext
import hivens.look.manager.api.LookProvider
import hivens.look.manager.api.Status
import hivens.ui.theme.Scheme
import hivens.ui.theme.SchemeColours
import hivens.widget.api.get
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import hivens.ui.theme.Status as SchemeStatus

/**
 * The nx family of the colour axis: the colours of the theme in use, in the scheme of the mode in use,
 * as this module's manager holds them.
 *
 * Before the host is up it answers from the module's own settings and theme file, worked out the way
 * the boot window's theme is ([bootOpening]), and once the host is up from the module's own manager,
 * following the theme and the mode as they change without being opened again. Which theme and mode
 * those are, and what decides them, is this module's alone: the look manager only opens and closes it.
 */
internal object NxColourProvider : LookProvider<ColourAnswers> {
    override val axis: String = Colour.id
    override val family: String = "nx"
    override val contract = ColourApi.VERSION
    override val label: String = "themes.colour"

    override fun open(context: LookContext): LookAnswers<ColourAnswers> {
        context.boot?.let { boot ->
            val opening = bootOpening(boot)
            return LookAnswers.of(nxColourAnswers(opening.theme.scheme(opening.dark)))
        }
        val manager = context.services.get<ThemeManager>()
        var drawn = manager.active.value.scheme(manager.dark.value)
        val answers = MutableStateFlow(nxColourAnswers(drawn))
        context.coroutineScope.launch {
            combine(manager.active, manager.dark) { theme, dark -> theme.scheme(dark) }.collect { scheme ->
                if (scheme != drawn) {
                    drawn = scheme
                    answers.value = nxColourAnswers(scheme)
                }
            }
        }
        return LookAnswers.following(answers.asStateFlow())
    }
}

/**
 * [scheme] as the colour axis's answers: every role nx-ui answers today, `NxInk`, `NxColor` and
 * `NxTheme` read off the same [SchemeColours], answered exactly as they answer it, and every other role
 * left to the base rule over these. The sets nx-ui works out once per scheme are worked out under a
 * lock, since the answers are read off the drawing thread too.
 */
internal fun nxColourAnswers(scheme: Scheme): ColourAnswers {
    val colours = SchemeColours(scheme)
    return ColourAnswers.build {
        inkMain = colours.inkMain
        inkQuiet = colours.inkQuiet
        inkOff = colours.inkQuiet.copy(alpha = OFF_ALPHA)
        inkOn = colours::on
        inkInverse = colours.step(0)
        paintLead = colours.lead
        paintPartner = scheme.colors.getOrElse(1) { colours.lead }
        paintStatus = { status -> synchronized(colours) { colours.status(status.inScheme()) } }
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

private fun Status.inScheme(): SchemeStatus = when (this) {
    Status.Error -> SchemeStatus.Error
    Status.Warning -> SchemeStatus.Warning
    Status.Success -> SchemeStatus.Success
    Status.Info -> SchemeStatus.Info
}

/** How faded `NxInk.off` draws the quiet ink. */
private const val OFF_ALPHA = 0.45f

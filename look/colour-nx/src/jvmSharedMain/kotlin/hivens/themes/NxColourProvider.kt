package hivens.themes

import hivens.look.colour.api.Colour
import hivens.look.colour.api.ColourAnswers
import hivens.look.colour.api.ColourApi
import hivens.look.manager.api.LookAnswers
import hivens.look.manager.api.LookContext
import hivens.look.manager.api.LookProvider
import hivens.themes.api.ThemesApi
import hivens.ui.theme.colourAnswers
import hivens.widget.api.get
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The nx family of the colour axis: the colours of the theme in use, in the scheme of the mode in use,
 * as this module's manager holds them, answered the way nx-ui answers a scheme (`Scheme.colourAnswers`).
 *
 * Before the host is up it answers from the module's own settings and theme file, worked out the way
 * the boot window's theme is ([bootOpening]), and once the host is up from the module's own manager,
 * following the theme and the mode as they change without being opened again. Which theme and mode
 * those are, and what decides them, is this module's alone: the look manager only opens and closes it.
 */
internal object NxColourProvider : LookProvider<ColourAnswers> {
    override val axis: String = Colour.id
    override val family: String = ThemesApi.COLOUR_FAMILY
    override val contract = ColourApi.VERSION
    override val label: String = "themes.colour"

    override fun open(context: LookContext): LookAnswers<ColourAnswers> {
        context.boot?.let { boot ->
            val opening = bootOpening(boot)
            return LookAnswers.of(opening.theme.scheme(opening.dark).colourAnswers())
        }
        val manager = context.services.get<ThemeManager>()
        var drawn = manager.active.value.scheme(manager.dark.value)
        val answers = MutableStateFlow(drawn.colourAnswers())
        context.coroutineScope.launch {
            combine(manager.active, manager.dark) { theme, dark -> theme.scheme(dark) }.collect { scheme ->
                if (scheme != drawn) {
                    drawn = scheme
                    answers.value = scheme.colourAnswers()
                }
            }
        }
        return LookAnswers.following(answers.asStateFlow())
    }
}

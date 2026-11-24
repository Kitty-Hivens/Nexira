package hivens.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import hivens.look.colour.api.Colour
import hivens.look.colour.api.ColourAnswers
import hivens.look.manager.api.LocalLookProvision
import hivens.look.manager.api.LookProvision
import hivens.look.manager.api.Status as LookStatus
import hivens.look.preview.api.PreviewLook
import hivens.look.shape.api.Shape
import hivens.look.stroke.api.Stroke
import hivens.look.surface.api.Surface
import hivens.look.timing.api.Timing
import hivens.look.feedback.api.Feedback
import hivens.look.type.api.Type
import hivens.look.motion.api.Motion as MotionAxis

/**
 * This scheme as the colour axis's answers: every role nx-ui draws, answered from the scheme the way
 * [SchemeColours] answers it, and every other role left to the axis's own rule over these.
 *
 * What the nx colour provider answers for the theme in use, and what [NxThemed] draws a theme in. The
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

/**
 * [content] drawn in [theme], in its dark scheme or its light one by [dark], moving as nx moves, with
 * its decorative movement held still by [reduceMotion], and in nx's corners, strokes, planes, waits,
 * feedback and faces, whatever the look around answers: the theme's answers provided for the colour
 * axis, nx's for the motion axis ([nxMotionAnswers]), the shape axis ([nxShapeAnswers]), the stroke
 * axis ([nxStrokeAnswers]), the surface axis ([nxSurfaceAnswers]), the timing axis ([nxTimingAnswers]),
 * the feedback axis ([nxFeedbackAnswers]) and the type axis ([nxTypeAnswers]), and the bridge
 * ([NxTheme]) set from them. For what draws one theme on purpose, a preview of it or a render of a
 * component in it. [interfaceText] decides the interface face as it does for [NxTheme].
 *
 * Icons are drawn as the look around draws them, and where none is around, in a preview or a test, as
 * the [PreviewLook] there answers.
 */
@Composable
fun NxThemed(
    theme: Theme,
    dark: Boolean = true,
    interfaceText: String = LocalInterfaceText.current,
    reduceMotion: Boolean = false,
    content: @Composable () -> Unit,
) {
    val scheme = theme.scheme(dark)
    val answers = remember(scheme) { scheme.colourAnswers() }
    CompositionLocalProvider(
        Colour.local provides answers,
        MotionAxis.local provides nxMotionAnswers(reduceMotion),
        Shape.local provides nxShapeAnswers(),
        Stroke.local provides nxStrokeAnswers(),
        Surface.local provides nxSurfaceAnswers(),
        Timing.local provides nxTimingAnswers(),
        Feedback.local provides nxFeedbackAnswers(),
        Type.local provides nxTypeAnswers(),
    ) {
        val preview = if (LocalLookProvision.current === LookProvision.NONE) PreviewLook.found else null
        if (preview == null) {
            NxTheme(interfaceText = interfaceText, content = content)
        } else {
            preview.Provide { NxTheme(interfaceText = interfaceText, content = content) }
        }
    }
}

internal fun LookStatus.inScheme(): Status = when (this) {
    LookStatus.Error -> Status.Error
    LookStatus.Warning -> Status.Warning
    LookStatus.Success -> Status.Success
    LookStatus.Info -> Status.Info
}

internal fun Status.inLook(): LookStatus = when (this) {
    Status.Error -> LookStatus.Error
    Status.Warning -> LookStatus.Warning
    Status.Success -> LookStatus.Success
    Status.Info -> LookStatus.Info
}

/** How faded `NxInk.off` draws the quiet ink. */
private const val OFF_ALPHA = 0.45f

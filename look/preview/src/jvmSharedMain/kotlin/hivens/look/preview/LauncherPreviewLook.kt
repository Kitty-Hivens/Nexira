package hivens.look.preview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import hivens.look.colour.api.Colour
import hivens.look.feedback.api.Feedback
import hivens.look.feedback.nx.nxFeedbackAnswers
import hivens.look.icon.api.Icon
import hivens.look.icon.google.rounded.googleRoundedIcons
import hivens.look.motion.api.Motion
import hivens.look.motion.nx.nxMotionAnswers
import hivens.look.preview.api.PreviewLook
import hivens.look.shape.api.Shape
import hivens.look.shape.nx.nxShapeAnswers
import hivens.look.stroke.api.Stroke
import hivens.look.stroke.nx.nxStrokeAnswers
import hivens.look.surface.api.Surface
import hivens.look.surface.nx.nxSurfaceAnswers
import hivens.look.timing.api.Timing
import hivens.look.timing.nx.nxTimingAnswers
import hivens.look.type.api.Type
import hivens.look.type.nx.nxTypeAnswers
import hivens.themes.api.colourAnswers

/**
 * Previews and tests draw as the launcher does before anyone chooses anything: as nx answers and with the
 * icons of the library the launcher ships, in [NeutralTheme]. What draws one theme on purpose provides its
 * colours inside.
 */
class LauncherPreviewLook : PreviewLook {
    @Composable
    override fun Provide(dark: Boolean, reduceMotion: Boolean, content: @Composable () -> Unit) = CompositionLocalProvider(
        Colour.local provides remember(dark) { NeutralTheme.scheme(dark).colourAnswers() },
        Motion.local provides nxMotionAnswers(reduceMotion),
        Shape.local provides nxShapeAnswers(),
        Stroke.local provides nxStrokeAnswers(),
        Surface.local provides nxSurfaceAnswers(),
        Timing.local provides nxTimingAnswers(),
        Feedback.local provides nxFeedbackAnswers(),
        Type.local provides nxTypeAnswers(),
        Icon.local provides googleRoundedIcons(),
        content = content,
    )
}

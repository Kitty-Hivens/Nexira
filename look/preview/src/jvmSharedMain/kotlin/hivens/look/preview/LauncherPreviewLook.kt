package hivens.look.preview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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

/**
 * Previews and tests draw as the launcher does before anyone chooses anything: as nx answers and with the
 * icons of the library the launcher ships. Colour is not here, a preview says itself which theme it is in.
 */
class LauncherPreviewLook : PreviewLook {
    @Composable
    override fun Provide(reduceMotion: Boolean, content: @Composable () -> Unit) = CompositionLocalProvider(
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

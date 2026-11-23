package hivens.ui.theme

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import hivens.look.colour.api.Colour
import hivens.look.colour.api.ColourAnswers
import hivens.look.shape.api.ShapeAnswers
import hivens.look.manager.api.Status as LookStatus
import hivens.look.shape.api.Shape as ShapeAxis
import hivens.look.feedback.api.Feedback
import hivens.look.feedback.api.FeedbackTarget
import hivens.look.type.api.Type

/**
 * The bridge from the look to Compose and Material: everything inside drawn with what the colour axis
 * answers where this is placed, the page as the first plane, and the locals Compose and Material read
 * set from the same answers.
 *
 * Which colours those are is not decided here: a window is placed inside the look the host wraps it in,
 * and a preview or a render inside [NxThemed]. Material components still in use get a colour scheme
 * cut from the same ladder and colours, so they agree with the library around them instead of falling
 * back to Material's own purple, and Material's corners cut from the shape axis's roles
 * ([materialShapes]). Text selection and the right-click menu a text field opens are set from the
 * answers too. The page is the first plane: content directly inside reads against step 0
 * until a surface says otherwise.
 */
@Composable
fun NxTheme(
    // A sample of the interface's own words. Where the type axis has a face of its
    // own for their script, every role is drawn in it, which is the only way a
    // locale the interface face cannot cover stays inside the bundle rather than
    // borrowing whatever the host happens to have. A bridge inside another, a
    // preview of a theme, takes the sample of the one around it.
    interfaceText: String = LocalInterfaceText.current,
    content: @Composable () -> Unit,
) {
    val answers = Colour.local.current
    val shapeAnswers = ShapeAxis.local.current
    val page = answers.groundPage
    val colorScheme = remember(answers) { materialScheme(answers) }
    val shapes = remember(shapeAnswers) { materialShapes(shapeAnswers) }
    val selection = remember(answers) { TextSelectionColors(handleColor = answers.paintLead, backgroundColor = answers.fillSelection(page)) }

    CompositionLocalProvider(
        LocalPlane provides Plane(step = 0, color = page, over = Backdrop.Page),
        LocalMonoFamily provides Type.mono.family(),
        // Resolved once here rather than per call site: a font family is a cache
        // key in the resolver, and familyForText is asked inside Text parameters
        // that recompose with the playback position.
        LocalCjkFamily provides nexiraCjkFamily(),
        LocalInterfaceText provides interfaceText,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            shapes      = shapes,
            typography  = lookTypography(familyForText(interfaceText)),
        ) {
            // Inside MaterialTheme's lambda on purpose: MaterialTheme provides its
            // default ripple into LocalIndication and its own selection colours, so
            // the ones here must be provided beneath it to win. Placed outside, this
            // is a silent no-op.
            CompositionLocalProvider(
                LocalIndication provides Feedback.indication(FeedbackTarget.Control),
                LocalContentColor provides answers.inkMain,
                LocalTextSelectionColors provides selection,
            ) {
                ThemedContextMenu(answers, content)
            }
        }
    }
}

/** The sample of the interface's own words the bridge around was given, empty outside any. */
internal val LocalInterfaceText = compositionLocalOf { "" }

/** Read access to what the colour axis answers, for the few places that need more than a request. */
object NxTheme {
    /** Whether the look is drawn dark now, the colour axis's one answer that says so. */
    val isDark: Boolean
        @Composable @ReadOnlyComposable
        get() = Colour.local.current.groundDark
}

/**
 * Material's scheme, cut from the same ladder. The containers map onto the steps in
 * order, so a Material dialog lands on a step of the look rather than on a tone
 * Material picked for its own palette.
 */
private fun materialScheme(c: ColourAnswers): ColorScheme {
    val page = c.groundDepth(0)
    val lead = c.paintLead
    val second = c.paintPartner
    val third = c.paintThird
    val error = c.paintStatus(LookStatus.Error)
    val ink = c.inkMain
    val quiet = c.inkQuiet
    return if (c.groundDark) {
        darkColorScheme(
            primary = lead, onPrimary = c.inkOn(lead),
            primaryContainer = lerp(c.groundDepth(2), lead, 0.3f), onPrimaryContainer = ink,
            secondary = second, onSecondary = c.inkOn(second),
            secondaryContainer = lerp(c.groundDepth(2), second, 0.3f), onSecondaryContainer = ink,
            tertiary = third, onTertiary = c.inkOn(third),
            background = page, onBackground = ink,
            surface = c.groundDepth(1), onSurface = ink,
            surfaceVariant = c.groundDepth(2), onSurfaceVariant = quiet,
            surfaceTint = lead,
            inverseSurface = ink, inverseOnSurface = page,
            error = error, onError = c.inkOn(error),
            outline = c.lineDivider(c.groundDepth(1)), outlineVariant = c.lineDivider(page),
            scrim = page,
            surfaceDim = page, surfaceBright = c.groundDepth(4),
            surfaceContainerLowest = page, surfaceContainerLow = c.groundDepth(1),
            surfaceContainer = c.groundDepth(2), surfaceContainerHigh = c.groundDepth(3), surfaceContainerHighest = c.groundDepth(4),
        )
    } else {
        lightColorScheme(
            primary = lead, onPrimary = c.inkOn(lead),
            primaryContainer = lerp(c.groundDepth(2), lead, 0.2f), onPrimaryContainer = ink,
            secondary = second, onSecondary = c.inkOn(second),
            secondaryContainer = lerp(c.groundDepth(2), second, 0.2f), onSecondaryContainer = ink,
            tertiary = third, onTertiary = c.inkOn(third),
            background = page, onBackground = ink,
            surface = c.groundDepth(1), onSurface = ink,
            surfaceVariant = c.groundDepth(2), onSurfaceVariant = quiet,
            surfaceTint = lead,
            inverseSurface = ink, inverseOnSurface = page,
            error = error, onError = c.inkOn(error),
            outline = c.lineDivider(c.groundDepth(1)), outlineVariant = c.lineDivider(page),
            scrim = ink,
            surfaceDim = c.groundDepth(4), surfaceBright = page,
            surfaceContainerLowest = page, surfaceContainerLow = c.groundDepth(1),
            surfaceContainer = c.groundDepth(2), surfaceContainerHigh = c.groundDepth(3), surfaceContainerHighest = c.groundDepth(4),
        )
    }
}

/**
 * Material's corners, filled from the shape axis's roles so a Material component and a call site still
 * reading `MaterialTheme.shapes` round as the role it stands for: the tooltip for `extraSmall`, the
 * control for `small`, the card for `medium`, the sheet for `large` and the dialog for `extraLarge`.
 *
 * Material takes corner shapes only. A role answered in another family leaves its slot at the axis's
 * base answer for that role, which is a corner shape.
 */
internal fun materialShapes(a: ShapeAnswers): Shapes = Shapes(
    extraSmall = a.tooltip.corners(ShapeAnswers.BASE.tooltip),
    small      = a.control.corners(ShapeAnswers.BASE.control),
    medium     = a.card.corners(ShapeAnswers.BASE.card),
    large      = a.sheet.corners(ShapeAnswers.BASE.sheet),
    extraLarge = a.dialog.corners(ShapeAnswers.BASE.dialog),
)

private fun Shape.corners(base: Shape): CornerBasedShape =
    this as? CornerBasedShape ?: base as? CornerBasedShape ?: RoundedCornerShape(0.dp)

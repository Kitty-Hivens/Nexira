package hivens.ui.theme

import androidx.compose.foundation.DefaultContextMenuRepresentation
import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import hivens.ui.nx.ThemeStateLayer

/**
 * The active scheme's answers. Static: a theme switch is one recomposition of
 * everything, which is what it is, and nothing reads this per frame.
 */
val LocalScheme = staticCompositionLocalOf<SchemeColours> { error("No theme provided") }

/**
 * Provides [theme] in [dark] or light mode to everything inside.
 *
 * The page is the first plane: content directly inside reads against step 0 until a
 * surface says otherwise. Material components still in use get a colour scheme built
 * from the same ladder and colours, so they agree with the library around them
 * instead of falling back to Material's own purple.
 *
 * A theme with no light scheme stays in its dark one when [dark] is false. The
 * switch that asked is expected to say so. This does not invent the missing half.
 */
@Composable
fun NxTheme(
    theme: Theme = Themes.default,
    dark: Boolean = true,
    // The face every text role is drawn with. Null keeps the bundled Latin face;
    // a locale whose own strings that face cannot cover passes the bundled CJK
    // one instead, which is the only way the interface stays inside the bundle
    // rather than borrowing whatever the host happens to have.
    uiFamily: FontFamily? = null,
    content: @Composable () -> Unit,
) {
    val scheme = theme.scheme(dark)
    val colours = remember(scheme) { SchemeColours(scheme) }
    val page = colours.step(0)
    val colorScheme = remember(colours) { materialScheme(colours) }

    CompositionLocalProvider(
        LocalScheme provides colours,
        LocalPlane provides Plane(step = 0, color = page, over = Backdrop.Page),
        LocalMonoFamily provides nexiraMonoFamily(),
        // Resolved once here rather than per call site: a font family is a cache
        // key in the resolver, and familyForText is asked inside Text parameters
        // that recompose with the playback position.
        LocalCjkFamily provides nexiraCjkFamily(),
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            shapes      = NexiraShapes,
            typography  = nexiraTypography(uiFamily ?: nexiraSansFamily()),
        ) {
            // Inside MaterialTheme's lambda on purpose: MaterialTheme provides its
            // default ripple into LocalIndication, so the app-wide state layer must
            // be provided beneath it to win. Placed outside, this is a silent no-op.
            // The right-click menu a text field opens is drawn by foundation, not by
            // us, and it defaults to an unthemed light popup. One representation at
            // the root reaches every field, since a field never provides its own.
            val contextMenu = remember(colours) {
                val top = colours.step(colours.topStep)
                DefaultContextMenuRepresentation(
                    backgroundColor = top,
                    textColor       = colours.inkMain(top),
                    itemHoverColor  = lerp(top, colours.lead, 0.14f),
                )
            }
            CompositionLocalProvider(
                LocalIndication provides ThemeStateLayer,
                LocalContentColor provides colours.inkMain(page),
                LocalContextMenuRepresentation provides contextMenu,
            ) {
                content()
            }
        }
    }
}

/** Read access to the active scheme, for the few places that need more than a request. */
object NxTheme {
    val isDark: Boolean
        @Composable @ReadOnlyComposable
        get() = LocalScheme.current.isDark

    val colours: SchemeColours
        @Composable @ReadOnlyComposable
        get() = LocalScheme.current
}

/**
 * Material's scheme, cut from the same ladder. The containers map onto the steps in
 * order, so a Material dialog lands on a step of this theme rather than on a tone
 * Material picked for its own palette.
 */
private fun materialScheme(c: SchemeColours): ColorScheme {
    val page = c.step(0)
    val lead = c.lead
    val second = c.scheme.colors.getOrElse(1) { lead }
    val third = c.scheme.colors.getOrElse(2) { second }
    val error = c.status(Status.Error)
    val ink = c.inkMain(page)
    val quiet = c.inkQuiet(c.step(2))
    return if (c.isDark) {
        darkColorScheme(
            primary = lead, onPrimary = c.on(lead),
            primaryContainer = lerp(c.step(2), lead, 0.3f), onPrimaryContainer = ink,
            secondary = second, onSecondary = c.on(second),
            secondaryContainer = lerp(c.step(2), second, 0.3f), onSecondaryContainer = ink,
            tertiary = third, onTertiary = c.on(third),
            background = page, onBackground = ink,
            surface = c.step(1), onSurface = ink,
            surfaceVariant = c.step(2), onSurfaceVariant = quiet,
            surfaceTint = lead,
            inverseSurface = ink, inverseOnSurface = page,
            error = error, onError = c.on(error),
            outline = c.line(c.step(1)), outlineVariant = c.line(page),
            scrim = page,
            surfaceDim = page, surfaceBright = c.step(4),
            surfaceContainerLowest = page, surfaceContainerLow = c.step(1),
            surfaceContainer = c.step(2), surfaceContainerHigh = c.step(3), surfaceContainerHighest = c.step(4),
        )
    } else {
        lightColorScheme(
            primary = lead, onPrimary = c.on(lead),
            primaryContainer = lerp(c.step(2), lead, 0.2f), onPrimaryContainer = ink,
            secondary = second, onSecondary = c.on(second),
            secondaryContainer = lerp(c.step(2), second, 0.2f), onSecondaryContainer = ink,
            tertiary = third, onTertiary = c.on(third),
            background = page, onBackground = ink,
            surface = c.step(1), onSurface = ink,
            surfaceVariant = c.step(2), onSurfaceVariant = quiet,
            surfaceTint = lead,
            inverseSurface = ink, inverseOnSurface = page,
            error = error, onError = c.on(error),
            outline = c.line(c.step(1)), outlineVariant = c.line(page),
            scrim = ink,
            surfaceDim = c.step(4), surfaceBright = page,
            surfaceContainerLowest = page, surfaceContainerLow = c.step(1),
            surfaceContainer = c.step(2), surfaceContainerHigh = c.step(3), surfaceContainerHighest = c.step(4),
        )
    }
}

/**
 * The one place a corner radius is decided.
 *
 * Material's own bundle rather than a token object beside it: `MaterialTheme.shapes`
 * is already what a call site reaches for, `Card`, `Button`, dialogs and sheets read
 * it without being told, and a component that wants the card corner asks for
 * [Shapes.medium] instead of importing a constant. A second vocabulary next to this
 * one is what the style axis was.
 *
 * `small` is the button corner and `medium` the card corner, which is the pairing the
 * primitives assume: a chip, a field and a scrollbar take `small`, a plane takes
 * `medium`, and a floating panel takes `large`.
 */
internal val NexiraShapes: Shapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small      = RoundedCornerShape(8.dp),
    medium     = RoundedCornerShape(12.dp),
    large      = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

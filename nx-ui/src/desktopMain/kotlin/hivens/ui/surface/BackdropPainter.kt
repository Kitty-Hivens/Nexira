package hivens.ui.surface

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier

/**
 * Draws the frosted backdrop -- a blurred slice of the active wallpaper -- behind
 * a [FrostSurface]'s [Backdrop] layer. :nx-ui has no knowledge of the wallpaper or
 * media stack, so client-ui provides the real painter (its FrostBackdrop, which
 * reads the wallpaper recipe) through [LocalBackdropPainter]. That keeps the
 * surface system in the leaf module while the wallpaper rendering stays in
 * client-ui, where the background/media code lives.
 */
typealias BackdropPainter = @Composable (extraBlurDp: Float, modifier: Modifier) -> Unit

/**
 * The active backdrop painter, or null when no wallpaper is in play. When null a
 * [Backdrop] layer draws nothing and the surface leans on its [Fill] for body.
 */
val LocalBackdropPainter = compositionLocalOf<BackdropPainter?> { null }

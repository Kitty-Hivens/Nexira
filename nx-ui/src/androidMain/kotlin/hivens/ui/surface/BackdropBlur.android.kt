package hivens.ui.surface

import androidx.compose.runtime.Composable

/**
 * No blur yet: Compose on Android can blur a layer's own content but has no way to
 * filter what is already painted under it, so glass stays a plain translucent body
 * until a backdrop capture is built for it.
 */
@Composable
internal actual fun rememberBackdropBlur(radiusDp: Float): BackdropBlur? = null

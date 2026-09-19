package hivens.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import org.jetbrains.skia.Bitmap

/**
 * A one-pixel image for a test that needs "some artwork" and does not care what.
 *
 * Built through skiko rather than with `ImageBitmap(w, h)`, which since Compose
 * 1.13 goes through a platform graphics implementation that only a scene
 * registers. A plain unit test has no scene, so that constructor threw unless some
 * earlier test in the same fork happened to build one -- which is what made three
 * media-session tests pass on one machine and fail on every runner. Registering
 * the implementation directly is not open to us: both the registry and the skiko
 * implementation are internal to Compose.
 */
fun testImageBitmap(width: Int = 1, height: Int = 1): ImageBitmap =
    Bitmap().apply { allocN32Pixels(width, height) }.asComposeImageBitmap()

package hivens.ui.effects

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.image.BufferedImage

internal actual fun bitmapOfArgb(pixels: IntArray, width: Int, height: Int): ImageBitmap {
    val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    img.setRGB(0, 0, width, height, pixels, 0, width)
    return img.toComposeImageBitmap()
}

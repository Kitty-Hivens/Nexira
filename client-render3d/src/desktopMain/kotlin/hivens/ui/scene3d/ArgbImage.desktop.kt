package hivens.ui.scene3d

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.image.BufferedImage

internal actual fun ArgbImage(width: Int, height: Int): ArgbImage = object : ArgbImage {
    private val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)

    override fun toImageBitmap(pixels: IntArray): ImageBitmap {
        image.setRGB(0, 0, width, height, pixels, 0, width)
        return image.toComposeImageBitmap()
    }
}

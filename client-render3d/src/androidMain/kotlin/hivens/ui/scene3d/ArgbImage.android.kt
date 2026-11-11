package hivens.ui.scene3d

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

// A bitmap per frame: asImageBitmap wraps the pixels without copying them, so a bitmap
// reused for the next frame would be rewritten while the draw phase is still reading it.
internal actual fun ArgbImage(width: Int, height: Int): ArgbImage = object : ArgbImage {
    override fun toImageBitmap(pixels: IntArray): ImageBitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap.asImageBitmap()
    }
}

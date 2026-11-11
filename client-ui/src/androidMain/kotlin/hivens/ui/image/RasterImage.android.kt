package hivens.ui.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.ByteArrayOutputStream

internal actual fun decodeRaster(bytes: ByteArray): RasterImage? =
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let(::AndroidRaster)

// asImageBitmap wraps the pixels without copying them, so close() must not recycle
// the bitmap: an ImageBitmap taken from it would be left pointing at nothing.
private class AndroidRaster(private val bitmap: Bitmap) : RasterImage {
    override val width: Int get() = bitmap.width
    override val height: Int get() = bitmap.height

    override fun toImageBitmap(): ImageBitmap = bitmap.asImageBitmap()

    override fun encodePng(): ByteArray? {
        val out = ByteArrayOutputStream()
        return if (bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) out.toByteArray() else null
    }

    override fun close() = Unit
}

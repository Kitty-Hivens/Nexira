package hivens.ui.image

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

internal actual fun decodeRaster(bytes: ByteArray): RasterImage? =
    runCatching { SkiaRaster(Image.makeFromEncoded(bytes)) }.getOrNull()

private class SkiaRaster(private val image: Image) : RasterImage {
    override val width: Int get() = image.width
    override val height: Int get() = image.height

    override fun toImageBitmap(): ImageBitmap = image.toComposeImageBitmap()

    override fun encodePng(): ByteArray? = image.encodeToData(EncodedImageFormat.PNG)?.use { it.bytes }

    override fun close() = image.close()
}

package hivens.ui.image

import androidx.compose.ui.graphics.ImageBitmap

/**
 * A decoded picture that can be shown and written back out, whatever backs it.
 *
 * A desktop holds a Skia image, a phone a bitmap. [close] releases what the host
 * holds natively, and an [ImageBitmap] taken before it stays good afterwards.
 */
internal interface RasterImage : AutoCloseable {
    val width: Int
    val height: Int

    fun toImageBitmap(): ImageBitmap

    /** The picture as PNG, or null when it cannot be encoded. */
    fun encodePng(): ByteArray?
}

/** Decodes an encoded picture (PNG, JPEG, ...), or null when [bytes] are not one. */
internal expect fun decodeRaster(bytes: ByteArray): RasterImage?

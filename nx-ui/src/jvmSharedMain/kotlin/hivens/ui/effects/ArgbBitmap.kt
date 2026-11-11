package hivens.ui.effects

import androidx.compose.ui.graphics.ImageBitmap

/**
 * A [width] by [height] bitmap of non-premultiplied ARGB [pixels], row by row.
 *
 * What a host backs it with is its own business: a desktop writes an AWT image and
 * hands it to Skia, a phone fills a bitmap.
 */
internal expect fun bitmapOfArgb(pixels: IntArray, width: Int, height: Int): ImageBitmap

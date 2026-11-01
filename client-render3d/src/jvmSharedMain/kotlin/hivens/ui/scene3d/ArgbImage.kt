package hivens.ui.scene3d

import androidx.compose.ui.graphics.ImageBitmap

/**
 * A fixed-size surface of non-premultiplied ARGB pixels that the rasterizer's output
 * is published through. The host decides what backs it: a desktop reuses one image
 * for every frame, a phone makes a bitmap per frame.
 */
internal interface ArgbImage {
    /** The [pixels] (`width * height` of them, row by row) as a bitmap the draw phase may keep. */
    fun toImageBitmap(pixels: IntArray): ImageBitmap
}

internal expect fun ArgbImage(width: Int, height: Int): ArgbImage

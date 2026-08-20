package hivens.ui.render

import androidx.annotation.VisibleForTesting
import coil3.asImage
import coil3.ImageLoader
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import com.github.weisj.jsvg.parser.LoaderContext
import com.github.weisj.jsvg.parser.SVGLoader
import com.github.weisj.jsvg.parser.resources.ResourcePolicy
import com.github.weisj.jsvg.view.ViewBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.math.ceil

/**
 * Rasterises an SVG at the size the SVG itself declares.
 *
 * Both halves of that matter. The loader's own SVG support on this platform goes
 * through Skia, which draws an SVG's shapes and none of its text -- a shields.io
 * badge came out as two blank colour blocks -- and there is no way to hand Skia a
 * font manager from here. It also scales an SVG to whatever size was asked for
 * rather than to its own, so those blocks arrived the width of the page.
 *
 * A description's markup is written by whoever published the pack, so the parse
 * is given no route off the machine: external references are refused outright
 * rather than fetched, and there is no script engine in this renderer to disable.
 */
internal class SvgImageDecoder(
    private val source: ImageSource,
    private val options: Options,
) : Decoder {

    override suspend fun decode(): DecodeResult? = withContext(Dispatchers.IO) {
        val bytes = source.source().use { it.readByteArray() }
        val bitmap = renderSvg(bytes) ?: return@withContext null
        DecodeResult(image = bitmap.asImage(shareable = true), isSampled = false)
    }

    class Factory : Decoder.Factory {
        override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? =
            if (isSvg(result)) SvgImageDecoder(result.source, options) else null
    }

    internal companion object {

        /**
         * A guard on a scalable format: an SVG can declare any size at all, and a
         * document claiming tens of thousands of pixels a side would ask for a
         * gigabyte of raster before anything looked at it.
         */
        const val MAX_EDGE = 4096

        /** An inlined document may inline documents of its own; this is where that stops. */
        private const val MAX_NESTING = 3

        /** How much larger than its box an icon is drawn before being let down into it. */
        private const val ICON_SUPERSAMPLE = 4f

        private const val MAX_ICON_EDGE = 512

        private const val SVG_DATA_URI = "data:image/svg+xml"

        /** Both spellings occur; the plain one is current, the namespaced one is older. */
        private val HREF_ATTRS = listOf("href", "xlink:href")

        private val LOADER_CONTEXT: LoaderContext = LoaderContext.builder()
            .externalResourcePolicy(ResourcePolicy.DENY_EXTERNAL)
            .build()

        /**
         * Rasterises [bytes] at the size the document declares, or null when it
         * declares nothing usable -- inventing a size would put us back to
         * guessing, and there is nothing to guess from.
         */
        fun renderSvg(bytes: ByteArray): Bitmap? = rasterise(inlineNestedSvg(bytes, depth = 0))?.toSkiaBitmap()

        private fun rasterise(bytes: ByteArray, forcedWidth: Int = 0, forcedHeight: Int = 0): BufferedImage? {
            val document = SVGLoader().load(ByteArrayInputStream(bytes), null, LOADER_CONTEXT) ?: return null
            val declared = document.size()
            val width = if (forcedWidth > 0) forcedWidth else ceilOrZero(declared.width)
            val height = if (forcedHeight > 0) forcedHeight else ceilOrZero(declared.height)
            if (width <= 0 || height <= 0 || width > MAX_EDGE || height > MAX_EDGE) return null

            val raster = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
            val g = raster.createGraphics()
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                document.render(null, g, ViewBox(0f, 0f, width.toFloat(), height.toFloat()))
            } finally {
                g.dispose()
            }
            return raster
        }

        private fun ceilOrZero(v: Float): Int = if (!v.isFinite() || v <= 0f) 0 else ceil(v.toDouble()).toInt()

        /**
         * Replaces every `<image>` whose source is an inline SVG with the same
         * image rasterised.
         *
         * The renderer draws an `<image>` through a raster path, so a nested SVG
         * lands as nothing at all: a shields.io badge names its logo that way, and
         * every badge in a description came out with a blank square where its icon
         * belongs. Rasterising the inner document first is the whole fix, and it
         * stays inside the file -- a `data:` source carries its own bytes, so this
         * reaches for nothing.
         *
         * [depth] bounds the recursion, since an inlined document may name inlined
         * documents of its own.
         */
        private fun inlineNestedSvg(bytes: ByteArray, depth: Int): ByteArray {
            if (depth >= MAX_NESTING) return bytes
            val text = bytes.toString(Charsets.UTF_8)
            if (!text.contains(SVG_DATA_URI, ignoreCase = true)) return bytes
            val doc = runCatching { Jsoup.parse(text, "", Parser.xmlParser()) }.getOrNull() ?: return bytes
            var rewritten = false
            for (image in doc.select("image")) {
                val attr = HREF_ATTRS.firstOrNull { image.hasAttr(it) } ?: continue
                val href = image.attr(attr)
                if (!href.startsWith(SVG_DATA_URI, ignoreCase = true)) continue
                val inner = decodeDataUri(href) ?: continue
                // Drawn into a box the outer document sizes, so it is rasterised
                // larger than that box and let down again -- a fourteen-pixel icon
                // rendered at fourteen pixels is a smear.
                val box = maxOf(image.attr("width").toFloatOrNull() ?: 0f, image.attr("height").toFloatOrNull() ?: 0f)
                val edge = (box * ICON_SUPERSAMPLE).toInt().coerceIn(0, MAX_ICON_EDGE)
                val raster = rasterise(inlineNestedSvg(inner, depth + 1), edge, edge) ?: continue
                val png = ByteArrayOutputStream().also { ImageIO.write(raster, "png", it) }.toByteArray()
                image.attr(attr, "data:image/png;base64," + Base64.getEncoder().encodeToString(png))
                rewritten = true
            }
            return if (rewritten) doc.outerHtml().toByteArray(Charsets.UTF_8) else bytes
        }

        /** Base64 or percent-encoded; both spellings are ordinary in the wild. */
        private fun decodeDataUri(uri: String): ByteArray? {
            val comma = uri.indexOf(',')
            if (comma < 0) return null
            val payload = uri.substring(comma + 1)
            val meta = uri.substring(0, comma)
            return runCatching {
                if (meta.contains(";base64", ignoreCase = true)) Base64.getMimeDecoder().decode(payload)
                else URLDecoder.decode(payload, Charsets.UTF_8).toByteArray(Charsets.UTF_8)
            }.getOrNull()
        }

        /**
         * Whether this response is SVG. The declared type is believed where there
         * is one; where there is not, the head of the document is read, since a
         * plain file and a CDN that answers `application/octet-stream` are both
         * ordinary.
         */
        @VisibleForTesting
        fun isSvg(result: SourceFetchResult): Boolean {
            if (result.mimeType?.startsWith("image/svg") == true) return true
            val head = result.source.source().peek().readByteArray(SNIFF_BYTES.toLong().coerceAtMost(Long.MAX_VALUE))
            return looksLikeSvg(head)
        }

        private const val SNIFF_BYTES = 1024

        /**
         * A root `<svg` within the head of the document. An XML prolog, a comment
         * or a doctype may sit in front of it, so the tag is looked for rather
         * than expected first.
         */
        @VisibleForTesting
        fun looksLikeSvg(head: ByteArray): Boolean {
            val text = String(head, Charsets.UTF_8)
            return text.contains("<svg", ignoreCase = true)
        }
    }
}

/**
 * The rendered raster as a Skia bitmap.
 *
 * `TYPE_INT_ARGB` is straight alpha in ARGB word order; Skia is told the same in
 * byte order, which on this platform is the reverse. Handing it premultiplied
 * data instead would darken every antialiased edge.
 */
private fun BufferedImage.toSkiaBitmap(): Bitmap {
    val pixels = IntArray(width * height)
    getRGB(0, 0, width, height, pixels, 0, width)
    val out = ByteArray(pixels.size * 4)
    for (i in pixels.indices) {
        val argb = pixels[i]
        val o = i * 4
        out[o] = (argb and 0xFF).toByte()
        out[o + 1] = ((argb ushr 8) and 0xFF).toByte()
        out[o + 2] = ((argb ushr 16) and 0xFF).toByte()
        out[o + 3] = ((argb ushr 24) and 0xFF).toByte()
    }
    val bitmap = Bitmap()
    bitmap.allocPixels(ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL))
    bitmap.installPixels(out)
    bitmap.setImmutable()
    return bitmap
}

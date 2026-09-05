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
import okio.BufferedSource
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
        val bytes = source.source().use { s ->
            // One byte past the ceiling, so a document that sits exactly on it is
            // still read and one that exceeds it is recognised without the whole
            // of it being pulled into memory first. renderSvg refuses the excess.
            s.request(MAX_SOURCE_BYTES + 1)
            s.readByteArray(minOf(s.buffer.size, MAX_SOURCE_BYTES + 1))
        }
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

        /** How many inlined images one document may carry. A badge has one. */
        private const val MAX_INLINED_IMAGES = 32

        /**
         * A ceiling on the source itself. The bytes arrive from wherever a pack
         * description points, and a size check on the parsed document is no help
         * to the read that has already happened.
         */
        private const val MAX_SOURCE_BYTES = 8L * 1024 * 1024

        /** A guard on the box an inlined icon resolves to, however it resolves to it. */
        private const val MAX_ICON_EDGE = 512

        /**
         * Total pixels one document may cause to be rasterised, itself included.
         * Sixteen megapixels is four full-size documents or a great many icons,
         * and past it a description is not illustrating anything.
         */
        private const val MAX_TOTAL_PIXELS = 16L * 1024 * 1024

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
        fun renderSvg(bytes: ByteArray): Bitmap? {
            // Measured on the source, before the inlining pass, which only adds to
            // it. Checked here rather than at the one call that reads a response,
            // so every way in is bounded by the same number.
            if (bytes.size > MAX_SOURCE_BYTES) return null
            return rasterise(inlineNestedSvg(bytes, depth = 0, budget = PixelBudget()))?.toSkiaBitmap()
        }

        /**
         * One document's worth of rasterising, shared by every inlined icon in it.
         *
         * Counting per call bounds nothing: an outer document may inline thirty-two
         * icons, each of which inlines thirty-two of its own, so a per-call limit
         * multiplies with the depth limit instead of standing against it. Pixels
         * rather than documents, because what runs out is memory, and a document is
         * any size it says it is.
         */
        private class PixelBudget(var remaining: Long = MAX_TOTAL_PIXELS) {
            /** Whether [w] by [h] fits in what is left, taking it if it does. */
            fun take(w: Int, h: Int): Boolean {
                val cost = w.toLong() * h.toLong()
                if (cost > remaining) return false
                remaining -= cost
                return true
            }
        }

        /**
         * [cap] bounds the size a document is allowed to resolve to on its own.
         * An inlined icon is drawn into a box the outer document gives it, and
         * where that box is not stated the inner document's own size is used --
         * which is a number the same untrusted file chose. Without a cap there, a
         * few hundred bytes naming eight icons of four thousand pixels a side ran
         * the heap out before anything was drawn.
         */
        private fun rasterise(
            bytes: ByteArray,
            forcedWidth: Int = 0,
            forcedHeight: Int = 0,
            cap: Int = MAX_EDGE,
            budget: PixelBudget? = null,
        ): BufferedImage? {
            val document = SVGLoader().load(ByteArrayInputStream(bytes), null, LOADER_CONTEXT) ?: return null
            val declared = document.size()
            // Refused on what it asks for, then clamped to what it is allowed.
            // Clamping first would turn "this document wants a hundred thousand
            // pixels a side" into a perfectly ordinary request for four thousand.
            val askedW = if (forcedWidth > 0) forcedWidth else ceilOrZero(declared.width)
            val askedH = if (forcedHeight > 0) forcedHeight else ceilOrZero(declared.height)
            if (askedW <= 0 || askedH <= 0 || askedW > MAX_EDGE || askedH > MAX_EDGE) return null
            val width = askedW.coerceAtMost(cap)
            val height = askedH.coerceAtMost(cap)
            if (budget != null && !budget.take(width, height)) return null

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
         * An SVG length as whole pixels, or zero for one that does not resolve to
         * pixels on its own.
         *
         * A bare number and an absolute unit are the two forms that mean a size
         * without knowing anything else. A percentage or a font-relative unit is
         * a fraction of something this does not have, so it reads as zero and the
         * caller falls back to the document's own size rather than inventing one.
         */
        @VisibleForTesting
        fun lengthPx(raw: String): Int {
            val v = raw.trim().lowercase()
            if (v.isEmpty() || v.endsWith("%")) return 0
            val unit = ABSOLUTE_UNITS.keys.firstOrNull { v.endsWith(it) }
            val number = (if (unit != null) v.dropLast(unit.length) else v).trim().toFloatOrNull() ?: return 0
            val px = number * (unit?.let { ABSOLUTE_UNITS.getValue(it) } ?: 1f)
            return ceilOrZero(px).coerceAtMost(MAX_ICON_EDGE)
        }

        /** CSS absolute units, in pixels. The relative ones are deliberately absent. */
        private val ABSOLUTE_UNITS = mapOf(
            "px" to 1f, "pt" to 4f / 3f, "pc" to 16f, "in" to 96f, "cm" to 96f / 2.54f, "mm" to 96f / 25.4f,
        )

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
        private fun inlineNestedSvg(bytes: ByteArray, depth: Int, budget: PixelBudget): ByteArray {
            if (depth >= MAX_NESTING || budget.remaining <= 0) return bytes
            val text = bytes.toString(Charsets.UTF_8)
            if (!text.contains(SVG_DATA_URI, ignoreCase = true)) return bytes
            val doc = runCatching { Jsoup.parse(text, "", Parser.xmlParser()) }.getOrNull() ?: return bytes
            var rewritten = false
            var inlined = 0
            for (image in doc.select("image")) {
                // Depth alone bounds nothing: a document naming a hundred icons,
                // each naming a hundred of its own, is a million rasterisations
                // from one line of a description. Breadth is bounded here and the
                // depth bound above stops it compounding.
                if (inlined >= MAX_INLINED_IMAGES || budget.remaining <= 0) break
                val attr = HREF_ATTRS.firstOrNull { image.hasAttr(it) } ?: continue
                val href = image.attr(attr)
                if (!href.startsWith(SVG_DATA_URI, ignoreCase = true)) continue
                val inner = decodeDataUri(href) ?: continue
                // Rasterised at exactly the box the outer document gives it, so
                // nothing resamples it afterwards. Rendering larger and letting the
                // outer draw shrink it is worse, not better: that scaling is done
                // by nearest neighbour and no rendering hint reaches it, which is
                // what turned a smooth mark into a handful of hard squares. A
                // vector drawn straight at fourteen pixels is antialiased there.
                // Clamped, never refused. A box this cannot read -- absent, given
                // in units, given as a percentage -- means "use the inner
                // document's own size", which is what rasterise does with a zero;
                // refusing there would put the blank square back, which is the
                // whole thing this exists to remove.
                val boxW = lengthPx(image.attr("width"))
                val boxH = lengthPx(image.attr("height"))
                val raster = rasterise(
                    bytes = inlineNestedSvg(inner, depth + 1, budget),
                    forcedWidth = boxW,
                    forcedHeight = boxH,
                    cap = MAX_ICON_EDGE,
                    budget = budget,
                ) ?: continue
                val png = ByteArrayOutputStream().also { ImageIO.write(raster, "png", it) }.toByteArray()
                image.attr(attr, "data:image/png;base64," + Base64.getEncoder().encodeToString(png))
                rewritten = true
                inlined++
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
                else percentDecode(payload).toByteArray(Charsets.UTF_8)
            }.getOrNull()
        }

        /**
         * Percent-decoding, which is what a `data:` URI uses. The form decoder is
         * not the same thing: it reads `+` as a space and refuses a lone `%`, and
         * an unencoded SVG saying `width='100%'` is both ordinary and refused --
         * so the icon vanished rather than being drawn.
         */
        @VisibleForTesting
        fun percentDecode(raw: String): String {
            if ('%' !in raw) return raw
            val out = StringBuilder(raw.length)
            var i = 0
            while (i < raw.length) {
                val c = raw[i]
                val hex = if (c == '%' && i + 2 < raw.length) raw.substring(i + 1, i + 3).toIntOrNull(16) else null
                if (hex != null) {
                    out.append(hex.toChar())
                    i += 3
                } else {
                    out.append(c)
                    i++
                }
            }
            return out.toString()
        }

        /**
         * Whether this response is SVG.
         *
         * A declared image type is believed in both directions -- a response that
         * says it is a PNG is not read at all. Anything vaguer than that (a plain
         * file, a CDN answering `application/octet-stream`, no type at all) has
         * its head looked at.
         *
         * This runs for every image the app loads, not only for descriptions, so
         * it must not be able to fail: reading a fixed number of bytes demands
         * that many exist, and a favicon or a one-pixel spacer is shorter than
         * that. It takes what is there.
         */
        @VisibleForTesting
        fun isSvg(result: SourceFetchResult): Boolean {
            val mime = result.mimeType
            if (mime != null && mime.startsWith("image/")) return mime.startsWith("image/svg")
            return looksLikeSvg(sniffHead(result.source.source()))
        }

        /** Up to [SNIFF_BYTES] from the front of [source], without consuming it and without demanding they exist. */
        @VisibleForTesting
        fun sniffHead(source: BufferedSource): ByteArray {
            val peek = source.peek()
            peek.request(SNIFF_BYTES)
            return peek.readByteArray(minOf(SNIFF_BYTES, peek.buffer.size))
        }

        private const val SNIFF_BYTES = 1024L

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

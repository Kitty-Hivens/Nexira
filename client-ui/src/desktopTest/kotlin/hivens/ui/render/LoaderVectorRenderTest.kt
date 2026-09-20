package hivens.ui.render

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke as StrokeStyle
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hivens.ui.theme.NxTheme
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The loader glyph as a PATH against the loader glyph as a PICTURE.
 *
 * A raster is fixed at the size it was baked, so the same glyph at a heading
 * size is a blur, and its colour has to be substituted into the source text
 * before the raster exists. The glyphs themselves are forms -- a 24x24 box, no
 * fill, round caps and joins -- and Compose reads that form directly.
 *
 * The first cut of this sheet drew four of five and left Fabric blank, which is
 * the finding: a `d` attribute is not the whole glyph. Fabric is authored around
 * (800, 760) and brought back into the box by a `transform` matrix, with a
 * `stroke-width` of 23 that the same matrix takes down to two. Reading the path
 * and dropping its transform put it a thousand units off screen. Whatever ships
 * either carries the transform beside the path or has it flattened in before it
 * gets here.
 *
 * [SvgImageDecoder] keeps its job: an arbitrary third-party SVG off the web may
 * carry gradients, filters and embedded rasters, which is what its pixel budget
 * exists for. A known glyph is not that.
 */
class LoaderVectorRenderTest {

    /** One stroked path of a glyph: its data, its width, and the matrix that places it. */
    private class Stroke(val d: String, val width: Float, val matrix: FloatArray?)

    private object Paths {
        val FABRIC = listOf(
            Stroke("m820 761-85.6-87.6c-4.6-4.7-10.4-9.6-25.9 1-19.9 13.6-8.4 21.9-5.2 25.4 8.2 9 84.1 89 97.2 104 2.5 2.8-20.3-22.5-6.5-39.7 5.4-7 18-12 26-3 6.5 7.3 10.7 18-3.4 29.7-24.7 20.4-102 82.4-127 103-12.5 10.3-28.5 2.3-35.8-6-7.5-8.9-30.6-34.6-51.3-58.2-5.5-6.3-4.1-19.6 2.3-25 35-30.3 91.9-73.8 111.9-90.8", 23f, floatArrayOf(0.08671f, 0f, 0f, 0.0867f, -49.8f, -56f)),
        )
        val FORGE = listOf(
            Stroke("M2 7.5h8v-2h12v2s-7 3.4-7 6 3.1 3.1 3.1 3.1l.9 3.9H5l1-4.1s3.8.1 4-2.9c.2-2.7-6.5-.7-8-6Z", 2f, null),
        )
        val NEOFORGE = listOf(
            Stroke("m12 19.2v2m0-2v2", 2f, null),
            Stroke("m8.4 1.3c0.5 1.5 0.7 3 0.1 4.6-0.2 0.5-0.9 1.5-1.6 1.5m8.7-6.1c-0.5 1.5-0.7 3-0.1 4.6 0.2 0.6 0.9 1.5 1.6 1.5", 2f, null),
            Stroke("m3.6 15.8h-1.7m18.5 0h1.7", 2f, null),
            Stroke("m3.2 12.1h-1.7m19.3 0h1.8", 2f, null),
            Stroke("m8.1 12.7v1.6m7.8-1.6v1.6", 2f, null),
            Stroke("m10.8 18h1.2m0 1.2-1.2-1.2m2.4 0h-1.2m0 1.2 1.2-1.2", 2f, null),
            Stroke("m4 9.7c-0.5 1.2-0.8 2.4-0.8 3.7 0 3.1 2.9 6.3 5.3 8.2 0.9 0.7 2.2 1.1 3.4 1.1m0.1-17.8c-1.1 0-2.1 0.2-3.2 0.7m11.2 4.1c0.5 1.2 0.8 2.4 0.8 3.7 0 3.1-2.9 6.3-5.3 8.2-0.9 0.7-2.2 1.1-3.4 1.1m-0.1-17.8c1.1 0 2.1 0.2 3.2 0.7", 2f, null),
            Stroke("m4 9.7c-0.2-1.8-0.3-3.7 0.5-5.5s2.2-2.6 3.9-3m11.6 8.5c0.2-1.9 0.3-3.7-0.5-5.5s-2.2-2.6-3.9-3", 2f, null),
            Stroke("m12 21.2-2.4 0.4m2.4-0.4 2.4 0.4", 2f, null),
        )
        val IRIS = listOf(
            Stroke("m22.59 12.013-3.01 3.126v4.405l.005.019-4.251-.005-2.994 3.115h-.003l-3.003-3.132H5.1l-.018.005.005-4.424-2.994-3.116-.003-.023L5.1 8.858V4.452l-.005-.019 4.252.005 2.993-3.115h.003l3.003 3.132h4.234l.018-.005-.005 4.425 2.994 3.115", 2f, null),
            Stroke("m17.229 12.005-1.436 1.491v2.101l.003.009-2.028-.002-1.428 1.486h-.001l-1.433-1.494H8.887l-.008.002.002-2.11-1.428-1.486-.001-.011L8.887 10.5V8.399l-.002-.009 2.027.002 1.428-1.485h.002l1.432 1.494h2.019l.009-.003-.003 2.11 1.428 1.486", 2f, null),
        )
        val OPTIFINE = listOf(
            Stroke("M10.985 9.205c0-1.38-1.121-2.5-2.5-2.5H7.156a2.5 2.5 0 0 0-2.5 2.5v5.59a2.5 2.5 0 0 0 2.5 2.5h1.329c1.379 0 2.5-1.12 2.5-2.5v-5.59ZM14.793 17.295v-9.34a1.252 1.252 0 0 1 1.25-1.25h3.301M18.007 10.997h-3.214", 2f, null),
        )
        val QUILT = listOf(
            Stroke("M442.5 233.9c0-6.4-5.2-11.6-11.6-11.6h-197c-6.4 0-11.6 5.2-11.6 11.6v197c0 6.4 5.2 11.6 11.6 11.6h197c6.4 0 11.6-5.2 11.6-11.7v-197Z", 65.6f, null),
            Stroke("M442.5 234.8c0-7-5.6-12.5-12.5-12.5H234.7c-6.8 0-12.4 5.6-12.4 12.5V430c0 6.9 5.6 12.5 12.4 12.5H430c6.9 0 12.5-5.6 12.5-12.5V234.8Z", 70.4f, null),
        )
        val MINECRAFT = listOf(
            Stroke("M9.504 1.132a1 1 0 01.992 0l1.75 1a1 1 0 11-.992 1.736L10 3.152l-1.254.716a1 1 0 11-.992-1.736l1.75-1zM5.618 4.504a1 1 0 01-.372 1.364L5.016 6l.23.132a1 1 0 11-.992 1.736L4 7.723V8a1 1 0 01-2 0V6a.996.996 0 01.52-.878l1.734-.99a1 1 0 011.364.372zm8.764 0a1 1 0 011.364-.372l1.733.99A1.002 1.002 0 0118 6v2a1 1 0 11-2 0v-.277l-.254.145a1 1 0 11-.992-1.736l.23-.132-.23-.132a1 1 0 01-.372-1.364zm-7 4a1 1 0 011.364-.372L10 8.848l1.254-.716a1 1 0 11.992 1.736L11 10.58V12a1 1 0 11-2 0v-1.42l-1.246-.712a1 1 0 01-.372-1.364zM3 11a1 1 0 011 1v1.42l1.246.712a1 1 0 11-.992 1.736l-1.75-1A1 1 0 012 14v-2a1 1 0 011-1zm14 0a1 1 0 011 1v2a1 1 0 01-.504.868l-1.75 1a1 1 0 11-.992-1.736L16 13.42V12a1 1 0 011-1zm-9.618 5.504a1 1 0 011.364-.372l.254.145V16a1 1 0 112 0v.277l.254-.145a1 1 0 11.992 1.736l-1.735.992a.995.995 0 01-1.022 0l-1.735-.992a1 1 0 01-.372-1.364z", 2f, null),
        )
    }

    private object Svg {
    const val FABRIC = "<svg xmlns=\"http://www.w3.org/2000/svg\" xml:space=\"preserve\" fill-rule=\"evenodd\" stroke-linecap=\"round\" stroke-linejoin=\"round\" clip-rule=\"evenodd\" viewBox=\"0 0 24 24\">   <path fill=\"none\" d=\"M0 0h24v24H0z\"/>   <path fill=\"none\" stroke=\"currentColor\" stroke-width=\"23\" d=\"m820 761-85.6-87.6c-4.6-4.7-10.4-9.6-25.9 1-19.9 13.6-8.4 21.9-5.2 25.4 8.2 9 84.1 89 97.2 104 2.5 2.8-20.3-22.5-6.5-39.7 5.4-7 18-12 26-3 6.5 7.3 10.7 18-3.4 29.7-24.7 20.4-102 82.4-127 103-12.5 10.3-28.5 2.3-35.8-6-7.5-8.9-30.6-34.6-51.3-58.2-5.5-6.3-4.1-19.6 2.3-25 35-30.3 91.9-73.8 111.9-90.8\" transform=\"matrix(.08671 0 0 .0867 -49.8 -56)\"/> </svg>"
    const val NEOFORGE = "<svg enable-background=\"new 0 0 24 24\" version=\"1.1\" viewBox=\"0 0 24 24\" xml:space=\"preserve\" xmlns=\"http://www.w3.org/2000/svg\"><g fill=\"none\" stroke=\"currentColor\" stroke-linecap=\"round\" stroke-linejoin=\"round\" stroke-width=\"2\"><path d=\"m12 19.2v2m0-2v2\"/><path d=\"m8.4 1.3c0.5 1.5 0.7 3 0.1 4.6-0.2 0.5-0.9 1.5-1.6 1.5m8.7-6.1c-0.5 1.5-0.7 3-0.1 4.6 0.2 0.6 0.9 1.5 1.6 1.5\"/><path d=\"m3.6 15.8h-1.7m18.5 0h1.7\"/><path d=\"m3.2 12.1h-1.7m19.3 0h1.8\"/><path d=\"m8.1 12.7v1.6m7.8-1.6v1.6\"/><path d=\"m10.8 18h1.2m0 1.2-1.2-1.2m2.4 0h-1.2m0 1.2 1.2-1.2\"/><path d=\"m4 9.7c-0.5 1.2-0.8 2.4-0.8 3.7 0 3.1 2.9 6.3 5.3 8.2 0.9 0.7 2.2 1.1 3.4 1.1m0.1-17.8c-1.1 0-2.1 0.2-3.2 0.7m11.2 4.1c0.5 1.2 0.8 2.4 0.8 3.7 0 3.1-2.9 6.3-5.3 8.2-0.9 0.7-2.2 1.1-3.4 1.1m-0.1-17.8c1.1 0 2.1 0.2 3.2 0.7\"/><path d=\"m4 9.7c-0.2-1.8-0.3-3.7 0.5-5.5s2.2-2.6 3.9-3m11.6 8.5c0.2-1.9 0.3-3.7-0.5-5.5s-2.2-2.6-3.9-3\"/><path d=\"m12 21.2-2.4 0.4m2.4-0.4 2.4 0.4\"/></g></svg>"
    const val FORGE = "<svg xml:space=\"preserve\" fill-rule=\"evenodd\" stroke-linecap=\"round\" stroke-linejoin=\"round\" stroke-miterlimit=\"1.5\" clip-rule=\"evenodd\" viewBox=\"0 0 24 24\">   <path fill=\"none\" d=\"M0 0h24v24H0z\"></path>   <path fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" d=\"M2 7.5h8v-2h12v2s-7 3.4-7 6 3.1 3.1 3.1 3.1l.9 3.9H5l1-4.1s3.8.1 4-2.9c.2-2.7-6.5-.7-8-6Z\"></path> </svg>"
    const val QUILT = "<svg xmlns:xlink=\"http://www.w3.org/1999/xlink\" xml:space=\"preserve\" fill-rule=\"evenodd\" stroke-linecap=\"round\" stroke-linejoin=\"round\" stroke-miterlimit=\"2\" clip-rule=\"evenodd\" viewBox=\"0 0 24 24\">   <defs>     <path id=\"quilt\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"65.6\" d=\"M442.5 233.9c0-6.4-5.2-11.6-11.6-11.6h-197c-6.4 0-11.6 5.2-11.6 11.6v197c0 6.4 5.2 11.6 11.6 11.6h197c6.4 0 11.6-5.2 11.6-11.7v-197Z\"></path>   </defs>   <path fill=\"none\" d=\"M0 0h24v24H0z\"></path>   <use xlink:href=\"#quilt\" stroke-width=\"65.6\" transform=\"matrix(.03053 0 0 .03046 -3.2 -3.2)\"></use>   <use xlink:href=\"#quilt\" stroke-width=\"65.6\" transform=\"matrix(.03053 0 0 .03046 -3.2 7)\"></use>   <use xlink:href=\"#quilt\" stroke-width=\"65.6\" transform=\"matrix(.03053 0 0 .03046 6.9 -3.2)\"></use>   <path fill=\"none\" stroke=\"currentColor\" stroke-width=\"70.4\" d=\"M442.5 234.8c0-7-5.6-12.5-12.5-12.5H234.7c-6.8 0-12.4 5.6-12.4 12.5V430c0 6.9 5.6 12.5 12.4 12.5H430c6.9 0 12.5-5.6 12.5-12.5V234.8Z\" transform=\"rotate(45 3.5 24) scale(.02843 .02835)\"></path> </svg>"
    }

    /** The box every one of these is drawn in. */
    private val GRID = 24f

    private fun DrawScope.strokePath(s: Stroke, path: androidx.compose.ui.graphics.Path, color: Color) {
        val style = StrokeStyle(width = s.width, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val m = s.matrix
        if (m == null) {
            drawPath(path, color, style = style)
        } else {
            // matrix(a b c d e f) with b and c zero: scale then place. Applied
            // innermost-first, so the path is scaled into the box and then moved,
            // and the stroke width rides the same scale -- which is how 23 becomes 2.
            translate(m[4], m[5]) {
                scale(m[0], m[3], pivot = Offset.Zero) {
                    drawPath(path, color, style = style)
                }
            }
        }
    }

    @Composable
    private fun VectorGlyph(strokes: List<Stroke>, size: Dp, color: Color) {
        val paths = remember(strokes) { strokes.map { PathParser().parsePathString(it.d).toPath() } }
        Canvas(Modifier.size(size)) {
            val k = this.size.minDimension / GRID
            scale(k, k, pivot = Offset.Zero) {
                strokes.forEachIndexed { i, s -> strokePath(s, paths[i], color) }
            }
        }
    }

    @Composable
    private fun RasterGlyph(svg: String, size: Dp, color: Color) {
        val bitmap = remember(svg, color) {
            SvgImageDecoder.renderSvg(
                svg.replace("currentColor", "#%06X".format(color.toArgb() and 0xFFFFFF)).toByteArray(Charsets.UTF_8),
            )
        }
        if (bitmap != null) {
            Image(bitmap.asComposeImageBitmap(), contentDescription = null, modifier = Modifier.size(size))
        } else {
            Box(Modifier.size(size).background(Color.Red.copy(alpha = 0.3f)))
        }
    }

    @Composable
    private fun Caption(text: String) = Text(
        text  = text,
        style = MaterialTheme.typography.labelSmall,
        color = NxTheme.colors.textSecondary,
    )

    private val sizes = listOf(16.dp, 24.dp, 48.dp, 96.dp)

    @Test
    fun `path against picture, across sizes`() {
        val out = Path.of("build/render", "loader-vector-vs-raster.png")
        Files.createDirectories(out.parent)
        val ink = Color(0xFFE8E8EC)
        val accent = Color(0xFFB794F6)
        val scene = ImageComposeScene(1160, 860, density = Density(2f)) {
            NxTheme(useDarkTheme = true) {
                Box(Modifier.fillMaxSize().background(NxTheme.colors.background)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Caption("вектор: fabric и neoforge на 16 / 24 / 48 / 96 dp")
                        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.Bottom) {
                            sizes.forEach { VectorGlyph(Paths.FABRIC, it, ink) }
                            sizes.forEach { VectorGlyph(Paths.NEOFORGE, it, ink) }
                        }
                        Caption("растр: те же, те же размеры")
                        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.Bottom) {
                            sizes.forEach { RasterGlyph(Svg.FABRIC, it, ink) }
                            sizes.forEach { RasterGlyph(Svg.NEOFORGE, it, ink) }
                        }
                        Caption("весь словарь вектором, 44 dp, цвет параметром")
                        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.Bottom) {
                            VectorGlyph(Paths.FABRIC, 44.dp, accent)
                            VectorGlyph(Paths.FORGE, 44.dp, accent)
                            VectorGlyph(Paths.NEOFORGE, 44.dp, accent)
                            VectorGlyph(Paths.QUILT, 44.dp, accent)
                            VectorGlyph(Paths.IRIS, 44.dp, accent)
                            VectorGlyph(Paths.OPTIFINE, 44.dp, accent)
                            VectorGlyph(Paths.MINECRAFT, 44.dp, accent)
                        }
                        Caption("в размере строки списка, 18 dp")
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Bottom) {
                            VectorGlyph(Paths.FABRIC, 18.dp, ink)
                            VectorGlyph(Paths.FORGE, 18.dp, ink)
                            VectorGlyph(Paths.NEOFORGE, 18.dp, ink)
                            VectorGlyph(Paths.QUILT, 18.dp, ink)
                            VectorGlyph(Paths.IRIS, 18.dp, ink)
                            VectorGlyph(Paths.OPTIFINE, 18.dp, ink)
                            VectorGlyph(Paths.MINECRAFT, 18.dp, ink)
                        }
                    }
                }
            }
        }
        val png = try {
            var t = 0L
            repeat(10) { scene.render(t).close(); t += 16_000_000L }
            scene.render(t).encodeToData(EncodedImageFormat.PNG) ?: error("PNG encode failed")
        } finally {
            scene.close()
        }
        Files.write(out, png.bytes)
        assertTrue(png.bytes.size > 2000, "the sheet did not draw")
    }
}

package hivens.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A mod loader's own logo, drawn as a path.
 *
 * Paths and not pictures. A raster is fixed at the size it was baked, so the same
 * mark beside a heading is a blur beside a chip's label, and its colour has to be
 * substituted into the source before the bitmap exists -- which means one bitmap
 * per colour per size, for a mark that is a few line segments. These are forms:
 * a 24 by 24 box, no fill, round caps and joins, and the ink is an argument.
 *
 * The forms are the projects' own. A launcher that lists what a mod runs on is
 * naming the loaders, and a swatch of colour standing in for a logo is a worse
 * answer to "is this the Fabric one" than the logo is.
 */

/**
 * One stroked path: its data, its width, and the matrix that places it.
 *
 * The matrix is not decoration. Fabric's mark is authored around (800, 760) and
 * brought into the box by a transform, with a stroke width of 23 that the same
 * transform takes down to two. Read the `d` alone and it draws a thousand units
 * off screen, which is exactly what the first attempt at this did: a path
 * attribute is not the whole glyph.
 */
private class LoaderStroke(val d: String, val width: Float, val matrix: FloatArray? = null)

/** The box every one of these is authored in. */
private const val GRID = 24f

private object LoaderPaths {
    val FABRIC = listOf(
        LoaderStroke(
            "m820 761-85.6-87.6c-4.6-4.7-10.4-9.6-25.9 1-19.9 13.6-8.4 21.9-5.2 25.4 8.2 9 84.1 89 97.2 104 " +
                "2.5 2.8-20.3-22.5-6.5-39.7 5.4-7 18-12 26-3 6.5 7.3 10.7 18-3.4 29.7-24.7 20.4-102 82.4-127 103" +
                "-12.5 10.3-28.5 2.3-35.8-6-7.5-8.9-30.6-34.6-51.3-58.2-5.5-6.3-4.1-19.6 2.3-25 35-30.3 91.9-73.8 111.9-90.8",
            23f,
            floatArrayOf(0.08671f, 0f, 0f, 0.0867f, -49.8f, -56f),
        ),
    )

    val FORGE = listOf(
        LoaderStroke("M2 7.5h8v-2h12v2s-7 3.4-7 6 3.1 3.1 3.1 3.1l.9 3.9H5l1-4.1s3.8.1 4-2.9c.2-2.7-6.5-.7-8-6Z", 2f),
    )

    val NEOFORGE = listOf(
        LoaderStroke("m12 19.2v2m0-2v2", 2f),
        LoaderStroke("m8.4 1.3c0.5 1.5 0.7 3 0.1 4.6-0.2 0.5-0.9 1.5-1.6 1.5m8.7-6.1c-0.5 1.5-0.7 3-0.1 4.6 0.2 0.6 0.9 1.5 1.6 1.5", 2f),
        LoaderStroke("m3.6 15.8h-1.7m18.5 0h1.7", 2f),
        LoaderStroke("m3.2 12.1h-1.7m19.3 0h1.8", 2f),
        LoaderStroke("m8.1 12.7v1.6m7.8-1.6v1.6", 2f),
        LoaderStroke("m10.8 18h1.2m0 1.2-1.2-1.2m2.4 0h-1.2m0 1.2 1.2-1.2", 2f),
        LoaderStroke(
            "m4 9.7c-0.5 1.2-0.8 2.4-0.8 3.7 0 3.1 2.9 6.3 5.3 8.2 0.9 0.7 2.2 1.1 3.4 1.1m0.1-17.8c-1.1 0-2.1 0.2-3.2 0.7" +
                "m11.2 4.1c0.5 1.2 0.8 2.4 0.8 3.7 0 3.1-2.9 6.3-5.3 8.2-0.9 0.7-2.2 1.1-3.4 1.1m-0.1-17.8c1.1 0 2.1 0.2 3.2 0.7",
            2f,
        ),
        LoaderStroke("m4 9.7c-0.2-1.8-0.3-3.7 0.5-5.5s2.2-2.6 3.9-3m11.6 8.5c0.2-1.9 0.3-3.7-0.5-5.5s-2.2-2.6-3.9-3", 2f),
        LoaderStroke("m12 21.2-2.4 0.4m2.4-0.4 2.4 0.4", 2f),
    )

    val QUILT = listOf(
        LoaderStroke(
            "M442.5 233.9c0-6.4-5.2-11.6-11.6-11.6h-197c-6.4 0-11.6 5.2-11.6 11.6v197c0 6.4 5.2 11.6 11.6 11.6h197" +
                "c6.4 0 11.6-5.2 11.6-11.7v-197Z",
            65.6f,
            floatArrayOf(0.03053f, 0f, 0f, 0.03046f, -3.2f, -3.2f),
        ),
        LoaderStroke(
            "M442.5 233.9c0-6.4-5.2-11.6-11.6-11.6h-197c-6.4 0-11.6 5.2-11.6 11.6v197c0 6.4 5.2 11.6 11.6 11.6h197" +
                "c6.4 0 11.6-5.2 11.6-11.7v-197Z",
            65.6f,
            floatArrayOf(0.03053f, 0f, 0f, 0.03046f, -3.2f, 7f),
        ),
        LoaderStroke(
            "M442.5 233.9c0-6.4-5.2-11.6-11.6-11.6h-197c-6.4 0-11.6 5.2-11.6 11.6v197c0 6.4 5.2 11.6 11.6 11.6h197" +
                "c6.4 0 11.6-5.2 11.6-11.7v-197Z",
            65.6f,
            floatArrayOf(0.03053f, 0f, 0f, 0.03046f, 6.9f, -3.2f),
        ),
    )

    val IRIS = listOf(
        LoaderStroke(
            "m22.59 12.013-3.01 3.126v4.405l.005.019-4.251-.005-2.994 3.115h-.003l-3.003-3.132H5.1l-.018.005" +
                ".005-4.424-2.994-3.116-.003-.023L5.1 8.858V4.452l-.005-.019 4.252.005 2.993-3.115h.003l3.003 3.132h4.234" +
                "l.018-.005-.005 4.425 2.994 3.115",
            2f,
        ),
        LoaderStroke(
            "m17.229 12.005-1.436 1.491v2.101l.003.009-2.028-.002-1.428 1.486h-.001l-1.433-1.494H8.887l-.008.002" +
                ".002-2.11-1.428-1.486-.001-.011L8.887 10.5V8.399l-.002-.009 2.027.002 1.428-1.485h.002l1.432 1.494h2.019" +
                "l.009-.003-.003 2.11 1.428 1.486",
            2f,
        ),
    )

    val OPTIFINE = listOf(
        LoaderStroke(
            "M10.985 9.205c0-1.38-1.121-2.5-2.5-2.5H7.156a2.5 2.5 0 0 0-2.5 2.5v5.59a2.5 2.5 0 0 0 2.5 2.5h1.329" +
                "c1.379 0 2.5-1.12 2.5-2.5v-5.59ZM14.793 17.295v-9.34a1.252 1.252 0 0 1 1.25-1.25h3.301M18.007 10.997h-3.214",
            2f,
        ),
    )

    val MINECRAFT = listOf(
        LoaderStroke(
            "M9.504 1.132a1 1 0 01.992 0l1.75 1a1 1 0 11-.992 1.736L10 3.152l-1.254.716a1 1 0 11-.992-1.736l1.75-1z" +
                "M5.618 4.504a1 1 0 01-.372 1.364L5.016 6l.23.132a1 1 0 11-.992 1.736L4 7.723V8a1 1 0 01-2 0V6a.996.996 0 " +
                "01.52-.878l1.734-.99a1 1 0 011.364.372zm8.764 0a1 1 0 011.364-.372l1.733.99A1.002 1.002 0 0118 6v2a1 1 0 " +
                "11-2 0v-.277l-.254.145a1 1 0 11-.992-1.736l.23-.132-.23-.132a1 1 0 01-.372-1.364zm-7 4a1 1 0 011.364-.372" +
                "L10 8.848l1.254-.716a1 1 0 11.992 1.736L11 10.58V12a1 1 0 11-2 0v-1.42l-1.246-.712a1 1 0 01-.372-1.364z" +
                "M3 11a1 1 0 011 1v1.42l1.246.712a1 1 0 11-.992 1.736l-1.75-1A1 1 0 012 14v-2a1 1 0 011-1zm14 0a1 1 0 011 1v2" +
                "a1 1 0 01-.504.868l-1.75 1a1 1 0 11-.992-1.736L16 13.42V12a1 1 0 011-1zm-9.618 5.504a1 1 0 011.364-.372" +
                "l.254.145V16a1 1 0 112 0v.277l.254-.145a1 1 0 11.992 1.736l-1.735.992a.995.995 0 01-1.022 0l-1.735-.992" +
                "a1 1 0 01-.372-1.364z",
            2f,
        ),
    )
}

/**
 * The strokes for a loader id, or null where this build has no mark for it.
 *
 * Null rather than a stand-in, so a caller can fall back to whatever it uses for
 * the unknown rather than being handed a wrong logo. `vanilla` and `minecraft`
 * are the same answer: the game with nothing loading into it.
 */
private fun loaderStrokes(loader: String): List<LoaderStroke>? = when (loader.lowercase()) {
    "fabric", "legacy-fabric" -> LoaderPaths.FABRIC
    "forge" -> LoaderPaths.FORGE
    "neoforge" -> LoaderPaths.NEOFORGE
    "quilt" -> LoaderPaths.QUILT
    "iris" -> LoaderPaths.IRIS
    "optifine" -> LoaderPaths.OPTIFINE
    "minecraft", "vanilla", "datapack" -> LoaderPaths.MINECRAFT
    else -> null
}

/** Whether this build can draw [loader]'s own mark. */
fun hasLoaderGlyph(loader: String): Boolean = loaderStrokes(loader) != null

/**
 * Draws [loader]'s logo at [size] in [tint], or nothing where there is no mark
 * for it. Ask [hasLoaderGlyph] first when the caller needs a fallback.
 */
@Composable
fun LoaderGlyph(
    loader: String,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 12.dp,
) {
    val strokes = remember(loader) { loaderStrokes(loader) } ?: return
    val paths = remember(strokes) { strokes.map { PathParser().parsePathString(it.d).toPath() } }
    Canvas(modifier.size(size)) {
        val k = this.size.minDimension / GRID
        scale(k, k, pivot = Offset.Zero) {
            strokes.forEachIndexed { i, stroke -> drawLoaderStroke(stroke, paths[i], tint) }
        }
    }
}

private fun DrawScope.drawLoaderStroke(stroke: LoaderStroke, path: Path, color: Color) {
    val style = Stroke(width = stroke.width, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val m = stroke.matrix
    if (m == null) {
        drawPath(path, color, style = style)
        return
    }
    // matrix(a b c d e f) with b and c zero: scale, then place. Applied
    // innermost-first, so the path is scaled into the box and then moved, and the
    // stroke width rides the same scale, which is how 23 becomes 2.
    translate(m[4], m[5]) {
        scale(m[0], m[3], pivot = Offset.Zero) {
            drawPath(path, color, style = style)
        }
    }
}

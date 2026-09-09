package hivens.ui.nx

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GeomSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.surface.NxSurface
import hivens.ui.surface.NxSurfaceLevel
import hivens.ui.theme.NxTheme
import hivens.ui.theme.Spacing
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image as SkImage
import java.io.File
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test

/**
 * The cassette, drawn from the object's real proportions.
 *
 * The first attempt drew two rings in a black box and nobody read it as a
 * cassette. What carries the reading is the silhouette and the label, not the
 * reels: a body of 100.5 x 63.8 mm (1.575:1), a label covering the top half, a
 * window slot about 0.58 of the width low on the face, five screws and the head
 * openings recessed into the bottom edge.
 *
 * Progress is the pack radii, following `r = sqrt(hub^2 + share * (full^2 - hub^2))`
 * because tape area, not radius, is proportional to length. The hubs sit far
 * enough in that a full pack is clipped by the slot top and bottom only, never
 * by its ends, so a full pack reads as a flattened disc and an empty one as a
 * small circle.
 */
enum class Spool(val radius: Float, val pale: Boolean, val label: String) {
    Stepped(0.14f, false, "1. ступень с зубьями"),
    Pale(0.14f, true, "2. белый пластик"),
    Ring(0.14f, false, "3. гладкое кольцо"),
    Bare(0.085f, false, "4. почти скрыта намоткой"),
    Holes(0.15f, true, "5. фланец с отверстиями"),
    Gear(0.155f, false, "6. крупная звёздочка"),
}

class CassetteProbe {

    private val title = "Sacrifice"
    private val artist = "Taka feat. めらみぽっぷ"
    private val album = "追憶のサクラメント"
    private val elapsed = "1:47"
    private val total = "4:55"

    private val cover: ImageBitmap by lazy {
        SkImage.makeFromEncoded(File("SAMPLE_PATH_REMOVED").readBytes()).toComposeImageBitmap()
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun sheet(name: String, wDp: Int, hDp: Int, body: @Composable () -> Unit) {
        val d = 3f
        val scene = ImageComposeScene((wDp * d).toInt(), (hDp * d).toInt(), density = Density(d)) {
            NxTheme(useDarkTheme = true) {
                Box(
                    Modifier.fillMaxSize().background(NxTheme.colors.background).padding(Spacing.s16),
                    contentAlignment = Alignment.Center,
                ) { body() }
            }
        }
        val img = scene.render()
        scene.close()
        File("build/render").mkdirs()
        img.encodeToData(EncodedImageFormat.PNG)?.bytes?.let { File("build/render/tape-$name.png").writeBytes(it) }
    }

    /** A recessed key. The plate is what makes a control read as a control. */
    @Composable
    private fun Key(
        icon: hivens.ui.icons.IconKey,
        plate: Color,
        tint: Color,
        width: Int = 34,
        height: Int = 30,
        glyph: Int = 18,
    ) {
        Box(
            Modifier.width(width.dp).height(height.dp).clip(MaterialTheme.shapes.small).background(plate),
            contentAlignment = Alignment.Center,
        ) {
            Symbol(icon, null, tint = tint, fill = 1f, weight = 500, modifier = Modifier.size(glyph.dp))
        }
    }

    @Composable
    private fun Controls(plate: Color, tint: Color, accent: Color, onAccent: Color, repeatOn: Boolean) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Key(
                NxIcon.Repeat,
                if (repeatOn) accent.copy(alpha = 0.22f) else plate,
                if (repeatOn) accent else tint,
            )
            Spacer(Modifier.width(Spacing.s8))
            Key(NxIcon.QueueMusic, plate, tint)
            Spacer(Modifier.weight(1f))
            Key(NxIcon.SkipPrevious, plate, tint)
            Spacer(Modifier.width(Spacing.s6))
            Key(NxIcon.Pause, accent, onAccent, width = 46, glyph = 20)
            Spacer(Modifier.width(Spacing.s6))
            Key(NxIcon.SkipNext, plate, tint)
            Spacer(Modifier.weight(1f))
            Key(NxIcon.MoreVert, plate, tint)
        }
    }

    @Composable
    private fun Glyph(icon: hivens.ui.icons.IconKey, size: Int, tint: Color) =
        Symbol(icon, null, tint = tint, fill = 1f, weight = 500, modifier = Modifier.size(size.dp))

    /**
     * The window, the tape path, the packs and the hubs.
     *
     * The hub is a spool, not a printed star: an outer flange, a step down to the
     * seat, then a bore with six drive teeth cut as narrow slots. The tape leaves
     * each pack on its tangent, drops to a guide roller in the corner and runs
     * along the bottom of the window, which is where a cassette actually carries
     * it and what stops the pair reading as two wheels on a wire.
     *
     * [scrubbing] raises the seek affordance on that bottom run, so dragging along
     * the tape winds the packs.
     */
    @Composable
    private fun Window(fraction: Float, scrubbing: Boolean, spool: Spool, modifier: Modifier) {
        val c = NxTheme.colors
        val tape = Color(0xFF3B2A20)
        val tapeRing = Color(0xFF553D30)
        val plastic = Color(0xFF7C746A)
        val plasticLit = Color(0xFF9A9186)
        val plasticDim = Color(0xFF5C554D)
        val bore = Color(0xFF0B0908)
        Canvas(modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xFF0B0908))) {
            val cy = size.height * 0.43f
            val hubR = size.height * spool.radius
            val fullR = size.height * 0.40f
            val lx = size.width * 0.22f
            val rx = size.width * 0.78f
            val runY = size.height * 0.86f
            val rollR = size.height * 0.07f
            val lrx = size.width * 0.06f
            val rrx = size.width * 0.94f

            fun radius(share: Float): Float {
                val s = share.coerceIn(0f, 1f)
                return sqrt(hubR * hubR + s * (fullR * fullR - hubR * hubR))
            }

            val rL = radius(1f - fraction)
            val rR = radius(fraction)

            /**
             * Where the tape leaves a pack for its roller. Of the two tangents, the
             * one on the roller's own side, so the span wraps the outside of the
             * pack instead of cutting across underneath it.
             */
            fun tangent(cx: Float, r: Float, px: Float, py: Float): Offset {
                val d = hypot(px - cx, py - cy)
                val base = atan2(py - cy, px - cx)
                val alpha = acos((r / d).coerceIn(-1f, 1f))
                val p1 = Offset(cx + cos(base + alpha) * r, cy + sin(base + alpha) * r)
                val p2 = Offset(cx + cos(base - alpha) * r, cy + sin(base - alpha) * r)
                val toward = px - cx
                return if ((p1.x - cx) * toward > (p2.x - cx) * toward) p1 else p2
            }

            drawLine(tape, tangent(lx, rL, lrx, runY), Offset(lrx, runY), strokeWidth = 3f)
            drawLine(tape, Offset(lrx, runY), Offset(rrx, runY), strokeWidth = 3f)
            drawLine(tape, Offset(rrx, runY), tangent(rx, rR, rrx, runY), strokeWidth = 3f)

            fun roller(cx: Float) {
                drawCircle(plastic, radius = rollR, center = Offset(cx, runY))
                drawCircle(bore, radius = rollR * 0.38f, center = Offset(cx, runY))
            }
            roller(lrx)
            roller(rrx)

            fun teethPath(cx: Float, at: Float, tip: Float, wideDeg: Float, narrowDeg: Float, count: Int): Path {
                val p = Path()
                for (k in 0 until count) {
                    val a = (k * 360f / count) * (Math.PI / 180f).toFloat()
                    val wide = (wideDeg * Math.PI / 180f).toFloat()
                    val narrow = (narrowDeg * Math.PI / 180f).toFloat()
                    p.moveTo(cx + cos(a - wide) * at, cy + sin(a - wide) * at)
                    p.lineTo(cx + cos(a + wide) * at, cy + sin(a + wide) * at)
                    p.lineTo(cx + cos(a + narrow) * tip, cy + sin(a + narrow) * tip)
                    p.lineTo(cx + cos(a - narrow) * tip, cy + sin(a - narrow) * tip)
                    p.close()
                }
                return p
            }

            fun hub(cx: Float) {
                val body = if (spool.pale) Color(0xFFD5CEC4) else plastic
                val lit = if (spool.pale) Color.White.copy(alpha = 0.7f) else plasticLit.copy(alpha = 0.55f)
                val dim = if (spool.pale) Color(0xFFB0A99F) else plasticDim
                when (spool) {
                    Spool.Bare -> {
                        drawCircle(body, radius = hubR, center = Offset(cx, cy))
                        drawCircle(bore, radius = hubR * 0.5f, center = Offset(cx, cy))
                    }
                    Spool.Ring -> {
                        drawCircle(body, radius = hubR, center = Offset(cx, cy))
                        drawCircle(lit, radius = hubR, center = Offset(cx, cy), style = Stroke(width = 1.2f))
                        drawCircle(dim, radius = hubR * 0.78f, center = Offset(cx, cy))
                        drawCircle(bore, radius = hubR * 0.42f, center = Offset(cx, cy))
                    }
                    Spool.Holes -> {
                        drawCircle(body, radius = hubR, center = Offset(cx, cy))
                        drawCircle(dim, radius = hubR, center = Offset(cx, cy), style = Stroke(width = 1f))
                        for (k in 0 until 6) {
                            val a = (k * 60f) * (Math.PI / 180f).toFloat()
                            drawCircle(
                                color = bore, radius = hubR * 0.17f,
                                center = Offset(cx + cos(a) * hubR * 0.62f, cy + sin(a) * hubR * 0.62f),
                            )
                        }
                        drawCircle(bore, radius = hubR * 0.30f, center = Offset(cx, cy))
                    }
                    Spool.Gear -> {
                        drawPath(teethPath(cx, hubR * 0.80f, hubR * 1.20f, 15f, 10f, 8), dim)
                        drawCircle(body, radius = hubR * 0.80f, center = Offset(cx, cy))
                        drawCircle(bore, radius = hubR * 0.44f, center = Offset(cx, cy))
                        drawPath(teethPath(cx, hubR * 0.44f, hubR * 0.15f, 22f, 15f, 6), body)
                    }
                    else -> {
                        drawCircle(body, radius = hubR, center = Offset(cx, cy))
                        drawCircle(lit, radius = hubR, center = Offset(cx, cy), style = Stroke(width = 1.2f))
                        drawCircle(dim, radius = hubR * 0.78f, center = Offset(cx, cy))
                        drawCircle(
                            color = Color.Black.copy(alpha = 0.35f), radius = hubR * 0.78f,
                            center = Offset(cx, cy), style = Stroke(width = 0.9f),
                        )
                        val boreR = hubR * 0.52f
                        drawCircle(bore, radius = boreR, center = Offset(cx, cy))
                        drawPath(teethPath(cx, boreR, boreR * 0.34f, 22f, 15f, 6), lit)
                    }
                }
            }

            fun pack(cx: Float, r: Float) {
                drawCircle(tape, radius = r, center = Offset(cx, cy))
                // The windings. A wound pack is not a flat disc, and the rings are
                // what stops it reading as one.
                val rings = 5
                for (k in 1..rings) {
                    val rr = hubR + (r - hubR) * (k / (rings + 1f))
                    drawCircle(
                        color = tapeRing.copy(alpha = 0.5f), radius = rr,
                        center = Offset(cx, cy), style = Stroke(width = 0.8f),
                    )
                }
                drawCircle(
                    color = Color.Black.copy(alpha = 0.45f), radius = r,
                    center = Offset(cx, cy), style = Stroke(width = 1f),
                )
                hub(cx)
            }
            pack(lx, rL)
            pack(rx, rR)

            if (scrubbing) {
                val hx = lrx + (rrx - lrx) * fraction.coerceIn(0f, 1f)
                drawLine(
                    color = c.primary, start = Offset(lrx, runY), end = Offset(hx, runY),
                    strokeWidth = 3f, cap = StrokeCap.Round,
                )
                drawCircle(c.primary, radius = 12f, center = Offset(hx, runY))
                drawCircle(bore, radius = 4f, center = Offset(hx, runY))
            }
        }
    }

    @Composable
    private fun Cassette(fraction: Float, scrubbing: Boolean, spool: Spool, inside: Boolean, repeatOn: Boolean, paper: Color, ink: Color, inkSoft: Color) {
        val c = NxTheme.colors
        val shell = lerp(c.surfaceContainerHigh, Color.Black, 0.45f)
        Box(Modifier.fillMaxWidth().height(if (inside) 220.dp else 200.dp)) {
            Canvas(Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp))) {
                val d = 3f
                drawRoundRect(shell, cornerRadius = CornerRadius(8f * d, 8f * d))

                // The head and capstan openings along the bottom edge. They look into
                // the dark inside of the shell, so they are recesses, not holes.
                if (!inside) {
                    val hollow = Color(0xFF0B0908)
                    fun recess(centerFraction: Float, widthFraction: Float, hDp: Float) {
                        val w = size.width * widthFraction
                        val h = hDp * d
                        drawRoundRect(
                            color = hollow,
                            topLeft = Offset(size.width * centerFraction - w / 2f, size.height - h),
                            size = GeomSize(w, h + 4f * d),
                            cornerRadius = CornerRadius(3f * d, 3f * d),
                        )
                    }
                    recess(0.50f, 0.21f, 11f)
                    recess(0.255f, 0.075f, 8f)
                    recess(0.745f, 0.075f, 8f)
                }

                val screw = Color.Black.copy(alpha = 0.5f)
                val inset = 9f * d
                val screws = mutableListOf(
                    Offset(inset, inset), Offset(size.width - inset, inset),
                    Offset(size.width / 2f, 107f * d),
                )
                if (!inside) {
                    screws += Offset(inset, size.height - 16f * d)
                    screws += Offset(size.width - inset, size.height - 16f * d)
                }
                screws.forEach { drawCircle(screw, radius = 2.6f * d, center = it) }
            }

            // The label: a paper sticker over the top of the shell, the album art
            // as its square image and the writing area beside it.
            Row(
                Modifier.fillMaxWidth().height(93.dp)
                    .padding(start = 11.dp, end = 11.dp, top = 11.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(paper)
                    .padding(6.dp),
            ) {
                Image(
                    cover, null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxHeight().aspectRatio(1f).clip(RoundedCornerShape(1.dp)),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
                    Text(
                        title, style = MaterialTheme.typography.titleMedium, color = ink,
                        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        artist, style = MaterialTheme.typography.labelMedium, color = inkSoft,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.width(28.dp).height(2.dp).background(c.primary))
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            album, style = MaterialTheme.typography.labelSmall, color = inkSoft,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, false),
                        )
                        Spacer(Modifier.width(Spacing.s8))
                        Text(
                            "$elapsed / $total", style = MaterialTheme.typography.labelSmall,
                            color = inkSoft, maxLines = 1,
                        )
                    }
                }
            }

            if (inside) {
                Box(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .padding(start = 11.dp, end = 11.dp, bottom = 11.dp),
                ) {
                    Controls(
                        plate = Color.Black.copy(alpha = 0.42f),
                        tint = Color(0xFFC9C2B8),
                        accent = c.primary,
                        onAccent = c.onPrimary,
                        repeatOn = repeatOn,
                    )
                }
            }

            Window(
                fraction, scrubbing, spool,
                Modifier.align(Alignment.TopCenter)
                    .fillMaxWidth(0.62f)
                    .padding(top = 110.dp)
                    .height(60.dp),
            )
        }
    }

    @Composable
    private fun ConceptTape(fraction: Float, scrubbing: Boolean, spool: Spool, inside: Boolean, repeatOn: Boolean, paper: Color, ink: Color, inkSoft: Color) {
        val c = NxTheme.colors
        NxSurface(
            NxSurfaceLevel.Floating, Modifier.width(340.dp),
            shape = MaterialTheme.shapes.medium,
        ) {
            Column(Modifier.fillMaxWidth().padding(Spacing.s12)) {
                Cassette(fraction, scrubbing, spool, inside, repeatOn, paper, ink, inkSoft)
                if (!inside) {
                    Spacer(Modifier.height(Spacing.s12))
                    Controls(
                        plate = Color.White.copy(alpha = 0.07f),
                        tint = c.textSecondary,
                        accent = c.primary,
                        onAccent = c.onPrimary,
                        repeatOn = repeatOn,
                    )
                }
            }
        }
    }

    @Composable
    private fun SpoolSheet() {
        val c = NxTheme.colors
        val shell = lerp(c.surfaceContainerHigh, Color.Black, 0.45f)
        Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(Spacing.s12)) {
            Spool.entries.chunked(2).forEach { pair ->
                Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(Spacing.s16)) {
                    pair.forEach { spool ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                Modifier.width(216.dp).clip(RoundedCornerShape(8.dp)).background(shell)
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Window(107f / 295f, false, spool, Modifier.width(196.dp).height(60.dp))
                            }
                            Spacer(Modifier.height(Spacing.s6))
                            Text(
                                spool.label, style = MaterialTheme.typography.labelMedium,
                                color = c.textSecondary,
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun probe() {
        val paper = Color(0xFFEDE6DA)
        val ink = Color(0xFF1A1714)
        val inkSoft = Color(0xFF5A5248)
        val f = 107f / 295f
        sheet("deck", 380, 320) { ConceptTape(f, false, Spool.Stepped, false, true, paper, ink, inkSoft) }
        sheet("deck-scrub", 380, 320) { ConceptTape(f, true, Spool.Stepped, false, true, paper, ink, inkSoft) }
        sheet("inshell", 380, 320) { ConceptTape(f, false, Spool.Stepped, true, true, paper, ink, inkSoft) }
        sheet("inshell-scrub", 380, 320) { ConceptTape(f, true, Spool.Stepped, true, false, paper, ink, inkSoft) }
        sheet("spools", 500, 372) { SpoolSheet() }
    }
}

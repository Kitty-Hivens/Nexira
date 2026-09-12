package hivens.ui.nx

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GeomSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
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
import hivens.ui.theme.seedFromImage
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test

/**
 * Concepts built on one real track rather than on a synthetic square.
 *
 * The material is Sacrifice by Taka feat. めらみぽっぷ: its embedded 800x800 cover,
 * its tags, and an RMS envelope computed from the audio itself. RMS and not peaks,
 * because the peak envelope of this master is a solid block (mean 0.856 of full
 * scale) and draws as a rectangle: a loudness-war master has no peak shape left.
 */
class RealTrackConceptsProbe {

    private val title = "Sacrifice"
    private val artist = "Taka feat. めらみぽっぷ"
    private val album = "追憶のサクラメント"
    private val elapsed = "1:47"
    private val total = "4:55"
    private val fraction = 107f / 295f

    /** RMS per bin, normalised to the track's own maximum. 110 bars over 4:55. */
    private val env = floatArrayOf(
        0.3074f, 0.2906f, 0.2545f, 0.3151f, 0.288f, 0.309f, 0.2916f, 0.8261f, 0.7492f, 0.7732f, 0.8829f, 0.8107f,
        0.7619f, 0.7538f, 0.8191f, 0.5064f, 0.4953f, 0.503f, 0.4552f, 0.4057f, 0.4555f, 0.5866f, 0.61f, 0.6622f,
        0.6274f, 0.6649f, 0.6585f, 0.6361f, 0.6358f, 0.8114f, 0.8916f, 0.8381f, 0.9074f, 0.8441f, 0.8849f, 0.9371f,
        0.9047f, 0.8458f, 0.8462f, 0.8632f, 0.9284f, 0.8134f, 0.8194f, 0.9559f, 0.8936f, 0.7405f, 0.7552f, 0.8003f,
        0.9378f, 0.7237f, 0.7913f, 0.8358f, 0.8084f, 0.6191f, 0.7217f, 0.709f, 0.6515f, 0.7391f, 0.7428f, 0.8181f,
        0.8756f, 0.8793f, 0.9043f, 0.8344f, 0.8328f, 0.8612f, 0.9097f, 0.8438f, 0.8585f, 0.8488f, 0.9482f, 0.8645f,
        0.8505f, 0.8839f, 0.9602f, 0.8595f, 0.8064f, 0.8157f, 0.805f, 0.806f, 0.7843f, 0.8525f, 0.4856f, 0.4789f,
        0.4843f, 0.6308f, 0.4957f, 0.5331f, 0.5107f, 0.8973f, 0.9338f, 0.8522f, 1.0f, 0.8913f, 0.8625f, 0.9589f,
        0.9512f, 0.7177f, 0.7846f, 0.8013f, 0.8378f, 0.7652f, 0.8201f, 0.7508f, 0.8375f, 0.4595f, 0.5431f, 0.4926f,
        0.2398f, 0.0f,
    )

    /**
     * The reference cover, or null when the sample is not checked out. The probe
     * is a drawing of three shapes, not a test, so a missing sample degrades to
     * the shapes without it rather than failing: this file was once deleted for
     * being "only a probe", the concepts it draws had to be recovered from a
     * transcript, and a probe that refuses to run is a probe that gets deleted
     * again.
     */
    private val cover: ImageBitmap? by lazy { ProbeSample.cover() }

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
        img.encodeToData(EncodedImageFormat.PNG)?.bytes?.let { File("build/render/real-$name.png").writeBytes(it) }
    }

    @Composable
    private fun Glyph(icon: hivens.ui.icons.IconKey, size: Int, tint: Color) =
        Symbol(icon, null, tint = tint, fill = 1f, weight = 500, modifier = Modifier.size(size.dp))

    /** The envelope as mirrored bars. Played bars take [played], the rest [rest]. */
    @Composable
    private fun Envelope(played: Color, rest: Color, modifier: Modifier) {
        Canvas(modifier) {
            val n = env.size
            val slot = size.width / n
            val bar = slot * 0.62f
            val cut = (n * fraction)
            for (i in 0 until n) {
                val h = (env[i] * size.height).coerceAtLeast(size.height * 0.06f)
                val x = i * slot + (slot - bar) / 2f
                drawRoundRect(
                    color = if (i < cut) played else rest,
                    topLeft = Offset(x, (size.height - h) / 2f),
                    size = GeomSize(bar, h),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(bar / 2f, bar / 2f),
                )
            }
        }
    }

    // ── E. The sound is the picture ────────────────────────────────────────
    // No cover here even though this file has one, because the envelope is the
    // one image every file can produce. It is the measure at the same time: the
    // played bars are inked, so nothing else in the card has to show position.
    @Composable
    private fun ConceptWave() {
        val c = NxTheme.colors
        NxSurface(NxSurfaceLevel.Floating, Modifier.width(340.dp), shape = MaterialTheme.shapes.medium) {
            Row(
                Modifier.fillMaxWidth().height(104.dp).padding(Spacing.s14),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(46.dp).clip(CircleShape).background(c.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) { Glyph(NxIcon.Pause, 22, c.onPrimaryContainer) }
                Spacer(Modifier.width(Spacing.s14))
                Column(Modifier.weight(1f)) {
                    Text(
                        title, style = MaterialTheme.typography.bodyLarge, color = c.textPrimary,
                        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(Spacing.s6))
                    Envelope(c.primary, c.textSecondary.copy(alpha = 0.28f), Modifier.fillMaxWidth().height(34.dp))
                    Spacer(Modifier.height(Spacing.s4))
                    Row(Modifier.fillMaxWidth()) {
                        Text(elapsed, style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
                        Spacer(Modifier.weight(1f))
                        Text(total, style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
                    }
                }
            }
        }
    }

    // ── F. The cover rules the card, but never carries the text ────────────
    // The album's own lettering lives at the bottom of this artwork, so a caption
    // laid over it would fight it. The picture keeps its own square and the card
    // borrows only its colour, seeded through the same quantizer the wallpaper
    // palette uses.
    @Composable
    private fun ConceptSeeded() {
        val c = NxTheme.colors
        val seed = cover?.let { seedFromImage(it) }
        val body = if (seed != null) lerp(c.surfaceContainer, Color(seed), 0.24f) else c.surfaceContainer
        NxSurface(
            NxSurfaceLevel.Floating, Modifier.width(340.dp),
            shape = MaterialTheme.shapes.medium, fillColor = body,
        ) {
            Row(Modifier.fillMaxWidth().height(112.dp).padding(Spacing.s12), verticalAlignment = Alignment.CenterVertically) {
                val art = cover
                if (art != null) {
                    Image(
                        art, null, contentScale = ContentScale.Crop,
                        modifier = Modifier.size(88.dp).clip(MaterialTheme.shapes.small),
                    )
                } else {
                    Box(
                        Modifier.size(88.dp).clip(MaterialTheme.shapes.small)
                            .background(c.primary.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center,
                    ) { Glyph(NxIcon.MusicNote, 30, c.primary) }
                }
                Spacer(Modifier.width(Spacing.s14))
                Column(Modifier.weight(1f)) {
                    Text(
                        title, style = MaterialTheme.typography.titleMedium, color = c.textPrimary,
                        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        artist, style = MaterialTheme.typography.bodySmall, color = c.textSecondary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        album, style = MaterialTheme.typography.labelSmall,
                        color = c.textSecondary.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(Spacing.s8))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Glyph(NxIcon.SkipPrevious, 18, c.textSecondary)
                        Spacer(Modifier.width(Spacing.s10))
                        Glyph(NxIcon.Pause, 22, c.textPrimary)
                        Spacer(Modifier.width(Spacing.s10))
                        Glyph(NxIcon.SkipNext, 18, c.textSecondary)
                        Spacer(Modifier.width(Spacing.s12))
                        Box(Modifier.weight(1f).height(3.dp).clip(CircleShape)) {
                            Box(Modifier.fillMaxSize().background(c.textSecondary.copy(alpha = 0.24f)))
                            Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(c.primary))
                        }
                    }
                }
            }
        }
    }

    // ── G. The record ──────────────────────────────────────────────────────
    // The cover as a disc, the envelope radiating around it, the played arc lit.
    // One square cell, no text at all: what it is lives in the tooltip, which the
    // library now anchors under the control instead of chasing the pointer.
    @Composable
    private fun ConceptRecord() {
        val c = NxTheme.colors
        Box(Modifier.size(168.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val inner = size.minDimension * 0.30f
                val room = size.minDimension * 0.16f
                val n = env.size
                val cut = n * fraction
                for (i in 0 until n) {
                    val a = (-90f + i * 360f / n) * (Math.PI / 180f).toFloat()
                    val len = inner + 3f + env[i] * room
                    val sx = cx + cos(a) * (inner + 3f)
                    val sy = cy + sin(a) * (inner + 3f)
                    val ex = cx + cos(a) * len
                    val ey = cy + sin(a) * len
                    drawLine(
                        color = if (i < cut) c.primary else c.textSecondary.copy(alpha = 0.30f),
                        start = Offset(sx, sy), end = Offset(ex, ey),
                        strokeWidth = 2.6f, cap = StrokeCap.Round,
                    )
                }
            }
            val art = cover
            if (art != null) {
                Image(
                    art, null, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(96.dp).clip(CircleShape),
                )
            } else {
                Box(
                    Modifier.size(96.dp).clip(CircleShape).background(c.primary.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center,
                ) { Glyph(NxIcon.MusicNote, 34, c.primary) }
            }
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.46f)),
                contentAlignment = Alignment.Center,
            ) { Glyph(NxIcon.Pause, 22, Color.White) }
        }
    }


    @Test
    fun probe() {
        sheet("e-wave", 380, 144) { ConceptWave() }
        sheet("f-seeded", 380, 152) { ConceptSeeded() }
        sheet("g-record", 208, 208) { ConceptRecord() }
    }
}

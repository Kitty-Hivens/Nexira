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
import androidx.compose.ui.graphics.Brush
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
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.math.sqrt
import kotlin.test.Test

/** Three more shapes on the same real track. */
class MoreConceptsProbe {

    private val title = "Sacrifice"
    private val artist = "Taka feat. めらみぽっぷ"
    private val elapsed = "1:47"
    private val total = "4:55"
    private val fraction = 107f / 295f

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
     * The reference art, and the same art reduced to 14 px and blown back up,
     * which is how concept K gets its ground: a real blur is not available in the
     * software renderer these sheets are drawn with, and a downsample reads the
     * same at this size.
     */
    private val cover: ImageBitmap by lazy { ProbeSample.cover() }
    private val coverBlur: ImageBitmap by lazy { ProbeSample.coverBlur() }

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
        img.encodeToData(EncodedImageFormat.PNG)?.bytes?.let { File("build/render/more-$name.png").writeBytes(it) }
    }

    @Composable
    private fun Glyph(icon: hivens.ui.icons.IconKey, size: Int, tint: Color) =
        Symbol(icon, null, tint = tint, fill = 1f, weight = 500, modifier = Modifier.size(size.dp))

    // ── H. The cassette ────────────────────────────────────────────────────
    // Progress is the tape itself: the left reel unwinds and the right one fills,
    // so the measure is a physical fact about the object rather than a bar added
    // to it. Wound radius goes as the square root of the share, because tape is an
    // area on a spool and a linear radius would run out visibly early.
    @Composable
    private fun ConceptCassette() {
        val c = NxTheme.colors
        NxSurface(NxSurfaceLevel.Floating, Modifier.width(340.dp), shape = MaterialTheme.shapes.medium) {
            Column(Modifier.fillMaxWidth().padding(Spacing.s14)) {
                Box(
                    Modifier.fillMaxWidth().height(92.dp).clip(RoundedCornerShape(10.dp))
                        .background(lerp(c.surfaceContainer, Color.Black, 0.35f)),
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        val hub = size.height * 0.10f
                        val full = size.height * 0.34f
                        val cy = size.height / 2f
                        val lx = size.width * 0.27f
                        val rx = size.width * 0.73f
                        // Tape between the reels, the part you can see through the window.
                        drawRect(
                            color = c.textSecondary.copy(alpha = 0.35f),
                            topLeft = Offset(lx, cy - 2f),
                            size = GeomSize(rx - lx, 4f),
                        )
                        fun reel(cx: Float, share: Float, lit: Boolean) {
                            val r = hub + (full - hub) * sqrt(share.coerceIn(0f, 1f))
                            drawCircle(
                                color = if (lit) c.primary.copy(alpha = 0.85f) else c.textSecondary.copy(alpha = 0.45f),
                                radius = r, center = Offset(cx, cy),
                                style = Stroke(width = (r - hub).coerceAtLeast(1.5f)),
                            )
                            drawCircle(color = c.surfaceContainer, radius = hub, center = Offset(cx, cy))
                            drawCircle(
                                color = c.textSecondary.copy(alpha = 0.5f), radius = hub,
                                center = Offset(cx, cy), style = Stroke(width = 1.5f),
                            )
                        }
                        reel(lx, 1f - fraction, lit = false)
                        reel(rx, fraction, lit = true)
                    }
                    // The label sits across the shell, the way a cassette's does.
                    Column(Modifier.align(Alignment.BottomStart).padding(Spacing.s10)) {
                        Text(
                            title, style = MaterialTheme.typography.bodyMedium, color = c.textPrimary,
                            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "$elapsed / $total", style = MaterialTheme.typography.labelSmall,
                            color = c.textSecondary, maxLines = 1,
                        )
                    }
                }
                Spacer(Modifier.height(Spacing.s10))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        artist, style = MaterialTheme.typography.bodySmall, color = c.textSecondary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    Glyph(NxIcon.SkipPrevious, 18, c.textSecondary)
                    Spacer(Modifier.width(Spacing.s10))
                    Glyph(NxIcon.Pause, 22, c.textPrimary)
                    Spacer(Modifier.width(Spacing.s10))
                    Glyph(NxIcon.SkipNext, 18, c.textSecondary)
                }
            }
        }
    }

    // ── I. The column ──────────────────────────────────────────────────────
    // Portrait, for the right rail, where every widget we have is landscape and
    // none fits. The envelope runs down the side as the measure, so the long axis
    // carries the time instead of wasting it.
    @Composable
    private fun ConceptColumn() {
        val c = NxTheme.colors
        NxSurface(NxSurfaceLevel.Floating, Modifier.width(148.dp), shape = MaterialTheme.shapes.medium) {
            Column(Modifier.fillMaxWidth().padding(Spacing.s12)) {
                Image(
                    cover, null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(124.dp).clip(MaterialTheme.shapes.small),
                )
                Spacer(Modifier.height(Spacing.s10))
                Row(Modifier.fillMaxWidth().height(96.dp)) {
                    Canvas(Modifier.width(22.dp).fillMaxHeight()) {
                        val n = env.size
                        val slot = size.height / n
                        val cut = n * fraction
                        for (i in 0 until n) {
                            val w = (env[i] * size.width).coerceAtLeast(size.width * 0.08f)
                            drawRoundRect(
                                color = if (i < cut) c.primary else c.textSecondary.copy(alpha = 0.26f),
                                topLeft = Offset(0f, i * slot),
                                size = GeomSize(w, slot * 0.66f),
                                cornerRadius = CornerRadius(1.5f, 1.5f),
                            )
                        }
                    }
                    Spacer(Modifier.width(Spacing.s10))
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        Text(
                            title, style = MaterialTheme.typography.bodyMedium, color = c.textPrimary,
                            fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            artist, style = MaterialTheme.typography.labelSmall, color = c.textSecondary,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.weight(1f))
                        Text("$elapsed / $total", style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
                    }
                }
                Spacer(Modifier.height(Spacing.s10))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Glyph(NxIcon.SkipPrevious, 18, c.textSecondary)
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).background(c.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) { Glyph(NxIcon.Pause, 20, c.onPrimaryContainer) }
                    Glyph(NxIcon.SkipNext, 18, c.textSecondary)
                }
            }
        }
    }

    // ── K. The cover is the ground, the sound is drawn on it ───────────────
    // The picture blurred behind everything and the envelope laid over it, so the
    // card is made of the two things the file actually carries. Blur is faked here
    // by a downsample, because the off-screen renderer has none.
    @Composable
    private fun ConceptGround() {
        val c = NxTheme.colors
        Box(Modifier.width(340.dp).height(112.dp).clip(MaterialTheme.shapes.medium)) {
            Image(coverBlur, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.46f), Color.Black.copy(alpha = 0.72f)),
                    ),
                ),
            )
            Column(Modifier.fillMaxSize().padding(Spacing.s14)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            title, style = MaterialTheme.typography.titleMedium, color = Color.White,
                            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            artist, style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Glyph(NxIcon.SkipPrevious, 18, Color.White.copy(alpha = 0.8f))
                    Spacer(Modifier.width(Spacing.s10))
                    Glyph(NxIcon.Pause, 24, Color.White)
                    Spacer(Modifier.width(Spacing.s10))
                    Glyph(NxIcon.SkipNext, 18, Color.White.copy(alpha = 0.8f))
                }
                Spacer(Modifier.weight(1f))
                Canvas(Modifier.fillMaxWidth().height(26.dp)) {
                    val n = env.size
                    val slot = size.width / n
                    val bar = slot * 0.58f
                    val cut = n * fraction
                    for (i in 0 until n) {
                        val h = (env[i] * size.height).coerceAtLeast(size.height * 0.08f)
                        drawRoundRect(
                            color = if (i < cut) c.primary else Color.White.copy(alpha = 0.34f),
                            topLeft = Offset(i * slot + (slot - bar) / 2f, size.height - h),
                            size = GeomSize(bar, h),
                            cornerRadius = CornerRadius(bar / 2f, bar / 2f),
                        )
                    }
                }
            }
        }
    }


    @Test
    fun probe() {
        sheet("h-cassette", 380, 190) { ConceptCassette() }
        sheet("i-column", 188, 380) { ConceptColumn() }
        sheet("k-ground", 380, 152) { ConceptGround() }
    }
}

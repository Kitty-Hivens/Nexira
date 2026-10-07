package hivens.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hivens.ui.effects.pixelArtBackground
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.nx.NxButton
import hivens.ui.nx.NxButtonStyle
import hivens.ui.nx.NxMetaChip
import hivens.ui.nx.NxMetaChipTone
import hivens.ui.nx.PlayButton
import hivens.ui.nx.PlayGround
import hivens.ui.settle
import hivens.ui.surface.NxSurface
import hivens.ui.surface.SurfaceKind
import hivens.ui.theme.NxColor
import hivens.ui.theme.NxInk
import hivens.ui.theme.NxTheme
import hivens.ui.theme.decorativePair
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Home, five ways, drawn from scratch to be chosen between.
 *
 * Each concept is a different answer to what the screen is, not a restyle of the
 * shipped one, which is four full-width bars stacked: a greeting, a banner, a
 * shelf, a row of tiles. Fed the packs a real library has, and most of them have no
 * art, so every concept has to look like something when the art is the pixel fill.
 * That is the constraint the shipped banner fails: the fill was made for a tile and
 * reads as noise at 1500 by 260.
 *
 * - A, column: one pack is the subject, in a tall card on the left with its facts
 *   set large. The rest are a grid that fills the right. Asymmetry gives the eye a
 *   first place to land.
 * - B, type: no picture for the pack being continued. Its name is set at display
 *   size and its facts are numerals, so a pack with no art is not a pack with no
 *   presence. The library under it is a list, like a track list, not tiles.
 * - C, spines: every pack is a full-height strip, side by side, and the one being
 *   continued is opened out. The fill suits a tall narrow strip in a way it never
 *   suits a wide banner.
 * - D, dock: the upper half belongs to the wallpaper and the time, the packs sit
 *   in a row along the bottom like a dock, the continued one twice as wide.
 * - E, bento: a grid whose cells are sized by what matters, the continued pack the
 *   largest, the most played next, the news in a cell of its own.
 *
 * Kept in the tree: this file is the design, the PNGs are its output.
 */
class HomeConceptsProbe {

    private data class Pack(
        val name: String,
        val mc: String,
        val loader: String,
        val played: String,
        val hours: Int,
        val update: String? = null,
    )

    private val packs = listOf(
        Pack("RPG-Revanced", "1.21.1", "NeoForge", "2 дн назад", 38),
        Pack("Create", "1.21.1", "NeoForge", "13 дн назад", 21, update = "26.3-2.0"),
        Pack("Industrial", "1.12.2", "Cleanroom", "давно", 64),
        Pack("Nevermine", "1.12.2", "Cleanroom", "давно", 12),
        Pack("TechnoMagic", "1.12.2", "Cleanroom", "давно", 9),
        Pack("RPG", "1.12.2", "Cleanroom", "давно", 5),
        Pack("Fabulously Optimized", "26.2", "Fabric", "3 нед назад", 1),
    )
    private val current get() = packs.first()
    private val rest get() = packs.drop(1)

    // ── shared pieces ─────────────────────────────────────────────────────

    /** The pack's own fill, the way every surface that has no art draws it. */
    @Composable
    private fun Fill(pack: Pack, modifier: Modifier = Modifier, scrim: Float = 0.7f, content: @Composable BoxScope.() -> Unit = {}) {
        val (a, b) = decorativePair(pack.name)
        Box(modifier.pixelArtBackground(pack.name, a, b)) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.05f), 1f to Color.Black.copy(alpha = scrim)),
                ),
            )
            content()
        }
    }

    @Composable
    private fun Caption(pack: Pack, big: Boolean = false) {
        Column {
            Text(
                pack.name,
                style = if (big) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleSmall,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${pack.mc} · ${pack.loader} · ${pack.played}",
                style = if (big) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.75f),
                maxLines = 1,
            )
        }
    }

    /** A number set large with its unit set small under it: a fact read at a glance. */
    @Composable
    private fun Numeral(value: String, label: String, onMedia: Boolean = false) {
        Column {
            Text(
                value,
                fontSize = 34.sp,
                lineHeight = 36.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (onMedia) Color.White else NxInk.main,
            )
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = if (onMedia) Color.White.copy(alpha = 0.7f) else NxInk.quiet,
            )
        }
    }

    @Composable
    private fun Play(modifier: Modifier = Modifier) =
        PlayButton(label = "Играть", onClick = {}, ground = PlayGround.Media, modifier = modifier)

    private fun Modifier.verticalText() = layout { measurable, constraints ->
        val placeable = measurable.measure(Constraints(maxWidth = constraints.maxHeight, maxHeight = constraints.maxWidth))
        layout(placeable.height, placeable.width) {
            placeable.placeWithLayer(
                x = -(placeable.width / 2 - placeable.height / 2),
                y = placeable.width / 2 - placeable.height / 2,
            ) { rotationZ = -90f }
        }
    }

    // ── A: column ─────────────────────────────────────────────────────────

    @Composable
    private fun ConceptColumn() {
        Row(Modifier.fillMaxSize().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.width(440.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Fill(current, Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(16.dp)), scrim = 0.85f) {
                    Column(Modifier.align(Alignment.BottomStart).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        Text("Продолжить", style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.7f))
                        Text(current.name, fontSize = 40.sp, lineHeight = 42.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                            Numeral("${current.hours} ч", "в игре", onMedia = true)
                            Numeral("2 дн", "назад", onMedia = true)
                            Numeral(current.mc, current.loader, onMedia = true)
                        }
                        Play(Modifier.fillMaxWidth())
                    }
                }
                NxSurface(SurfaceKind.Panel, Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Symbol(NxIcon.Update, null, tint = NxColor.lead())
                        Column(Modifier.weight(1f)) {
                            Text("Create", color = NxInk.main, fontWeight = FontWeight.Medium)
                            Text("Ждёт сборка 26.3-2.0", style = MaterialTheme.typography.bodySmall, color = NxInk.quiet)
                        }
                        Symbol(NxIcon.ChevronRight, null, tint = NxInk.quiet)
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Твои сборки", style = MaterialTheme.typography.titleMedium, color = NxInk.main, fontWeight = FontWeight.SemiBold)
                rest.chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { p ->
                            Fill(p, Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(12.dp))) {
                                Box(Modifier.align(Alignment.BottomStart).padding(14.dp)) { Caption(p) }
                                p.update?.let { NxMetaChip("Обновление", Modifier.align(Alignment.TopStart).padding(10.dp), tone = NxMetaChipTone.OnMedia, dot = NxColor.lead()) }
                            }
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }

    // ── B: type ───────────────────────────────────────────────────────────

    @Composable
    private fun ConceptType() {
        Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 40.dp)) {
            Text("Продолжить", style = MaterialTheme.typography.labelLarge, color = NxInk.quiet)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Fill(current, Modifier.size(width = 10.dp, height = 72.dp).clip(RoundedCornerShape(5.dp)), scrim = 0f)
                Text(current.name, fontSize = 72.sp, lineHeight = 76.sp, fontWeight = FontWeight.Bold, color = NxInk.main)
            }
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                Numeral("${current.hours} ч", "в игре")
                Numeral("2 дн", "назад")
                Numeral(current.mc, current.loader)
                Spacer(Modifier.weight(1f))
                NxButton("Играть", onClick = {}, icon = NxIcon.PlayArrow, minHeight = 52.dp)
            }
            Spacer(Modifier.height(48.dp))
            Text("Твои сборки", style = MaterialTheme.typography.titleMedium, color = NxInk.main, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            rest.forEach { p ->
                Row(
                    Modifier.fillMaxWidth().height(52.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Fill(p, Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)), scrim = 0f)
                    Text(p.name, Modifier.width(260.dp), color = NxInk.main, fontWeight = FontWeight.Medium, maxLines = 1)
                    Text("${p.mc} · ${p.loader}", Modifier.width(200.dp), color = NxInk.quiet, style = MaterialTheme.typography.bodySmall)
                    Text(p.played, Modifier.width(140.dp), color = NxInk.quiet, style = MaterialTheme.typography.bodySmall)
                    Box(Modifier.weight(1f)) {
                        p.update?.let { NxMetaChip("Ждёт сборка $it", tone = NxMetaChipTone.Surface, dot = NxColor.lead()) }
                    }
                    Text("${p.hours} ч", color = NxInk.quiet, style = MaterialTheme.typography.bodyMedium)
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(NxInk.line))
            }
        }
    }

    // ── C: spines ─────────────────────────────────────────────────────────

    @Composable
    private fun ConceptSpines() {
        Row(Modifier.fillMaxSize().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Fill(current, Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(16.dp)), scrim = 0.85f) {
                Column(Modifier.align(Alignment.BottomStart).padding(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Продолжить", style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.7f))
                    Text(current.name, fontSize = 48.sp, lineHeight = 50.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                        Numeral("${current.hours} ч", "в игре", onMedia = true)
                        Numeral("2 дн", "назад", onMedia = true)
                        Numeral(current.mc, current.loader, onMedia = true)
                    }
                    Play()
                }
            }
            rest.forEach { p ->
                Fill(p, Modifier.width(104.dp).fillMaxHeight().clip(RoundedCornerShape(14.dp)), scrim = 0.8f) {
                    Column(
                        Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            p.name,
                            Modifier.verticalText(),
                            fontSize = 22.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                            maxLines = 1,
                        )
                        Text("${p.hours} ч", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.75f))
                    }
                    p.update?.let {
                        Box(Modifier.align(Alignment.TopCenter).padding(top = 14.dp).size(10.dp).clip(RoundedCornerShape(5.dp)).background(NxColor.lead()))
                    }
                }
            }
        }
    }

    // ── D: dock ───────────────────────────────────────────────────────────

    @Composable
    private fun ConceptDock() {
        Box(Modifier.fillMaxSize().padding(32.dp)) {
            Column(Modifier.align(Alignment.TopStart)) {
                Text("22:49", fontSize = 96.sp, lineHeight = 96.sp, fontWeight = FontWeight.Light, color = NxInk.main)
                Text("четверг, 2 октября", style = MaterialTheme.typography.titleLarge, color = NxInk.quiet)
                Spacer(Modifier.height(16.dp))
                NxMetaChip("Create: ждёт сборка 26.3-2.0", tone = NxMetaChipTone.Surface, dot = NxColor.lead())
            }
            Row(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().height(300.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Fill(current, Modifier.weight(2f).fillMaxHeight().clip(RoundedCornerShape(16.dp)), scrim = 0.85f) {
                    Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(22.dp), verticalAlignment = Alignment.Bottom) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Продолжить", style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.7f))
                            Caption(current, big = true)
                        }
                        Play()
                    }
                }
                rest.take(4).forEach { p ->
                    Fill(p, Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(16.dp))) {
                        Box(Modifier.align(Alignment.BottomStart).padding(16.dp)) { Caption(p) }
                    }
                }
            }
        }
    }

    // ── E: bento ──────────────────────────────────────────────────────────

    @Composable
    private fun ConceptBento() {
        val gap = 14.dp
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(gap)) {
            Row(Modifier.fillMaxWidth().weight(2f), horizontalArrangement = Arrangement.spacedBy(gap)) {
                Fill(current, Modifier.weight(2f).fillMaxHeight().clip(RoundedCornerShape(18.dp)), scrim = 0.85f) {
                    Column(Modifier.align(Alignment.BottomStart).padding(26.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("Продолжить", style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.7f))
                        Text(current.name, fontSize = 44.sp, lineHeight = 46.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.Bottom) {
                            Numeral("${current.hours} ч", "в игре", onMedia = true)
                            Numeral("2 дн", "назад", onMedia = true)
                            Spacer(Modifier.width(12.dp))
                            Play()
                        }
                    }
                }
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(gap)) {
                    val most = rest.maxBy { it.hours }
                    Fill(most, Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(18.dp))) {
                        Column(Modifier.align(Alignment.BottomStart).padding(18.dp)) {
                            Text("Больше всего часов", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f))
                            Caption(most)
                        }
                        Text("${most.hours} ч", Modifier.align(Alignment.TopEnd).padding(16.dp), fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    }
                    NxSurface(SurfaceKind.Panel, Modifier.fillMaxWidth().weight(1f), shape = RoundedCornerShape(18.dp)) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Что нового", style = MaterialTheme.typography.titleSmall, color = NxInk.main, fontWeight = FontWeight.SemiBold)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Symbol(NxIcon.Update, null, tint = NxColor.lead())
                                Column {
                                    Text("Create", color = NxInk.main, fontWeight = FontWeight.Medium)
                                    Text("Ждёт сборка 26.3-2.0", style = MaterialTheme.typography.bodySmall, color = NxInk.quiet)
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Symbol(NxIcon.NewReleases, null, tint = NxColor.lead())
                                Text("Nexira 2.5.0", color = NxInk.main, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(gap)) {
                rest.filter { it != rest.maxBy { r -> r.hours } }.take(5).forEach { p ->
                    Fill(p, Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(18.dp))) {
                        Box(Modifier.align(Alignment.BottomStart).padding(16.dp)) { Caption(p) }
                    }
                }
            }
        }
    }

    // ── rendering ─────────────────────────────────────────────────────────

    /** The centre pane of a 1920 by 1080 window with both rails open, at scale one. */
    private fun sheet(name: String, content: @Composable () -> Unit) {
        val out = Path.of("build/render/home", name)
        Files.createDirectories(out.parent)
        val scene = ImageComposeScene(1545, 935, density = Density(1f)) {
            NxTheme(dark = true) {
                Box(Modifier.fillMaxSize().background(NxColor.page)) { content() }
            }
        }
        val png = try {
            val t = scene.settle(frames = 12)
            scene.render(t).encodeToData(EncodedImageFormat.PNG) ?: error("PNG encode failed")
        } finally {
            scene.close()
        }
        Files.write(out, png.bytes)
        assertTrue(png.bytes.size > 3000, "$name did not draw")
    }

    @Test fun `A column`() = sheet("home-a-column.png") { ConceptColumn() }

    @Test fun `B type`() = sheet("home-b-type.png") { ConceptType() }

    @Test fun `C spines`() = sheet("home-c-spines.png") { ConceptSpines() }

    @Test fun `D dock`() = sheet("home-d-dock.png") { ConceptDock() }

    @Test fun `E bento`() = sheet("home-e-bento.png") { ConceptBento() }
}

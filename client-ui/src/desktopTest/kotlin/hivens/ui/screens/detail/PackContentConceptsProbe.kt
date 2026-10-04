package hivens.ui.screens.detail

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import hivens.ui.effects.pixelArtBackground
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.nx.NxButton
import hivens.ui.nx.NxButtonStyle
import hivens.ui.nx.NxIconButton
import hivens.ui.nx.NxMetaChip
import hivens.ui.nx.NxMetaChipTone
import hivens.ui.nx.NxSwitch
import hivens.ui.settle
import hivens.ui.surface.NxSurface
import hivens.ui.surface.SurfaceKind
import hivens.ui.theme.NxColor
import hivens.ui.theme.NxInk
import hivens.ui.theme.NxTheme
import hivens.ui.theme.decorativeColor
import hivens.ui.theme.decorativePair
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue
import org.jetbrains.skia.Image as SkImage

/**
 * The pack page's content area, three ways, over a wallpaper.
 *
 * The toolbar was put on a panel of its own so its outlined buttons stop vanishing
 * into a bright picture, and the result reads oddly over one: a box of controls,
 * then a stack of separate cards with the picture showing in every gap between
 * them. Each concept is a different answer to what the area is, drawn over the
 * same wallpaper so the comparison is about how it sits on a picture.
 *
 * - A, as shipped: the toolbar panel, then one card per row.
 * - B, one plane: the toolbar and the list on a single panel, rows parted by
 *   hairlines the way Home's pack list is, nothing showing through between them.
 * - C, tiles: the same plane, the projects as a grid. A view a player could pick,
 *   which is where letting the list be shown in more than one way would lead.
 *
 * The wallpaper is read from the path in build/render/wallpaper.path when that file
 * exists, so a local run can be drawn over a real one without the picture or its
 * path entering the tree. Without it a generated field of colour stands in.
 *
 * Kept in the tree: this file is the design, the PNGs are its output.
 */
class PackContentConceptsProbe {

    private data class Mod(val name: String, val version: String, val locked: Boolean, val on: Boolean = true)

    private val mods = listOf(
        Mod("+Fugue", "0.17.3", locked = true),
        Mod("AE2Stuff", "0.7.0.4", locked = true),
        Mod("AmbientSounds", "3.1.5", locked = false),
        Mod("AppleSkin", "1.0.14", locked = false),
        Mod("AppliedEnergistics2", "rv6-stable-7", locked = true),
        Mod("ArmorHUD", "1.3.0", locked = false),
        Mod("AstralSorcery", "1.10.27", locked = true),
        Mod("AutoRegLib", "1.3-32", locked = true),
        Mod("Baubles", "1.5.2", locked = true),
        Mod("BdLib", "1.14.4.1", locked = true),
        Mod("better-advancements", "0.1.0.77", locked = false),
        Mod("BetterChat", "1.2", locked = false, on = false),
        Mod("Born In A Barn", "1.8", locked = false, on = false),
        Mod("Chameleon", "4.1.3", locked = true),
        Mod("Chisel", "1.0.2.45", locked = true),
        Mod("CodeChickenLib", "3.2.3.358", locked = true),
    )

    // ── the window around it ──────────────────────────────────────────────

    @Composable
    private fun Shell(wallpaper: ImageBitmap?, center: @Composable BoxScope.() -> Unit) {
        Box(Modifier.fillMaxSize().background(NxColor.page)) {
            Column(Modifier.fillMaxSize()) {
                NxSurface(SurfaceKind.Chrome, Modifier.fillMaxWidth().height(44.dp), RoundedCornerShape(0.dp)) {
                    Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Symbol(NxIcon.DarkMode, null, tint = NxColor.lead(), size = 20.dp)
                        Spacer(Modifier.width(16.dp))
                        Text("Главная", style = MaterialTheme.typography.bodyMedium, color = NxInk.quiet)
                        Symbol(NxIcon.ChevronRight, null, tint = NxInk.quiet, size = 16.dp)
                        Text("TechnoMagic", style = MaterialTheme.typography.bodyMedium, color = NxInk.main, fontWeight = FontWeight.SemiBold)
                    }
                }
                Row(Modifier.fillMaxWidth().weight(1f)) {
                    NxSurface(SurfaceKind.Chrome, Modifier.width(65.dp).fillMaxHeight(), RoundedCornerShape(0.dp)) {
                        Column(
                            Modifier.fillMaxSize().padding(top = 14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(22.dp),
                        ) {
                            listOf(NxIcon.Home, NxIcon.Star, NxIcon.Search, NxIcon.Person, NxIcon.Palette, NxIcon.Settings, NxIcon.Info)
                                .forEachIndexed { i, icon ->
                                    Symbol(icon, null, tint = if (i == 1) NxColor.lead() else NxInk.quiet, size = 22.dp)
                                }
                        }
                    }
                    // The wallpaper shows in the centre pane, as it does in the shell.
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        Wallpaper(wallpaper)
                        center()
                    }
                    NxSurface(
                        SurfaceKind.Panel,
                        Modifier.width(265.dp).fillMaxHeight().padding(start = 4.dp, top = 4.dp, bottom = 4.dp),
                        MaterialTheme.shapes.large,
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Text("Новости проекта", style = MaterialTheme.typography.labelLarge, color = NxInk.main, fontWeight = FontWeight.SemiBold)
                            repeat(4) {
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)).background(NxColor.wash(NxInk.quiet, 0.2f)))
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text("Итоги конкурса голосований", style = MaterialTheme.typography.bodySmall, color = NxInk.main, maxLines = 1)
                                        Text("30 сен 2026", style = MaterialTheme.typography.labelSmall, color = NxColor.lead(text = true))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun BoxScope.Wallpaper(bitmap: ImageBitmap?) {
        if (bitmap != null) {
            Image(bitmap, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
        } else {
            Box(
                Modifier.matchParentSize().background(
                    Brush.linearGradient(listOf(Color(0xFFF6E9F2), Color(0xFFB9C7F2), Color(0xFFF2C9A8), Color(0xFF8AA6D8))),
                ),
            )
        }
    }

    /** The pack header and the page tabs as they ship, the part above the content. */
    @Composable
    private fun ColumnScope.PageTop() {
        val (a, b) = decorativePair("TechnoMagic")
        Box(Modifier.fillMaxWidth().height(196.dp).clip(RoundedCornerShape(14.dp)).pixelArtBackground("TechnoMagic", a, b)) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)))
            Row(
                Modifier.align(Alignment.BottomStart).padding(24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(Modifier.size(72.dp).clip(RoundedCornerShape(14.dp)).background(decorativeColor("TechnoMagic")), contentAlignment = Alignment.Center) {
                    Text("T", style = MaterialTheme.typography.titleLarge, color = Color.White, fontWeight = FontWeight.Bold)
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("TechnoMagic", style = MaterialTheme.typography.headlineMedium, color = Color.White, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("Mirror", "Cleanroom 1.12.2", "v0.1.30", "2h").forEach { NxMetaChip(it, tone = NxMetaChipTone.OnMedia) }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Содержимое", "Файлы", "Миры", "Логи").forEachIndexed { i, t ->
                val fill = if (i == 0) NxColor.lead() else NxColor.wash(NxInk.quiet, 0.12f)
                Box(Modifier.clip(MaterialTheme.shapes.small).background(fill).padding(horizontal = 12.dp, vertical = 7.dp)) {
                    Text(t, style = MaterialTheme.typography.labelLarge, color = if (i == 0) NxColor.on(fill) else NxInk.quiet)
                }
            }
        }
    }

    // ── shared pieces of the content area ─────────────────────────────────

    @Composable
    private fun Search() {
        NxSurface(SurfaceKind.Field, Modifier.fillMaxWidth(), MaterialTheme.shapes.large) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Symbol(NxIcon.Search, null, tint = NxInk.quiet, size = 18.dp)
                Spacer(Modifier.width(10.dp))
                Text("Поиск в содержимом...", style = MaterialTheme.typography.bodyMedium, color = NxInk.quiet)
            }
        }
    }

    @Composable
    private fun Filters() {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Всё", "Моды", "Ресурсы", "Шейдеры").forEachIndexed { i, t ->
                val fill = if (i == 0) NxColor.lead() else NxColor.wash(NxInk.quiet, 0.10f)
                Box(Modifier.clip(MaterialTheme.shapes.small).background(fill).padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text(t, style = MaterialTheme.typography.labelLarge, color = if (i == 0) NxColor.on(fill) else NxInk.quiet)
                }
            }
            Spacer(Modifier.weight(1f))
            NxIconButton(icon = NxIcon.FilterAlt, contentDescription = null, onClick = {}, tint = NxInk.quiet)
            NxButton("Добавить файлы", onClick = {}, style = NxButtonStyle.Secondary, icon = NxIcon.Add, compact = true)
        }
    }

    @Composable
    private fun Mark(mod: Mod, size: Int = 30) {
        val tile = decorativeColor(mod.name)
        Box(Modifier.size(size.dp).clip(RoundedCornerShape((size / 4).dp)).background(tile), contentAlignment = Alignment.Center) {
            Text(mod.name.trimStart('+').take(1).uppercase(), style = MaterialTheme.typography.labelMedium, color = Color.White, fontWeight = FontWeight.Bold)
        }
    }

    @Composable
    private fun RowBody(mod: Mod) {
        Row(
            Modifier.fillMaxWidth().height(50.dp).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Mark(mod)
            Column(Modifier.weight(1f)) {
                Text(mod.name, style = MaterialTheme.typography.bodyMedium, color = if (mod.on) NxInk.main else NxInk.quiet, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(mod.version, style = MaterialTheme.typography.labelSmall, color = NxInk.quiet, maxLines = 1)
            }
            if (!mod.locked) NxSwitch(mod.on, {})
            Symbol(NxIcon.MoreVert, null, tint = NxInk.quiet, size = 20.dp)
        }
    }

    // ── A: as shipped ─────────────────────────────────────────────────────

    @Composable
    private fun ConceptShipped() {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PageTop()
            NxSurface(SurfaceKind.Panel, Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Search()
                    Filters()
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                mods.forEach { m -> NxSurface(SurfaceKind.Card, Modifier.fillMaxWidth(), MaterialTheme.shapes.small) { RowBody(m) } }
            }
        }
    }

    // ── B: one plane ──────────────────────────────────────────────────────

    @Composable
    private fun ConceptPlane() {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PageTop()
            NxSurface(SurfaceKind.Panel, Modifier.fillMaxWidth().weight(1f)) {
                Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Search()
                    Spacer(Modifier.height(10.dp))
                    Filters()
                    Spacer(Modifier.height(8.dp))
                    mods.forEachIndexed { i, m ->
                        if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(NxInk.line))
                        RowBody(m)
                    }
                }
            }
        }
    }

    // ── C: tiles ──────────────────────────────────────────────────────────

    @Composable
    private fun ConceptTiles() {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PageTop()
            NxSurface(SurfaceKind.Panel, Modifier.fillMaxWidth().weight(1f)) {
                Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Search()
                    Filters()
                    mods.chunked(6).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { m ->
                                NxSurface(SurfaceKind.Card, Modifier.weight(1f).height(118.dp), MaterialTheme.shapes.medium) {
                                    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Mark(m, size = 40)
                                            Spacer(Modifier.weight(1f))
                                            if (!m.locked) NxSwitch(m.on, {}) else Symbol(NxIcon.Lock, null, tint = NxInk.quiet, size = 16.dp)
                                        }
                                        Text(m.name, style = MaterialTheme.typography.bodyMedium, color = NxInk.main, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(m.version, style = MaterialTheme.typography.labelSmall, color = NxInk.quiet, maxLines = 1)
                                    }
                                }
                            }
                            repeat(6 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }

    // ── rendering ─────────────────────────────────────────────────────────

    private fun wallpaper(): ImageBitmap? {
        val pointer = Path.of("build/render/wallpaper.path")
        if (!Files.isRegularFile(pointer)) return null
        val file = Path.of(Files.readString(pointer).trim())
        if (!Files.isRegularFile(file)) return null
        return SkImage.makeFromEncoded(Files.readAllBytes(file)).toComposeImageBitmap()
    }

    private fun sheet(name: String, width: Int, height: Int, content: @Composable (ImageBitmap?) -> Unit) {
        val out = Path.of("build/render/pack-content", "$name-${width}x$height.png")
        Files.createDirectories(out.parent)
        val wall = wallpaper()
        val scene = ImageComposeScene(width, height, density = Density(1f)) {
            NxTheme(dark = true) { content(wall) }
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

    private fun both(name: String, content: @Composable (ImageBitmap?) -> Unit) {
        sheet(name, 1920, 1080, content)
        sheet(name, 2560, 1440, content)
    }

    @Test fun `A shipped`() = both("a-shipped") { w -> Shell(w) { ConceptShipped() } }

    @Test fun `B plane`() = both("b-plane") { w -> Shell(w) { ConceptPlane() } }

    @Test fun `C tiles`() = both("c-tiles") { w -> Shell(w) { ConceptTiles() } }
}

package hivens.ui.screens.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import hivens.ui.effects.pixelArtBackground
import hivens.ui.icons.IconKey
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.nx.NxButton
import hivens.ui.nx.NxButtonStyle
import hivens.ui.nx.NxChoiceChip
import hivens.ui.nx.NxIconButton
import hivens.ui.nx.NxMetaChip
import hivens.ui.nx.NxMetaChipTone
import hivens.ui.nx.NxSlider
import hivens.ui.nx.NxSwitch
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
 * Where a pack's settings live, three ways, drawn to be chosen between.
 *
 * The shipped window is mounted inside the centre pane, so its scrim stops at the
 * rails and the title bar, it is a fixed share of that pane, and its rows mix two
 * type scales on one level. Each concept here is a different answer to where the
 * settings sit, with the same reworked contents inside, so the comparison is about
 * the container and not about the rows.
 *
 * - A, window: a dialog over the whole window, scrimmed from edge to edge, sized
 *   by a ceiling rather than a share, with the section rail inside it.
 * - B, sheet: a panel that slides in from the right edge over the news rail and
 *   leaves the pack page readable beside it, sections as tabs along its top.
 * - C, page: no overlay at all. Settings are the last tab of the pack page and
 *   use the page's width, with an in-page index of the sections on the left.
 *
 * The contents are the Launch section rebuilt: one row shape for every setting
 * (label, a line of explanation, the control at the far edge), group titles in the
 * ink rather than the accent, memory as Auto or a slider in gigabytes, Java as what
 * the launcher manages rather than a placeholder path.
 *
 * Drawn at 1920 by 1080 and 2560 by 1440 at scale one, whole window with both rails.
 * Kept in the tree: this file is the design, the PNGs are its output.
 */
class PackSettingsConceptsProbe {

    private val packName = "Deep Caverns"

    // ── the window around it ──────────────────────────────────────────────

    @Composable
    private fun Shell(center: @Composable BoxScope.() -> Unit, overlay: @Composable BoxScope.() -> Unit = {}) {
        Box(Modifier.fillMaxSize().background(NxColor.page)) {
            Column(Modifier.fillMaxSize()) {
                NxSurface(SurfaceKind.Chrome, Modifier.fillMaxWidth().height(44.dp), RoundedCornerShape(0.dp)) {
                    Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Symbol(NxIcon.DarkMode, null, tint = NxColor.lead(), size = 20.dp)
                        Spacer(Modifier.width(16.dp))
                        Text("Главная", style = MaterialTheme.typography.bodyMedium, color = NxInk.quiet)
                        Symbol(NxIcon.ChevronRight, null, tint = NxInk.quiet, size = 16.dp)
                        Text(packName, style = MaterialTheme.typography.bodyMedium, color = NxInk.main, fontWeight = FontWeight.SemiBold)
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
                    Box(Modifier.weight(1f).fillMaxHeight()) { center() }
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
            overlay()
        }
    }

    /** The pack's own fill, the way every surface that has no art draws it. */
    @Composable
    private fun Fill(modifier: Modifier, scrim: Float) {
        val (a, b) = decorativePair(packName)
        Box(modifier.pixelArtBackground(packName, a, b)) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(0f to Color.Black.copy(alpha = scrim * 0.4f), 1f to Color.Black.copy(alpha = scrim)),
                ),
            )
        }
    }

    @Composable
    private fun Mark(size: Int) {
        Box(Modifier.size(size.dp).clip(RoundedCornerShape((size / 4).dp))) { Fill(Modifier.fillMaxSize(), scrim = 0f) }
    }

    /** The pack page as it stands, under an overlay: what a scrim has to cover. */
    @Composable
    private fun PageBehind(tabs: List<String> = listOf("Контент", "Файлы", "Миры", "Логи"), selectedTab: Int = 0) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.fillMaxWidth().height(196.dp).clip(RoundedCornerShape(14.dp))) {
                Fill(Modifier.fillMaxSize(), scrim = 0.75f)
                Row(
                    Modifier.align(Alignment.BottomStart).padding(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Mark(72)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(packName, style = MaterialTheme.typography.headlineSmall, color = Color.White, fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("Local", "Forge 1.20.1", "47.3.0", "34 ч").forEach { NxMetaChip(it, tone = NxMetaChipTone.OnMedia) }
                        }
                    }
                }
            }
            PageTabs(tabs, selectedTab)
            ContentRows()
        }
    }

    @Composable
    private fun PageTabs(tabs: List<String>, selected: Int) {
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            tabs.forEachIndexed { i, t ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        t,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (i == selected) NxInk.main else NxInk.quiet,
                        fontWeight = if (i == selected) FontWeight.SemiBold else FontWeight.Medium,
                    )
                    Spacer(Modifier.height(6.dp))
                    Box(
                        Modifier.width(28.dp).height(3.dp).clip(RoundedCornerShape(2.dp))
                            .background(if (i == selected) NxColor.lead() else Color.Transparent),
                    )
                }
            }
        }
    }

    @Composable
    private fun ContentRows() {
        val mods = listOf(
            "Create" to "0.5.1.f", "Just Enough Items" to "15.2.0.27", "Sodium" to "0.5.8", "Iris Shaders" to "1.6.11",
            "Farmer's Delight" to "1.2.4", "Waystones" to "14.1.3", "JourneyMap" to "5.9.18", "AppleSkin" to "2.5.1",
            "GeckoLib" to "4.4.4", "Architectury API" to "9.2.14", "Terralith" to "2.5.0", "Supplementaries" to "2.8.10",
        )
        Column {
            mods.forEach { (name, version) ->
                Row(
                    Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Box(Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(NxColor.wash(NxInk.quiet, 0.2f)))
                    Text(name, Modifier.weight(1f), color = NxInk.main, fontWeight = FontWeight.Medium)
                    Text(version, Modifier.width(140.dp), style = MaterialTheme.typography.bodySmall, color = NxInk.quiet)
                    NxSwitch(checked = true, onCheckedChange = {})
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(NxInk.line))
            }
        }
    }

    // ── the reworked contents, shared by every concept ────────────────────

    /** A group of settings: its title in the ink, the rows on one plane, a hairline between them. */
    @Composable
    private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = NxInk.main, fontWeight = FontWeight.SemiBold)
            NxSurface(SurfaceKind.Panel, Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp), content = content)
            }
        }
    }

    @Composable
    private fun Rule() = Box(Modifier.fillMaxWidth().height(1.dp).background(NxInk.line))

    /** The one row shape: what it is, why it matters, and the control at the far edge. */
    @Composable
    private fun Setting(title: String, detail: String? = null, trailing: @Composable () -> Unit = {}) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, color = NxInk.main, fontWeight = FontWeight.Medium)
                if (detail != null) {
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = NxInk.quiet, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            trailing()
        }
    }

    @Composable
    private fun LaunchSettings() {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(28.dp)) {
            Group("Память") {
                Setting("Объём памяти", "Авто подбирает под систему и под эту сборку. Сейчас это 10 ГБ.") {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        NxChoiceChip("Авто", selected = false) {}
                        NxChoiceChip("Своё", selected = true) {}
                    }
                }
                Rule()
                Column(Modifier.padding(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    NxSlider(
                        label = "Выделено",
                        value = 8f,
                        range = 1f..17f,
                        valueText = "8 ГБ",
                        onValueChange = {},
                        maxTrackWidth = 2000.dp,
                    )
                    Text(
                        "В системе 23,2 ГБ. Больше 17 ГБ не даём: игре нужна память и вне кучи.",
                        style = MaterialTheme.typography.bodySmall,
                        color = NxInk.quiet,
                    )
                }
            }
            Group("Окружение") {
                Setting("Java", "Управляется лаунчером: Java 17, подобрана под 1.20.1.") {
                    NxButton("Указать свою", onClick = {}, style = NxButtonStyle.Secondary, compact = true)
                }
                Rule()
                Setting("Аргументы JVM", "По умолчанию. Свои флаги стоит трогать, только если знаете зачем.") {
                    NxButton("Изменить", onClick = {}, style = NxButtonStyle.Secondary, compact = true)
                }
            }
            Group("Окно игры") {
                Setting("Свой размер окна", "Иначе игра откроется в размере, который запомнила сама.") {
                    NxSwitch(checked = false, onCheckedChange = {})
                }
                Rule()
                Setting("Полный экран") { NxSwitch(checked = false, onCheckedChange = {}) }
                Rule()
                Setting("Экран загрузки Forge", "Окно с прогрессом, пока игра стартует.") {
                    NxSwitch(checked = true, onCheckedChange = {})
                }
            }
        }
    }

    private data class Section(val icon: IconKey, val label: String)

    private val sections = listOf(
        Section(NxIcon.Tune, "Общее"),
        Section(NxIcon.Memory, "Запуск"),
        Section(NxIcon.Layers, "Контент"),
        Section(NxIcon.Storage, "Данные"),
    )

    @Composable
    private fun SectionRail(modifier: Modifier = Modifier) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            sections.forEachIndexed { i, sec ->
                val selected = i == 1
                Row(
                    Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
                        .background(if (selected) NxColor.wash(NxColor.lead(), 0.16f) else Color.Transparent)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Symbol(sec.icon, null, tint = if (selected) NxColor.lead() else NxInk.quiet, size = 20.dp)
                    Text(sec.label, color = if (selected) NxInk.main else NxInk.quiet, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
                }
            }
        }
    }

    @Composable
    private fun Header(onSheet: Boolean = false) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Mark(40)
            Column(Modifier.weight(1f)) {
                Text("Настройки сборки", style = MaterialTheme.typography.titleMedium, color = NxInk.main, fontWeight = FontWeight.SemiBold)
                Text("$packName · Forge 47.3.0 на 1.20.1", style = MaterialTheme.typography.bodySmall, color = NxInk.quiet)
            }
            if (onSheet) NxIconButton(icon = NxIcon.OpenInFull, contentDescription = null, onClick = {})
            NxIconButton(icon = NxIcon.Close, contentDescription = null, onClick = {})
        }
    }

    // ── A: window ─────────────────────────────────────────────────────────

    @Composable
    private fun ConceptWindow() {
        Shell(center = { PageBehind() }) {
            BoxWithConstraints(Modifier.fillMaxSize().background(NxColor.page.copy(alpha = 0.72f)), contentAlignment = Alignment.Center) {
                // A share of the window up to a ceiling: on a large display the window
                // stops growing rather than stretching its rows across the screen.
                val w = minOf(maxWidth * 0.86f, 1040.dp)
                val h = minOf(maxHeight * 0.88f, 820.dp)
                NxSurface(SurfaceKind.Dialog, Modifier.size(w, h), MaterialTheme.shapes.large) {
                    Column(Modifier.fillMaxSize()) {
                        Header()
                        Rule()
                        Row(Modifier.weight(1f).fillMaxWidth()) {
                            SectionRail(Modifier.width(220.dp).fillMaxHeight().padding(16.dp))
                            Box(Modifier.width(1.dp).fillMaxHeight().background(NxInk.line))
                            Column(Modifier.weight(1f).padding(horizontal = 32.dp, vertical = 24.dp)) { LaunchSettings() }
                        }
                    }
                }
            }
        }
    }

    // ── B: sheet ──────────────────────────────────────────────────────────

    @Composable
    private fun ConceptSheet() {
        Shell(center = { PageBehind() }) {
            Box(Modifier.fillMaxSize().padding(top = 44.dp).background(NxColor.page.copy(alpha = 0.45f)))
            NxSurface(
                SurfaceKind.Dialog,
                Modifier.align(Alignment.TopEnd).padding(top = 52.dp, end = 8.dp, bottom = 8.dp).width(640.dp).fillMaxHeight(),
                MaterialTheme.shapes.large,
            ) {
                Column(Modifier.fillMaxSize()) {
                    Header(onSheet = true)
                    Row(Modifier.padding(horizontal = 24.dp)) { PageTabs(sections.map { it.label }, selected = 1) }
                    Spacer(Modifier.height(8.dp))
                    Rule()
                    Column(Modifier.weight(1f).padding(horizontal = 24.dp, vertical = 20.dp)) { LaunchSettings() }
                }
            }
        }
    }

    // ── C: page ───────────────────────────────────────────────────────────

    @Composable
    private fun ConceptPage() {
        Shell(center = {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // The hero folds to a band once the page is about the pack rather than its art.
                Box(Modifier.fillMaxWidth().height(88.dp).clip(RoundedCornerShape(14.dp))) {
                    Fill(Modifier.fillMaxSize(), scrim = 0.8f)
                    Row(
                        Modifier.align(Alignment.CenterStart).padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Mark(52)
                        Column {
                            Text(packName, style = MaterialTheme.typography.titleLarge, color = Color.White, fontWeight = FontWeight.Bold)
                            Text("Forge 47.3.0 на 1.20.1 · 34 ч", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.75f))
                        }
                    }
                }
                PageTabs(listOf("Контент", "Файлы", "Миры", "Логи", "Настройки"), selected = 4)
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                    SectionRail(Modifier.width(200.dp).padding(top = 4.dp))
                    Column(Modifier.widthIn(max = 880.dp).weight(1f, fill = false)) { LaunchSettings() }
                }
            }
        })
    }

    // ── rendering ─────────────────────────────────────────────────────────

    private fun sheet(name: String, width: Int, height: Int, content: @Composable () -> Unit) {
        val out = Path.of("build/render/pack-settings", "$name-${width}x$height.png")
        Files.createDirectories(out.parent)
        val scene = ImageComposeScene(width, height, density = Density(1f)) {
            NxTheme(dark = true) { content() }
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

    private fun both(name: String, content: @Composable () -> Unit) {
        sheet(name, 1920, 1080, content)
        sheet(name, 2560, 1440, content)
    }

    @Test fun `A window`() = both("a-window") { ConceptWindow() }

    @Test fun `B sheet`() = both("b-sheet") { ConceptSheet() }

    @Test fun `C page`() = both("c-page") { ConceptPage() }
}

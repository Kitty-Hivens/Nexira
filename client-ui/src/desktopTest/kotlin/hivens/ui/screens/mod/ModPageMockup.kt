package hivens.ui.screens.mod

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import hivens.ui.components.formatBuildTimestamp
import hivens.ui.icons.IconKey
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.nx.NxButton
import hivens.ui.nx.NxButtonStyle
import hivens.ui.nx.NxKebabButton
import hivens.ui.nx.NxMenuItem
import hivens.ui.nx.NxMetaChip
import hivens.ui.nx.NxMetaChipTone
import hivens.ui.render.MarkdownHtml
import hivens.ui.surface.NxSurface
import hivens.ui.surface.NxSurfaceLevel
import hivens.ui.theme.NxTheme
import hivens.ui.theme.decorativeColor
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A mock-up of the mod page, drawn in Compose out of the real design system and
 * fed real Modrinth payloads.
 *
 * It is deliberately NOT built in a drawing tool or in HTML. Half the reason for
 * the exercise is the second question -- which pieces nx-ui is missing -- and a
 * mock-up made outside the design system cannot answer that. Anything below that
 * had to be hand-rolled here instead of called is a gap, and the gaps found on
 * the way are listed at the bottom of this file.
 *
 * The payloads are fixtures under `resources/mockup`, captured 2026-09-18, and
 * chosen to break the layout in three different ways rather than to flatter it.
 * Body images stay blank: Coil has no network here, which is fine because the
 * body renderer is already in production and the page AROUND it is the subject.
 */
class ModPageMockup {

    // ── fixtures ────────────────────────────────────────────────────────────

    @Serializable
    data class MockProject(
        val id: String,
        val slug: String,
        val title: String,
        val description: String = "",
        val body: String = "",
        val categories: List<String> = emptyList(),
        val downloads: Long = 0,
        val followers: Long = 0,
        val loaders: List<String> = emptyList(),
        @SerialName("game_versions") val gameVersions: List<String> = emptyList(),
        @SerialName("client_side") val clientSide: String = "unknown",
        @SerialName("server_side") val serverSide: String = "unknown",
        @SerialName("issues_url") val issuesUrl: String? = null,
        @SerialName("source_url") val sourceUrl: String? = null,
        @SerialName("wiki_url") val wikiUrl: String? = null,
        @SerialName("discord_url") val discordUrl: String? = null,
        @SerialName("donation_urls") val donationUrls: List<MockDonation> = emptyList(),
        val license: MockLicense? = null,
        val published: String? = null,
        val updated: String? = null,
        val gallery: List<MockGallery> = emptyList(),
    )

    @Serializable data class MockDonation(val id: String = "", val platform: String = "", val url: String = "")
    @Serializable data class MockLicense(val id: String? = null, val name: String? = null, val url: String? = null)
    @Serializable data class MockGallery(val url: String = "", val featured: Boolean = false)

    @Serializable data class MockDisclosureBag(val disclosures: List<MockDisclosure> = emptyList())

    @Serializable
    data class MockDisclosure(
        val type: String,
        val note: String? = null,
        val consent: String? = null,
        val features: List<String> = emptyList(),
        @SerialName("data_collected") val dataCollected: List<String> = emptyList(),
    )

    @Serializable data class MockGameVersion(val version: String, @SerialName("version_type") val versionType: String)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun resource(name: String): String =
        checkNotNull(javaClass.classLoader.getResourceAsStream("mockup/$name")) { "missing fixture $name" }
            .bufferedReader().use { it.readText() }

    private fun project(slug: String) = json.decodeFromString<MockProject>(resource("$slug.project.json"))
    private fun disclosures(slug: String) =
        json.decodeFromString<MockDisclosureBag>(resource("$slug.disclosures.json")).disclosures

    /** Newest-first canonical release order, which is what collapsing a range needs. */
    private val releaseOrder: List<String> by lazy {
        json.decodeFromString<List<MockGameVersion>>(resource("game_versions.json")).map { it.version }
    }

    // ── rules worth having in production ────────────────────────────────────

    /**
     * Game versions as a reader wants them: runs of consecutive releases folded
     * into one range, snapshots dropped.
     *
     * Cloth Config supports fifty-eight of them. Listed one per chip that is
     * eleven rows of noise in a 300dp column, and the answer to "does this run on
     * my game" is not in there anywhere. Folded, it is four chips.
     */
    private fun versionChips(supported: List<String>): List<String> {
        val index = releaseOrder.withIndex().associate { (i, v) -> v to i }
        val present = supported.mapNotNull { v -> index[v]?.let { it to v } }.sortedBy { it.first }
        if (present.isEmpty()) return emptyList()
        val out = mutableListOf<String>()
        var runStart = present.first()
        var previous = present.first()
        for (item in present.drop(1)) {
            if (item.first == previous.first + 1) {
                previous = item
                continue
            }
            out += label(runStart.second, previous.second)
            runStart = item
            previous = item
        }
        out += label(runStart.second, previous.second)
        // The canonical list runs newest-first, so a run reads backwards.
        return out.reversed()
    }

    private fun label(newest: String, oldest: String) =
        if (newest == oldest) newest else "$oldest–$newest"

    /**
     * Placeholder brand colours, and marked as such on purpose.
     *
     * The loader logos are not ours to ship -- Forge's belong to Forge LLC, and
     * NeoForge publishes only an ICO -- so the chip carries the name and the
     * colour instead, which is all a reader needs to pick their loader out of a
     * row. These four values are eyeballed and must be replaced with the real
     * brand values before any of this ships.
     */
    private fun loaderDot(loader: String): Color = when (loader.lowercase()) {
        "fabric"   -> Color(0xFFDBD0B4)
        "forge"    -> Color(0xFF6A8FD8)
        "neoforge" -> Color(0xFFF16436)
        "quilt"    -> Color(0xFF9B6CE0)
        else       -> Color(0xFF8A8A96)
    }

    /**
     * How a loader writes its own name.
     *
     * Capitalising the first letter gives "Neoforge", which is not a thing. These
     * are proper nouns with their own casing and the only correct source is the
     * project itself, so they are a table rather than a rule.
     */
    private fun loaderLabel(loader: String): String = when (loader.lowercase()) {
        "fabric"        -> "Fabric"
        "forge"         -> "Forge"
        "neoforge"      -> "NeoForge"
        "quilt"         -> "Quilt"
        "legacy-fabric" -> "Legacy Fabric"
        "optifine"      -> "OptiFine"
        "iris"          -> "Iris"
        "datapack"      -> "Datapack"
        "minecraft"     -> "Minecraft"
        else            -> loader.replaceFirstChar(Char::uppercase)
    }

    /** What the environment pair actually means, as one phrase rather than two flags. */
    private fun environment(client: String, server: String): String = when {
        client == "required" && server == "unsupported" -> "Только клиент"
        server == "required" && client == "unsupported" -> "Только сервер"
        client == "required" && server == "required"    -> "Клиент и сервер"
        client == "optional" && server == "optional"    -> "Клиент или сервер"
        else                                            -> "Клиент и сервер"
    }

    /** Short, human, and never the raw LicenseRef machinery. */
    private fun licenseLabel(l: MockLicense?): String {
        val id = l?.id ?: return "Лицензия не указана"
        return when {
            id == "LicenseRef-All-Rights-Reserved" -> "Все права защищены"
            id.startsWith("LicenseRef-")           -> id.removePrefix("LicenseRef-").replace('-', ' ')
            else                                   -> id
        }
    }

    private fun compactCount(n: Long): String = when {
        n >= 1_000_000 -> "%.1f млн".format(n / 1_000_000.0)
        n >= 1_000     -> "%.1f тыс.".format(n / 1_000.0)
        else           -> n.toString()
    }

    private fun disclosureLine(d: MockDisclosure): Pair<String, List<String>> = when (d.type) {
        "telemetry" -> when (d.consent) {
            "opt_in"        -> "Телеметрия, включается вручную"
            "opt_out"       -> "Телеметрия, отключается вручную"
            "always_active" -> "Телеметрия, отключить нельзя"
            else            -> "Телеметрия"
        } to d.dataCollected
        "advertisements"      -> "Содержит рекламу" to listOfNotNull(d.note)
        "paid_features"       -> "Содержит платные функции" to d.features
        "ai_content"          -> "Содержит материал, созданный ИИ" to listOfNotNull(d.note)
        "ai_functionality"    -> "Обращается к генеративной модели" to listOfNotNull(d.note)
        "system_interactions" -> "Взаимодействует с системой вне игры" to listOfNotNull(d.note)
        "epilepsy_triggers"   -> "Предупреждение о вспышках света" to listOfNotNull(d.note)
        else                  -> d.type to listOfNotNull(d.note)
    }

    // ── the page ────────────────────────────────────────────────────────────

    /** Which host opened the page, which is the only thing the action depends on. */
    private enum class Host { Browser, Instance }

    /**
     * Where the facts came from. A jar that Modrinth has never indexed still gets
     * this page: the shape of what a reader asks does not change with the origin
     * of the file, so the page keeps its form and marks what it cannot answer.
     */
    private enum class Source { Modrinth, Local }

    /**
     * The SCREEN, not the page: a left rail, the page, and the shell's right rail
     * beside it.
     *
     * Drawn this way because the page alone cannot be judged. `appshell.rightrail`
     * is a shell surface and is therefore present on every screen, so a page that
     * carried its own right-hand column would stand a third column next to the
     * news. The metadata blocks go into the rail instead, and the page keeps the
     * header, the tabs and the body.
     */
    @Composable
    private fun Screen(p: MockProject, dis: List<MockDisclosure>, host: Host, source: Source = Source.Modrinth) {
        Row(Modifier.fillMaxSize().background(NxTheme.colors.background)) {
            // Stand-in for appshell.leftrail, here only so the page is measured
            // against the width it will really get.
            Box(Modifier.width(56.dp).fillMaxHeight().background(NxTheme.colors.surface.copy(alpha = 0.35f)))
            ModPage(p, dis, host, source, Modifier.weight(1f))
            NxSurface(
                level    = NxSurfaceLevel.Sunken,
                // Only the corners that face INTO the page are rounded. The rail
                // sits flush against the window edge, and a rounded corner there
                // is a corner cut off nothing.
                shape    = RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp, topEnd = 0.dp, bottomEnd = 0.dp),
                modifier = Modifier.width(300.dp).fillMaxHeight(),
            ) {
                ModRailFamily(p, dis, source)
            }
        }
    }

    @Composable
    private fun ModPage(
        p: MockProject,
        dis: List<MockDisclosure>,
        host: Host,
        source: Source,
        modifier: Modifier = Modifier,
    ) {
        Column(modifier.fillMaxHeight()) {
            Header(p, host, source)
            NxSurface(
                level    = NxSurfaceLevel.Raised,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Tabs()
                    when {
                        source == Source.Local -> Unknown(
                            "Описание живёт на странице проекта, а этот файл Modrinth не знает.",
                        )
                        p.body.isNotBlank() ->
                            MarkdownHtml(markdown = p.body, modifier = Modifier.fillMaxWidth(), onLink = {})
                        else -> Unknown("Автор ничего не написал об этом проекте.")
                    }
                }
            }
        }
    }

    /** A fact the page has no answer for, said plainly instead of left blank. */
    @Composable
    private fun Unknown(text: String) = Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = NxTheme.colors.textSecondary,
    )

    @Composable
    private fun Header(p: MockProject, host: Host, source: Source) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 22.dp, end = 14.dp, top = 18.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // GAP: nx-ui has no avatar. Twelve places in the app hand-roll
            // "picture, else initials"; this is the thirteenth.
            Box(
                modifier = Modifier.size(84.dp).clip(RoundedCornerShape(18.dp))
                    .background(NxTheme.colors.decorativeColor(p.slug)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    p.title.firstOrNull()?.uppercase() ?: "?",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    p.title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = NxTheme.colors.textPrimary,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    p.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = NxTheme.colors.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Metrics are separate facts and want air between them; the
                    // chips are one list and want to read as one. The same step
                    // for both let the categories drift apart into three lonely
                    // words.
                    if (source == Source.Modrinth) {
                        // GAP: no metadata item either -- icon, value, label. Every
                        // screen that shows a count builds this row by hand.
                        Stat(NxIcon.Download, compactCount(p.downloads), "скачиваний")
                        Stat(NxIcon.Favorite, compactCount(p.followers), "подписчиков")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            p.categories.take(3).forEach { NxMetaChip(it, tone = NxMetaChipTone.Surface) }
                        }
                    } else {
                        // Counts belong to a catalogue. A file found on disk has
                        // none, and inventing a zero would read as an answer.
                        NxMetaChip("Локальный файл", tone = NxMetaChipTone.Surface)
                        NxMetaChip("0.6.13", tone = NxMetaChipTone.Surface)
                    }
                }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    when {
                        source == Source.Local && host == Host.Instance ->
                            NxButton(label = "Найти на Modrinth", onClick = {}, icon = NxIcon.Search, style = NxButtonStyle.Secondary)
                        host == Host.Browser ->
                            NxButton(label = "Установить", onClick = {}, icon = NxIcon.Download)
                        else ->
                            NxButton(label = "Сменить версию", onClick = {}, icon = NxIcon.SwapHoriz, style = NxButtonStyle.Secondary)
                    }
                    // The overflow only appears where it has more than one thing
                    // to hold. On a browsing page it carried a single link, which
                    // is a menu that exists to hide one item.
                    if (host == Host.Instance) {
                        NxKebabButton(contentDescription = "Ещё") { dismiss ->
                            NxMenuItem(label = "Открыть папку", icon = NxIcon.FolderOpen, onClick = dismiss)
                            NxMenuItem(label = "Открыть на Modrinth", icon = NxIcon.OpenInNew, onClick = dismiss)
                            NxMenuItem(label = "Удалить", icon = NxIcon.Delete, destructive = true, onClick = dismiss)
                        }
                    }
                }
                if (host == Host.Instance) {
                    Text(
                        "Установлено: 0.6.13",
                        style = MaterialTheme.typography.labelSmall,
                        color = NxTheme.colors.textSecondary,
                    )
                }
            }
        }
    }

    @Composable
    private fun Stat(icon: IconKey, value: String, label: String) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Symbol(icon, contentDescription = null, tint = NxTheme.colors.textSecondary, size = 15.dp)
            Text(value, style = MaterialTheme.typography.labelLarge, color = NxTheme.colors.textPrimary, fontWeight = FontWeight.SemiBold)
            Text(label, style = MaterialTheme.typography.labelSmall, color = NxTheme.colors.textSecondary)
        }
    }

    @Composable
    private fun Tabs() {
        // GAP: no tab row in nx-ui. NxChoiceChip is a filter chip and reads as
        // one; a tab is a place you are, not a filter you applied.
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            listOf("Описание" to true, "Версии" to false, "Галерея" to false).forEach { (label, active) ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        label,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (active) NxTheme.colors.textPrimary else NxTheme.colors.textSecondary,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    )
                    Spacer(Modifier.size(6.dp))
                    Box(
                        Modifier.width(if (active) 26.dp else 0.dp).size(width = 26.dp, height = 2.dp)
                            .background(if (active) NxTheme.colors.primary else Color.Transparent),
                    )
                }
            }
        }
    }

    @Composable
    private fun ModRailFamily(p: MockProject, dis: List<MockDisclosure>, source: Source) {
        Column(
            modifier = Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Section("Совместимость") {
                Label("Minecraft")
                // A jar declares the loader it needs and usually the game version
                // it was built against, so those two survive the loss of the
                // catalogue. What it cannot tell us is the RANGE it also runs on,
                // and a range guessed from one number would be a lie with a
                // confident shape, so it is a question mark instead.
                Chips(if (source == Source.Modrinth) versionChips(p.gameVersions) else listOf("1.21.1", "?"))
                Label("Платформы")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    p.loaders.forEach { NxMetaChip(loaderLabel(it), dot = loaderDot(it)) }
                }
                Label("Среда")
                Chips(listOf(if (source == Source.Modrinth) environment(p.clientSide, p.serverSide) else "?"))
            }

            val links = buildList<Pair<IconKey, String>> {
                if (source == Source.Local) return@buildList
                p.issuesUrl?.let { add(NxIcon.BugReport to "Сообщить о проблеме") }
                p.sourceUrl?.let { add(NxIcon.Code to "Исходный код") }
                p.wikiUrl?.let { add(NxIcon.Description to "Вики") }
                p.discordUrl?.let { add(NxIcon.Language to "Discord") }
                p.donationUrls.forEach { add(NxIcon.Favorite to "Поддержать автора") }
            }
            if (links.isNotEmpty()) {
                Section("Ссылки") {
                    links.forEach { (icon, text) ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            Symbol(icon, contentDescription = null, tint = NxTheme.colors.textSecondary, size = 16.dp)
                            Text(text, style = MaterialTheme.typography.bodySmall, color = NxTheme.colors.textPrimary)
                            Symbol(NxIcon.OpenInNew, contentDescription = null, tint = NxTheme.colors.textSecondary, size = 12.dp)
                        }
                    }
                }
            }

            Section("Сведения") {
                // Disclosures come FIRST and in plain text, not in red. Only the
                // one that can physically hurt someone gets a colour, and none of
                // these fixtures carries it. Everything else is reported, not
                // accused -- the difference between informing and branding.
                dis.forEach { d ->
                    val (headline, notes) = disclosureLine(d)
                    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        // GAP: no icon for any disclosure. Info stands in for a
                        // megaphone, a coin and a radio tower alike.
                        Symbol(
                            if (d.type == "telemetry") NxIcon.Wifi else NxIcon.Info,
                            contentDescription = null,
                            tint = NxTheme.colors.textSecondary,
                            size = 16.dp,
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(headline, style = MaterialTheme.typography.bodySmall, color = NxTheme.colors.textPrimary)
                            notes.forEach {
                                Text(it, style = MaterialTheme.typography.labelSmall, color = NxTheme.colors.textSecondary)
                            }
                        }
                    }
                }
                // The jar carries its own licence and its own authors; the dates
                // belong to the catalogue entry, not to the file.
                Fact(NxIcon.Shield, licenseLabel(p.license))
                if (source == Source.Modrinth) {
                    Fact(NxIcon.NewReleases, formatBuildTimestamp(p.published)?.let { "Опубликован $it" } ?: "Опубликован ?")
                    Fact(NxIcon.Update, formatBuildTimestamp(p.updated)?.let { "Обновлён $it" } ?: "Обновлён ?")
                } else {
                    Fact(NxIcon.NewReleases, "Опубликован ?")
                    Fact(NxIcon.Update, "Обновлён ?")
                }
            }
        }
    }

    @Composable
    private fun Section(title: String, content: @Composable ColumnScopeShim.() -> Unit) {
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = NxTheme.colors.textPrimary,
                fontWeight = FontWeight.Bold,
            )
            ColumnScopeShim(this).content()
        }
    }

    /** Lets a section body call the helpers below without inheriting ColumnScope. */
    private class ColumnScopeShim(val scope: androidx.compose.foundation.layout.ColumnScope)

    @Composable
    private fun ColumnScopeShim.Label(text: String) = Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = NxTheme.colors.textSecondary,
    )

    @Composable
    private fun ColumnScopeShim.Chips(values: List<String>) = FlowRow(
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) { values.forEach { NxMetaChip(it, tone = NxMetaChipTone.Surface) } }

    @Composable
    private fun ColumnScopeShim.Fact(icon: IconKey, text: String) = Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Symbol(icon, contentDescription = null, tint = NxTheme.colors.textSecondary, size = 16.dp)
        Text(text, style = MaterialTheme.typography.bodySmall, color = NxTheme.colors.textPrimary)
    }

    // ── sheets ──────────────────────────────────────────────────────────────

    private fun sheet(slug: String, host: Host, name: String, source: Source = Source.Modrinth) {
        val out = Path.of("build/render", name)
        Files.createDirectories(out.parent)
        val p = project(slug)
        val d = disclosures(slug)
        val scene = ImageComposeScene(1500, 1000, density = Density(1f)) {
            NxTheme(useDarkTheme = true) { Screen(p, d, host, source) }
        }
        val png = try {
            var t = 0L
            repeat(24) { scene.render(t); t += 16_000_000L }
            scene.render(t).encodeToData(EncodedImageFormat.PNG) ?: error("PNG encode failed")
        } finally {
            scene.close()
        }
        Files.write(out, png.bytes)
        assertTrue(png.bytes.size > 4000, "$name did not draw")
    }

    @Test fun `sodium, opened from the browser`() = sheet("sodium", Host.Browser, "modpage-sodium.png")
    @Test fun `essential, which declares three things`() = sheet("essential", Host.Browser, "modpage-essential.png")
    @Test fun `cloth config, fifty-eight game versions and a four-line body`() =
        sheet("cloth-config", Host.Instance, "modpage-cloth.png")

    /**
     * The same page for a jar Modrinth has never seen. Its shape does not change;
     * what it cannot answer says so.
     */
    @Test fun `a jar the catalogue does not know`() =
        sheet("cloth-config", Host.Instance, "modpage-local.png", Source.Local)
}

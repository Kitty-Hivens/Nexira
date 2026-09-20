package hivens.ui.screens.versions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import hivens.core.update.VersionChannel
import hivens.ui.components.channelColor
import hivens.ui.components.formatBuildTimestamp
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.nx.NxButton
import hivens.ui.nx.NxIconButton
import hivens.ui.nx.NxMetaChip
import hivens.ui.nx.NxMetaChipTone
import hivens.ui.nx.NxVerticalScrollbar
import hivens.ui.surface.NxSurface
import hivens.ui.surface.NxSurfaceLevel
import hivens.ui.theme.NxTheme
import hivens.ui.utils.humanSize
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The version picker as one list instead of two panels.
 *
 * What the window is for is mundane: pick a build and close. The shipped one
 * answers that with a master-detail split across 88% of the app window, and the
 * detail half is where the trouble is. Real notes on a real project run 29 to 60
 * characters, one sentence, so the pane holds a line and seven hundred pixels of
 * nothing. A sentence that short belongs on the row it describes.
 *
 * So: one column, the row carries what the row is, the note sits under it when
 * there is one, and the action appears on the row that is chosen rather than in
 * a footer strip. No footer at all, which is sixty pixels of dead weight gone.
 *
 * Fed the real listing for Iris on 1.21.1, which also brings the two problems
 * invented data did not have. Version numbers carry a build suffix
 * (`1.8.14-beta.1+1.21.1-neoforge`) that is three quarters noise, and the same
 * build appears once per loader, so two rows differ only in a tail nobody reads.
 * The suffix is split off and the loader becomes a chip, which is what makes the
 * pair legible.
 */
class VersionListConceptProbe {

    @Serializable
    data class Fixture(
        val id: String,
        @SerialName("version_number") val versionNumber: String,
        @SerialName("version_type") val versionType: String,
        @SerialName("date_published") val datePublished: String,
        val changelog: String? = null,
        val loaders: List<String> = emptyList(),
        val files: List<FixtureFile> = emptyList(),
    )

    @Serializable data class FixtureFile(val size: Long = 0, val primary: Boolean = false)

    private val versions: List<Fixture> by lazy {
        val raw = checkNotNull(javaClass.classLoader.getResourceAsStream("catalogue/iris.versions.json"))
            .bufferedReader().use { it.readText() }
        Json { ignoreUnknownKeys = true }.decodeFromString<List<Fixture>>(raw)
    }

    /**
     * One row: a build, and every loader that build was published for.
     *
     * Iris ships `1.8.8+1.21.1-fabric` and `1.8.8+1.21.1-neoforge` as two
     * entries, identical but for the tail and the byte count, and a project that
     * does this doubles the list. Filtering by the instance's loader hides half
     * of it, which works right up until there is no instance to filter by. Both
     * on one row loses nothing and answers "does this build exist for me" in the
     * chips rather than by making the reader scan for a second copy.
     */
    private data class Build(
        val label: String,
        val entries: List<Fixture>,
        val loaders: List<String>,
    ) {
        val head: Fixture get() = entries.first()
    }

    /**
     * Collapse the listing by the part of the number a person reads, newest run
     * first. [preferred] decides which member's file size is shown, because the
     * builds differ in bytes and one number standing for both would be a lie.
     */
    private fun builds(preferred: String): List<Build> =
        versions.groupBy { label(it) }.map { (label, group) ->
            val ordered = group.sortedByDescending { it.loaders.contains(preferred) }
            Build(label, ordered, group.flatMap { it.loaders }.distinct())
        }

    /**
     * The first line of a changelog, as prose rather than as source.
     *
     * A changelog is markdown, and its first line is as often a heading as a
     * sentence, so the preview printed `## One of the biggest releases ever`
     * with the hashes intact. Stripped here rather than rendered: this is one
     * line of context on a row, not a document, and a renderer on every row of a
     * long list is a parse per row for text nobody reads in full.
     */
    private fun noteLine(raw: String?): String? {
        val line = raw?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() } ?: return null
        return line
            .removePrefix(">").trimStart()
            .trimStart('#', '*', '-', '+').trimStart()
            .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
            .replace(Regex("`([^`]+)`"), "$1")
            .replace(Regex("\\[(.+?)]\\((?:[^)]*)\\)"), "$1")
            .trim()
            .takeIf { it.isNotEmpty() }
    }

    /**
     * The part of a version number a person reads.
     *
     * Modrinth's `version_number` carries the build target after a `+`, so the
     * string on screen is three quarters a repeat of the row's own metadata. The
     * head is the version; the tail is said again by the chips beside it.
     */
    private fun label(v: Fixture) = v.versionNumber.substringBefore('+')

    private fun channel(v: Fixture) = when (v.versionType) {
        "beta"  -> VersionChannel.Beta
        "alpha" -> VersionChannel.Alpha
        else    -> VersionChannel.Release
    }

    private fun size(v: Fixture) = (v.files.firstOrNull { it.primary } ?: v.files.firstOrNull())?.size ?: 0L

    @Composable
    private fun VersionRow(b: Build, installed: Boolean, latest: Boolean, selected: Boolean) {
        val s = LocalStrings.current
        val v = b.head
        val note = noteLine(v.changelog)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .background(if (selected) NxTheme.colors.primary.copy(alpha = 0.12f) else Color.Transparent)
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(channelColor(channel(v))))
                Text(
                    label(v),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NxTheme.colors.textPrimary,
                    fontWeight = if (installed || selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                b.loaders.forEach {
                    NxMetaChip(it.replaceFirstChar(Char::uppercase), tone = NxMetaChipTone.Surface)
                }
                when {
                    installed -> NxMetaChip("Текущая", tone = NxMetaChipTone.Success)
                    latest    -> NxMetaChip("Последняя", tone = NxMetaChipTone.Surface)
                }
                Spacer(Modifier.weight(1f))
                // One trailing slot, not two. The action belongs to the row that
                // was chosen rather than to a footer holding a single button, and
                // it takes the meta's place instead of sitting beside it: adding
                // a button to a row already carrying a date, a size, a badge and
                // up to three loader chips ran the row out of width and clipped
                // both. By the time a build is chosen its date has been read.
                if (selected) {
                    NxButton(label = "Установить", onClick = {}, icon = NxIcon.Download, compact = true)
                } else {
                    // Date and size ride together rather than splitting into two
                    // ragged columns with the label. They are one fact about the
                    // build, read at a glance or not at all.
                    Text(
                        listOfNotNull(formatBuildTimestamp(v.datePublished)?.substringBefore(' '), humanSize(size(v), s))
                            .joinToString("  ·  "),
                        style = MaterialTheme.typography.labelSmall,
                        color = NxTheme.colors.textSecondary,
                        maxLines = 1,
                    )
                }
            }
            if (note != null) {
                Text(
                    note,
                    style = MaterialTheme.typography.labelSmall,
                    color = NxTheme.colors.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 17.dp),
                )
            }
        }
    }

    @Composable
    private fun Window(selectedIndex: Int, installedIndex: Int) {
        Box(Modifier.fillMaxSize().background(Color(0xFF0B0B0D)), contentAlignment = Alignment.Center) {
            NxSurface(
                level = NxSurfaceLevel.Raised,
                // Sized to what it holds. The shipped window takes 88 percent of
                // the app whatever is in it, which is why eight builds arrive
                // inside a hall.
                modifier = Modifier.width(620.dp),
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).background(Color(0xFF2E7BE8)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("I", style = MaterialTheme.typography.titleSmall, color = Color.White, fontWeight = FontWeight.Bold)
                        }
                        Column(Modifier.weight(1f)) {
                            Text("Iris Shaders", style = MaterialTheme.typography.titleSmall, color = NxTheme.colors.textPrimary, fontWeight = FontWeight.Bold)
                            Text("${builds("neoforge").size} сборок для 1.21.1", style = MaterialTheme.typography.labelSmall, color = NxTheme.colors.textSecondary)
                        }
                        NxIconButton(icon = NxIcon.Close, contentDescription = "Закрыть", onClick = {})
                    }
                    // Capped, because a project with a hundred builds would
                    // otherwise size the window to its own history.
                    val rows = builds(preferred = "neoforge")
                    val scroll = rememberScrollState()
                    Box(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                        Column(
                            Modifier.fillMaxWidth()
                                .verticalScroll(scroll)
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                        ) {
                            rows.forEachIndexed { i, b ->
                                VersionRow(b, installed = i == installedIndex, latest = i == 0, selected = i == selectedIndex)
                            }
                        }
                        NxVerticalScrollbar(
                            adapter  = rememberScrollbarAdapter(scroll),
                            revealed = true,
                            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 4.dp),
                        )
                    }
                    Spacer(Modifier.size(8.dp))
                }
            }
        }
    }

    private fun sheet(name: String, selected: Int, installed: Int) {
        val out = Path.of("build/render", name)
        Files.createDirectories(out.parent)
        val scene = ImageComposeScene(900, 760, density = Density(1f)) {
            NxTheme(useDarkTheme = true) { Window(selected, installed) }
        }
        val png = try {
            var t = 0L
            repeat(12) { scene.render(t).close(); t += 16_000_000L }
            scene.render(t).encodeToData(EncodedImageFormat.PNG) ?: error("PNG encode failed")
        } finally {
            scene.close()
        }
        Files.write(out, png.bytes)
        assertTrue(png.bytes.size > 3000, "$name did not draw")
    }

    @Test fun `nothing chosen yet, the installed build marked`() =
        sheet("versionlist-idle.png", selected = -1, installed = 2)

    @Test fun `a newer build chosen, the action on its own row`() =
        sheet("versionlist-chosen.png", selected = 0, installed = 2)
}

package hivens.ui.screens.mod

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import hivens.core.api.dto.modrinth.ModrinthDisclosure
import hivens.core.api.dto.modrinth.ModrinthDisclosures
import hivens.core.api.dto.modrinth.ModrinthGameVersion
import hivens.core.api.dto.modrinth.ModrinthProject
import hivens.ui.components.formatBuildTimestamp
import hivens.ui.surface.NxSurface
import hivens.ui.surface.NxSurfaceLevel
import hivens.ui.theme.NxTheme
import hivens.ui.widgets.Sources
import hivens.ui.widgets.WidgetSurface
import hivens.widget.api.LocalLayoutGraph
import hivens.widget.api.LocalWidgetDataRegistry
import hivens.widget.api.LocalSurfaceFamilies
import hivens.widget.api.LocalWidgetRegistry
import hivens.widget.api.LocalWidgetSurfaceRenderer
import hivens.widget.api.SlotRenderer
import hivens.widget.api.SurfaceFamilies
import hivens.widget.api.WidgetDataRegistry
import hivens.widget.api.flowSource
import hivens.widget.generated.GeneratedWidgetRegistry
import hivens.widget.model.DefaultLayout
import hivens.widget.model.FamilyId
import hivens.widget.model.SlotId
import hivens.widget.model.SurfaceId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The project-view rail, drawn off-screen from the real fixtures through the real
 * kernel, so the visual port can be looked at rather than argued about.
 *
 * Not a unit test of a rule. It renders what the running app renders: the bundled
 * layout's `projectView` family, the KSP registry's own descriptors, and the
 * production plane renderer -- which is the part a hand-built preview would miss,
 * because each block's card comes from its `@Widget(surface = ...)` and nothing
 * paints it unless the kernel is in the loop.
 *
 * Three projects, chosen to break the column rather than to flatter it: one with
 * fifty-eight game versions and a four-line body, one that declares three things
 * about itself, and one plain one.
 */
class ProjectRailRenderTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun resource(name: String): String =
        checkNotNull(javaClass.classLoader.getResourceAsStream("catalogue/$name")) { "missing fixture $name" }
            .bufferedReader().use { it.readText() }

    private fun project(slug: String) = json.decodeFromString<ModrinthProject>(resource("$slug.project.json"))

    private fun disclosures(slug: String): List<ModrinthDisclosure> =
        json.decodeFromString<ModrinthDisclosures>(resource("$slug.disclosures.json")).disclosures

    private val gameVersions: List<ModrinthGameVersion> by lazy {
        json.decodeFromString<List<ModrinthGameVersion>>(resource("game_versions.json"))
    }

    /** The same reduction the page performs before it publishes. */
    private fun open(slug: String): OpenProject {
        val p = project(slug)
        return OpenProject(
            targetKey = "catalogue:${p.id}",
            title = p.title,
            slug = p.slug,
            source = ProjectSource.Catalogue,
            gameVersionLabels = foldGameVersions(p.gameVersions, gameVersions),
            loaders = p.loaders,
            categories = p.categories + p.additionalCategories,
            clientSide = p.clientSide,
            serverSide = p.serverSide,
            licenseId = p.license?.id,
            licenseName = p.license?.name,
            // Formatted here because the page formats before it publishes. Handing
            // the raw ISO stamp through made the sheet show something the running
            // app never shows, which is the one thing a reference sheet must not do.
            publishedAt = formatBuildTimestamp(p.published),
            updatedAt = formatBuildTimestamp(p.updated),
            links = buildList {
                p.issuesUrl?.let { add(ProjectLink(ProjectLinkKind.Issues, it)) }
                p.sourceUrl?.let { add(ProjectLink(ProjectLinkKind.Source, it)) }
                p.wikiUrl?.let { add(ProjectLink(ProjectLinkKind.Wiki, it)) }
                p.discordUrl?.let { add(ProjectLink(ProjectLinkKind.Discord, it)) }
                p.donationUrls.forEach {
                    add(ProjectLink(ProjectLinkKind.Donate, it.url, it.platform.takeIf { n -> n.isNotBlank() }))
                }
            },
            disclosures = disclosures(slug),
            creators = listOf(
                ProjectCreator(name = "jellysquid3", role = "Owner", owner = true),
                ProjectCreator(name = "IMS", role = "Developer"),
            ),
        )
    }

    @Composable
    private fun Rail(project: OpenProject) {
        val registry = WidgetDataRegistry().apply {
            register(Sources.OpenProject, flowSource(MutableStateFlow<OpenProject?>(project)))
        }
        val surface = SurfaceId("appshell.rightrail")
        // Switched the way the page switches it. Without this the sheet drew three
        // empty columns, because SlotRenderer resolves the family from the holder
        // and an unswitched holder answers "general" -- which has no modData slot,
        // so every block silently rendered nothing. Which is also exactly what the
        // rail does on screen for a family nobody switched to.
        val families = SurfaceFamilies().apply { switch(surface, FamilyId("projectView")) }
        CompositionLocalProvider(
            LocalLayoutGraph provides DefaultLayout.load(),
            LocalWidgetRegistry provides GeneratedWidgetRegistry,
            LocalWidgetDataRegistry provides registry,
            LocalSurfaceFamilies provides families,
            LocalWidgetSurfaceRenderer provides { spec, content -> WidgetSurface(spec, content) },
        ) {
            // The rail as the shell sits it: 300 wide, its own plane, rounded only
            // on the edge that faces the page.
            NxSurface(
                level = NxSurfaceLevel.Sunken,
                modifier = Modifier.width(300.dp).fillMaxHeight(),
            ) {
                Column(
                    modifier = Modifier.fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Addressed into the project-view family explicitly rather than
                    // through the live holder, because a sheet has no navigation to
                    // have switched it.
                    SlotRenderer(surface, SlotId("modData"), Modifier.fillMaxWidth(), spacing = 12.dp)
                    SlotRenderer(surface, SlotId("authorData"), Modifier.fillMaxWidth(), spacing = 12.dp)
                }
            }
        }
    }

    private fun sheet(slugs: List<String>, name: String, dark: Boolean = true) {
        val out = Path.of("build/render", name)
        Files.createDirectories(out.parent)
        val projects = slugs.map(::open)
        val scene = ImageComposeScene(300 * slugs.size + 40 * (slugs.size + 1), 1200, density = Density(1f)) {
            NxTheme(useDarkTheme = dark) {
                Box(Modifier.fillMaxSize().background(NxTheme.colors.background)) {
                    Row(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(40.dp),
                    ) {
                        projects.forEach { Rail(it) }
                    }
                }
            }
        }
        val png = try {
            var t = 0L
            repeat(24) { scene.render(t); t += 16_000_000L }
            scene.render(t).encodeToData(EncodedImageFormat.PNG) ?: error("PNG encode failed")
        } finally {
            scene.close()
        }
        Files.write(out, png.bytes)
        assertTrue(png.bytes.size > 40_000, "$name drew nothing but empty rails (${png.bytes.size} bytes)")
    }

    @Test
    fun `the rail, three projects side by side`() =
        sheet(listOf("sodium", "essential", "cloth-config"), "project-rail.png")

    /**
     * The same rail on the light style.
     *
     * The blocks are built out of style tokens and the chips inside them carry a
     * hairline drawn from the outline token, which had only ever been looked at
     * over a dark plane. A rule that reads on one background and vanishes on the
     * other is the whole failure mode of an outline, and nothing but a picture
     * catches it.
     */
    @Test
    fun `the rail holds up on the light style`() =
        sheet(listOf("sodium", "cloth-config"), "project-rail-light.png", dark = false)

    /**
     * Cloth Config's fifty-eight versions, which is the case the folding exists
     * for, and the one the first attempt got visibly wrong.
     */
    @Test
    fun `fifty-eight game versions fold into something readable`() {
        val labels = foldGameVersions(project("cloth-config").gameVersions, gameVersions)
        assertTrue(labels.isNotEmpty(), "nothing folded")
        assertTrue(
            labels.size < 12,
            "fifty-eight versions came out as ${labels.size} chips: ${labels.joinToString(", ")}",
        )
        assertTrue(
            labels.none { it.count { c -> c == '-' } > 1 },
            "a range crossed a major: ${labels.joinToString(", ")}",
        )
        println("cloth-config folds to: ${labels.joinToString(", ")}")
    }
}

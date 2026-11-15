package hivens.ui.feature.catalogue.project

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
import androidx.compose.runtime.remember
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import hivens.catalogue.api.CatalogueKeys
import hivens.catalogue.api.Disclosure
import hivens.catalogue.api.GameVersionTag
import hivens.catalogue.api.OpenProject
import hivens.catalogue.api.ProjectCreator
import hivens.catalogue.api.ProjectSource
import hivens.catalogue.api.foldGameVersions
import hivens.catalogue.api.groupGameVersions
import hivens.core.api.dto.modrinth.ModrinthDisclosures
import hivens.core.api.dto.modrinth.ModrinthGameVersion
import hivens.core.api.dto.modrinth.ModrinthProject
import hivens.ui.feature.catalogue.links
import hivens.ui.feature.catalogue.toTags
import hivens.ui.feature.catalogue.toView
import hivens.ui.surface.NxSurface
import hivens.ui.theme.NxTheme
import hivens.ui.widgets.ChildLevel
import hivens.ui.widgets.KoinLevelServicesHost
import hivens.ui.widgets.WidgetSurface
import hivens.widget.api.LocalLayoutGraph
import hivens.widget.api.LocalScope
import hivens.widget.api.LocalWidgetDataRegistry
import hivens.widget.api.LocalSurfaceFamilies
import hivens.widget.api.LocalWidgetRegistry
import hivens.widget.api.LocalWidgetSurfaceRenderer
import hivens.widget.api.SlotRenderer
import hivens.widget.api.SurfaceFamilies
import hivens.widget.api.WidgetDataRegistry
import hivens.widget.api.WidgetScope
import hivens.widget.api.flowSource
import hivens.widget.generated.GeneratedWidgetRegistry
import hivens.widget.model.DefaultLayout
import hivens.widget.model.FamilyId
import hivens.widget.model.ScopeLevel
import hivens.widget.model.SlotId
import hivens.widget.model.SurfaceId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue
import hivens.ui.theme.NxColor
import hivens.ui.surface.SurfaceKind

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

    private fun disclosures(slug: String): List<Disclosure> =
        json.decodeFromString<ModrinthDisclosures>(resource("$slug.disclosures.json")).disclosures.map { it.toView() }

    private val gameVersions: List<GameVersionTag> by lazy {
        json.decodeFromString<List<ModrinthGameVersion>>(resource("game_versions.json")).toTags()
    }

    /** The same reduction the page performs before it publishes. */
    private fun open(slug: String): OpenProject {
        val p = project(slug)
        return OpenProject(
            targetKey = "catalogue:${p.id}",
            title = p.title,
            slug = p.slug,
            source = ProjectSource.Catalogue,
            gameVersions = groupGameVersions(p.gameVersions, gameVersions),
            loaders = p.loaders,
            categories = p.categories + p.additionalCategories,
            clientSide = p.clientSide,
            serverSide = p.serverSide,
            licenseId = p.license?.id,
            licenseName = p.license?.name,
            // The moments as the catalogue gives them. The rail puts them into words
            // itself, the way it does in the running app.
            published = p.published,
            updated = p.updated,
            links = p.links(),
            disclosures = disclosures(slug),
            creators = listOf(
                ProjectCreator(name = "jellysquid3", role = "Owner", owner = true),
                ProjectCreator(name = "IMS", role = "Developer"),
            ),
        )
    }

    /** An app level whose windows answer the open project with [project], the way a window's page publishes it. */
    private fun app(project: OpenProject): Pair<WidgetScope, WidgetDataRegistry> {
        val registry = WidgetDataRegistry().apply {
            registerFactory(CatalogueKeys.OpenProject) { flowSource(MutableStateFlow<OpenProject?>(project)) }
        }
        val app: WidgetScope = koinApplication {
            modules(module { single { WidgetScope.app(registry, KoinLevelServicesHost.app(this), Dispatchers.Unconfined) } })
        }.koin.get()
        return app to registry
    }

    @Composable
    private fun Rail(project: OpenProject) {
        val (app, registry) = remember(project) { app(project) }
        // The registry the host provides at its root, which a block reading an app key, the
        // sign-in providers, reads through.
        CompositionLocalProvider(LocalScope provides app, LocalWidgetDataRegistry provides registry) {
            // The rail reads the project off the window it is drawn in.
            ChildLevel(ScopeLevel.Window) { RailInWindow() }
        }
    }

    @Composable
    private fun RailInWindow() {
        val surface = SurfaceId("appshell.rightrail")
        // Switched the way the page switches it. Without this the sheet drew three
        // empty columns, because SlotRenderer resolves the family from the holder
        // and an unswitched holder answers "general" -- which has no modData slot,
        // so every block silently rendered nothing. Which is also exactly what the
        // rail does on screen for a family nobody switched to.
        val families = SurfaceFamilies().apply { switch(surface, FamilyId("projectView"), owner = Unit) }
        CompositionLocalProvider(
            LocalLayoutGraph provides DefaultLayout.load(),
            LocalWidgetRegistry provides GeneratedWidgetRegistry,
            LocalSurfaceFamilies provides families,
            LocalWidgetSurfaceRenderer provides { spec, content -> WidgetSurface(spec, content) },
        ) {
            // The rail as the shell sits it: 300 wide, its own plane, rounded only
            // on the edge that faces the page.
            NxSurface(
                kind = SurfaceKind.Field,
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
            NxTheme(dark = dark) {
                Box(Modifier.fillMaxSize().background(NxColor.page)) {
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
            repeat(24) { scene.render(t).close(); t += 16_000_000L }
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

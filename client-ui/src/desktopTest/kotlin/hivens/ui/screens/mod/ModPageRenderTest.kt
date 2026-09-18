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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import hivens.core.api.HttpClientProvider
import hivens.core.api.dto.modrinth.ModrinthDisclosure
import hivens.core.api.dto.modrinth.ModrinthDisclosures
import hivens.core.api.dto.modrinth.ModrinthGameVersion
import hivens.core.api.dto.modrinth.ModrinthProject
import hivens.core.api.dto.modrinth.ModrinthVersion
import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.PackInstance
import hivens.core.net.TransferEngine
import hivens.launcher.instance.InstanceContentScanner
import hivens.launcher.modrinth.ModrinthClient
import hivens.ui.components.ImageGallery
import hivens.ui.components.formatBuildTimestamp
import hivens.ui.components.modrinthGalleryMedia
import hivens.ui.surface.NxSurface
import hivens.ui.surface.NxSurfaceLevel
import hivens.ui.theme.NxTheme
import hivens.ui.widgets.Sources
import hivens.ui.widgets.WidgetSurface
import hivens.widget.api.LocalLayoutGraph
import hivens.widget.api.LocalSurfaceFamilies
import hivens.widget.api.LocalWidgetDataRegistry
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The whole screen: the shell's left rail, the page, and the project-view right
 * rail beside it.
 *
 * The page cannot be judged on its own. Its metadata blocks live in a shell
 * surface that is present on every screen, so what has to look right is the three
 * columns together -- and the one thing a sheet of the page alone would never show
 * is whether the page and the rail read as one thing or as two.
 *
 * Drawn from the same fixtures the rail sheet uses, through the same kernel.
 */
class ModPageRenderTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun resource(name: String): String =
        checkNotNull(javaClass.classLoader.getResourceAsStream("mockup/$name")) { "missing fixture $name" }
            .bufferedReader().use { it.readText() }

    private fun project(slug: String) = json.decodeFromString<ModrinthProject>(resource("$slug.project.json"))

    private fun disclosures(slug: String): List<ModrinthDisclosure> =
        json.decodeFromString<ModrinthDisclosures>(resource("$slug.disclosures.json")).disclosures

    private val gameVersions: List<ModrinthGameVersion> by lazy {
        json.decodeFromString<List<ModrinthGameVersion>>(resource("game_versions.json"))
    }

    private val creators = listOf(
        ProjectCreator(name = "jellysquid3", role = "Owner", owner = true),
        ProjectCreator(name = "IMS", role = "Developer"),
    )

    /** A state already loaded, which is what the renderers see a moment after arrival. */
    private fun state(slug: String): ModDetailState {
        val p = project(slug)
        // The loader's dependencies, stood up but never reached: the sheet sets the
        // answer directly and never calls load(). A client whose selector throws is
        // the honest stub here, because anything it did touch would be a network
        // call inside a render test.
        val http = HttpClientProvider { error("a render sheet makes no requests") }
        val s = ModDetailState(
            target = ModTarget.Catalogue(p.id),
            modrinth = ModrinthClient(http, TransferEngine(http)),
            repo = EmptyPackRepository,
            dataDir = Path.of("."),
            scanner = InstanceContentScanner(),
            open = OpenProjectState(),
        )
        s.project = p
        s.disclosures = disclosures(slug)
        s.creators = creators
        s.loading = false
        // A page reached from a pack's own browser, which is the case the primary
        // action exists for. Set here rather than resolved, because resolving it
        // reads a folder that a sheet has no business having.
        s.install = InstallAction.Install("Industrial")
        return s
    }

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
            creators = creators,
        )
    }

    @Composable
    private fun Screen(slug: String) {
        val pageState = state(slug)
        val railProject = open(slug)
        val rail = SurfaceId("appshell.rightrail")
        val registry = WidgetDataRegistry().apply {
            register(Sources.OpenProject, flowSource(MutableStateFlow<OpenProject?>(railProject)))
        }
        val families = SurfaceFamilies().apply { switch(rail, FamilyId("projectView")) }

        CompositionLocalProvider(
            LocalLayoutGraph provides DefaultLayout.load(),
            LocalWidgetRegistry provides GeneratedWidgetRegistry,
            LocalWidgetDataRegistry provides registry,
            LocalSurfaceFamilies provides families,
            LocalWidgetSurfaceRenderer provides { spec, content -> WidgetSurface(spec, content) },
        ) {
            Row(Modifier.fillMaxSize().background(NxTheme.colors.background)) {
                // Stand-in for appshell.leftrail, here only so the page is measured
                // against the width it really gets.
                Box(Modifier.width(56.dp).fillMaxHeight().background(NxTheme.colors.surface.copy(alpha = 0.35f)))

                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Header(pageState, onBack = {})
                    Tabs(
                        active = ModPageTab.Description,
                        onSelect = {},
                        modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
                    )
                    NxSurface(
                        level = NxSurfaceLevel.Raised,
                        modifier = Modifier.weight(1f).fillMaxWidth()
                            .padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                    ) {
                        Column(
                            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                        ) {
                            Body(pageState, onRetry = {})
                        }
                    }
                }

                NxSurface(
                    level = NxSurfaceLevel.Sunken,
                    // Rounded only on the edge that faces the page: the rail sits
                    // flush against the window and a corner there cuts off nothing.
                    shape = RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp),
                    modifier = Modifier.width(300.dp).fillMaxHeight(),
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        SlotRenderer(rail, SlotId("modData"), Modifier.fillMaxWidth(), spacing = 12.dp)
                        SlotRenderer(rail, SlotId("authorData"), Modifier.fillMaxWidth(), spacing = 12.dp)
                    }
                }
            }
        }
    }

    private fun sheet(slug: String, name: String) {
        val out = Path.of("build/render", name)
        Files.createDirectories(out.parent)
        val scene = ImageComposeScene(1500, 1000, density = Density(1f)) {
            NxTheme(useDarkTheme = true) { Screen(slug) }
        }
        val png = try {
            var t = 0L
            repeat(24) { scene.render(t); t += 16_000_000L }
            scene.render(t).encodeToData(EncodedImageFormat.PNG) ?: error("PNG encode failed")
        } finally {
            scene.close()
        }
        Files.write(out, png.bytes)
        assertTrue(png.bytes.size > 40_000, "$name drew almost nothing (${png.bytes.size} bytes)")
    }

    /** Never consulted: the sheet loads nothing. */
    private object EmptyPackRepository : IPackRepository {
        override fun observe(): StateFlow<List<PackInstance>> = MutableStateFlow(emptyList())
        override suspend fun list(): List<PackInstance> = emptyList()
        override suspend fun get(id: String): PackInstance? = null
        override suspend fun put(instance: PackInstance) = Unit
        override suspend fun delete(id: String) = Unit
    }

    /**
     * The versions tab, on a project with a real build list.
     *
     * The same furniture the version modal uses, in a pane: what has to look right
     * is that a reader picking a build of a mod sees one thing whether they got
     * here from the page or from an installed row.
     */
    @Test
    fun `the versions tab`() {
        val out = Path.of("build/render", "modpage-versions.png")
        Files.createDirectories(out.parent)
        val pageState = state("sodium")
        // A captured build list. Its file records were trimmed to a size when the
        // fixture was taken for another sheet, so the three fields an install
        // needs were filled in afterwards and are derived from the version id
        // rather than captured -- nothing here downloads, and a hash that looked
        // real would be the lie.
        pageState.versions = json.decodeFromString<List<ModrinthVersion>>(resource("iris.versions.json"))

        val scene = ImageComposeScene(1160, 700, density = Density(1f)) {
            NxTheme(useDarkTheme = true) {
                Box(Modifier.fillMaxSize().background(NxTheme.colors.background).padding(14.dp)) {
                    NxSurface(level = NxSurfaceLevel.Raised, modifier = Modifier.fillMaxSize()) {
                        VersionsPane(pageState, Modifier.fillMaxSize())
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
        assertTrue(png.bytes.size > 20_000, "the versions tab drew almost nothing (${png.bytes.size} bytes)")
    }

    /**
     * The changelog, on a project that ships each release twice.
     *
     * Iris publishes a fabric and a neoforge build of the same version with the
     * same notes, which is the case the repeat rule exists for: four entries, two
     * changes, and the text said once.
     */
    @Test
    fun `the changelog tab`() {
        val out = Path.of("build/render", "modpage-changelog.png")
        Files.createDirectories(out.parent)
        val pageState = state("sodium")
        pageState.versions = json.decodeFromString<List<ModrinthVersion>>(resource("iris.versions.json"))

        val scene = ImageComposeScene(900, 760, density = Density(1f)) {
            NxTheme(useDarkTheme = true) {
                Box(Modifier.fillMaxSize().background(NxTheme.colors.background).padding(14.dp)) {
                    NxSurface(level = NxSurfaceLevel.Raised, modifier = Modifier.fillMaxSize()) {
                        ChangelogPane(pageState, Modifier.fillMaxSize())
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
        assertTrue(png.bytes.size > 20_000, "the changelog drew almost nothing (${png.bytes.size} bytes)")
    }

    /**
     * The gallery, on the pack gallery's own component.
     *
     * The point of the sheet is that there is nothing mod-specific to look at:
     * a project's shots are shots, and if this differs from a pack's strip then
     * one of the two has drifted.
     */
    @Test
    fun `the gallery tab`() {
        val out = Path.of("build/render", "modpage-gallery.png")
        Files.createDirectories(out.parent)
        val media = modrinthGalleryMedia(project("essential").gallery)
        assertTrue(media.isNotEmpty(), "the fixture carries no gallery to draw")

        val scene = ImageComposeScene(1000, 760, density = Density(1f)) {
            NxTheme(useDarkTheme = true) {
                Box(Modifier.fillMaxSize().background(NxTheme.colors.background).padding(14.dp)) {
                    NxSurface(level = NxSurfaceLevel.Raised, modifier = Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxSize().padding(16.dp)) {
                            ImageGallery(media, Modifier.fillMaxWidth())
                        }
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
        assertTrue(png.bytes.size > 20_000, "the gallery drew almost nothing (${png.bytes.size} bytes)")
    }

    @Test fun `sodium, a full page`() = sheet("sodium", "modpage-sodium.png")

    @Test fun `essential, which declares three things`() = sheet("essential", "modpage-essential.png")

    @Test fun `cloth config, a four-line body`() = sheet("cloth-config", "modpage-cloth.png")
}

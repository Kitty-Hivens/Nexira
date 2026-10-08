package hivens.ui.screens.browse

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import hivens.core.api.HttpClientProvider
import hivens.core.api.catalogue.CataloguePack
import hivens.core.api.catalogue.CataloguePackDetails
import hivens.core.api.catalogue.CataloguePackVersion
import hivens.core.api.dto.modrinth.ModrinthSearchHit
import hivens.core.api.dto.modrinth.ModrinthCategoryTag
import hivens.core.api.dto.modrinth.ModrinthGameVersion
import hivens.core.api.dto.modrinth.ModrinthLoaderTag
import hivens.launcher.modrinth.FilterField
import hivens.launcher.modrinth.SearchFilter
import hivens.ui.navigation.NavRequests
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import hivens.core.api.interfaces.IPackCatalogueService
import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.CachedManifestSnapshot
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.core.data.PackReference
import hivens.core.net.TransferEngine
import hivens.launcher.catalogue.PackArtResolver
import hivens.launcher.catalogue.PackCatalogueRegistry
import hivens.launcher.instance.ContentKind
import hivens.launcher.modrinth.ModrinthClient
import hivens.launcher.smrt.SmrtPackClient
import hivens.ui.i18n.AppLocale
import hivens.ui.i18n.LocaleProvider
import hivens.ui.settle
import hivens.ui.surface.NxSurface
import hivens.ui.surface.SurfaceKind
import hivens.ui.theme.NxColor
import hivens.ui.theme.NxInk
import hivens.ui.theme.NxTheme
import hivens.ui.widgets.WidgetSurface
import hivens.widget.api.LocalLayoutGraph
import hivens.widget.api.LocalSurfaceFamilies
import hivens.widget.api.LocalWidgetRegistry
import hivens.widget.api.LocalWidgetSurfaceRenderer
import hivens.widget.api.SlotRenderer
import hivens.widget.api.SurfaceFamilies
import hivens.widget.generated.GeneratedWidgetRegistry
import hivens.widget.model.DefaultLayout
import hivens.widget.model.FamilyId
import hivens.widget.model.SlotId
import hivens.widget.model.SurfaceId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.skia.EncodedImageFormat
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Browse drawn off-screen: a project card in each state its install can be in, and
 * the rail's browse family through the real kernel.
 *
 * The card is where an action reports on itself in line, so the four states are
 * drawn side by side: three of them otherwise read as the absence of the fourth.
 * The rail is drawn through the bundled layout and the KSP registry, because each
 * block's card comes from its `@Widget(surface = ...)` and nothing paints it unless
 * the kernel is in the loop.
 */
class BrowseRenderTest {

    @AfterTest fun tearDown() = stopKoin()

    private val now = Instant.now()

    private fun hit(
        id: String,
        title: String,
        description: String,
        author: String,
        downloads: Long,
        follows: Long,
        daysAgo: Long,
        tags: List<String>,
    ) = ModrinthSearchHit(
        projectId = id,
        slug = id,
        title = title,
        description = description,
        author = author,
        downloads = downloads,
        follows = follows,
        dateModified = now.minus(daysAgo, ChronoUnit.DAYS).toString(),
        displayCategories = tags,
    )

    private val sodium = hit(
        "sodium", "Sodium",
        "The fastest and most compatible rendering optimization mod for Minecraft. Now available for both NeoForge and Fabric!",
        "jellysquid3", 98_400_000, 26_100, 3, listOf("fabric", "neoforge", "quilt", "optimization"),
    )
    private val iris = hit(
        "iris", "Iris Shaders",
        "A modern shader pack loader for Minecraft intended to be compatible with existing OptiFine shader packs",
        "coderbot", 62_000_000, 14_800, 12, listOf("fabric", "neoforge", "quilt", "decoration", "optimization", "utility"),
    )
    private val lithium = hit(
        "lithium", "Lithium",
        "No-compromises game logic optimization mod.",
        "jellysquid3", 41_200_000, 11_000, 40, listOf("fabric", "neoforge", "optimization"),
    )
    private val tiny = hit(
        "tiny-tweaks", "A project whose name runs longer than the card has room for on one line",
        "Short.",
        "someone", 812, 4, 400, emptyList(),
    )

    @Test
    fun `project cards in every install state, wide and narrow`() {
        sheet("browse-project-cards", width = 1100, height = 1060) {
            Caption("с паком: ставится / ставим / стоит / не легло, с причиной")
            ProjectCard(sodium, onOpen = {}, action = { CardInstall(CardPhase.Offered) {} })
            ProjectCard(iris, onOpen = {}, action = { CardInstall(CardPhase.Working) {} })
            ProjectCard(lithium, onOpen = {}, action = { CardInstall(CardPhase.Installed) {} })
            ProjectCard(
                tiny,
                onOpen = {},
                note = "Нет сборки под эту версию игры и загрузчик",
                action = { CardInstall(CardPhase.Failed) {} },
            )
            Caption("без пака: карточка ведёт на страницу, узкая колонка берёт малую иконку")
            ProjectCard(sodium, iconSize = ICON_NARROW, onOpen = {})
        }
    }

    // ── The rail ─────────────────────────────────────────────────────

    private val local = PackInstance(
        id = "neo",
        packRef = PackReference(PackOrigin.Local, "neo"),
        displayName = "NeoForge 1.21.1",
        instanceDirName = "neo",
        createdAtEpoch = 0L,
        iconUrl = "",
    )

    private class FakeRepo(packs: List<PackInstance>) : IPackRepository {
        private val flow = MutableStateFlow(packs)
        override fun observe(): StateFlow<List<PackInstance>> = flow
        override suspend fun list(): List<PackInstance> = flow.value
        override suspend fun get(id: String): PackInstance? = flow.value.firstOrNull { it.id == id }
        override suspend fun put(instance: PackInstance) {}
        override suspend fun delete(id: String) {}
    }

    private class Listing(override val origin: PackOrigin) : IPackCatalogueService {
        override suspend fun search(query: String, page: Int): List<CataloguePack> = emptyList()
        override suspend fun details(packId: String): CataloguePackDetails = error("not on a rail sheet")
        override suspend fun versions(packId: String): List<CataloguePackVersion> = emptyList()
    }

    private val ring = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="9"/></svg>"""

    /** The catalogue's lists, cut down to what a sheet needs and carrying one mark to draw. */
    private val tags = BrowseTags.Tags(
        gameVersions = listOf("1.21.4", "1.21.1", "1.20.1", "24w14a", "1.19.2").map {
            ModrinthGameVersion(it, versionType = if (it.contains('w')) "snapshot" else ModrinthGameVersion.RELEASE)
        },
        categories = listOf(
            ModrinthCategoryTag("optimization", "mod", "categories", ring),
            ModrinthCategoryTag("utility", "mod", "categories", ring),
            ModrinthCategoryTag("worldgen", "mod", "categories", ring),
            ModrinthCategoryTag("decoration", "mod", "categories", ring),
            ModrinthCategoryTag("realistic", "resourcepack", "categories", ring),
            ModrinthCategoryTag("gui", "resourcepack", "features", ring),
            ModrinthCategoryTag("16x", "resourcepack", "resolutions"),
            ModrinthCategoryTag("512x+", "resourcepack", "resolutions"),
            ModrinthCategoryTag("adventure", "modpack", "categories", ring),
        ),
        loaders = listOf(
            ModrinthLoaderTag("fabric", listOf("mod", "modpack")),
            ModrinthLoaderTag("forge", listOf("mod", "modpack")),
            ModrinthLoaderTag("neoforge", listOf("mod", "modpack")),
            ModrinthLoaderTag("quilt", listOf("mod", "modpack")),
            ModrinthLoaderTag("liteloader", listOf("mod")),
            ModrinthLoaderTag("paper", listOf("plugin", "mod")),
        ),
    )

    private fun graph(controller: BrowseController) {
        val http = HttpClientProvider { error("a render sheet does not go to the network") }
        val data: Path = Files.createTempDirectory("nexira-browse-render")
        val modrinth = ModrinthClient(http, TransferEngine(http))
        startKoin {
            modules(
                module {
                    single { controller }
                    single { PackCatalogueRegistry(listOf(Listing(PackOrigin.Mirror), Listing(PackOrigin.Modrinth))) }
                    single<IPackRepository> { FakeRepo(listOf(local)) }
                    single { data }
                    single { PackArtResolver(modrinth, SmrtPackClient(http)) }
                    single { BrowseTags(modrinth, CoroutineScope(Dispatchers.Unconfined), tags) }
                    single { NavRequests() }
                },
            )
        }
    }

    @Composable
    private fun Kernel(surface: SurfaceId, family: FamilyId?, content: @Composable () -> Unit) {
        // Switched the way the screen switches it, so the slot resolves in the
        // browse family rather than in the general one that has no such slot.
        val families = SurfaceFamilies().apply { if (family != null) switch(surface, family) }
        CompositionLocalProvider(
            LocalLayoutGraph provides DefaultLayout.load(),
            LocalWidgetRegistry provides GeneratedWidgetRegistry,
            LocalSurfaceFamilies provides families,
            LocalWidgetSurfaceRenderer provides { spec, body -> WidgetSurface(spec, body) },
            LocalBrowseContext provides STUB_BROWSE,
        ) { content() }
    }

    @Composable
    private fun Rail() {
        val surface = SurfaceId("appshell.rightrail")
        Kernel(surface, FamilyId("browse")) {
            NxSurface(kind = SurfaceKind.Field, modifier = Modifier.width(300.dp).fillMaxHeight()) {
                Column(Modifier.fillMaxSize().padding(12.dp)) {
                    SlotRenderer(surface, SlotId("controls"), Modifier.fillMaxWidth(), spacing = 12.dp)
                }
            }
        }
    }

    @Test
    fun `the filter rail for each kind, with a pack and without`() {
        val controller = BrowseController()
        graph(controller)
        val target = BrowseTarget(
            browseDestination(local.copy(cachedManifest = neoManifest), Path.of("/tmp/nexira-browse-render")),
            listOf(ContentKind.Mod, ContentKind.ResourcePack),
        )
        listOf(
            "browse-rail-mods" to {
                controller.kind = ContentKind.Mod
                controller.targetId = null
                controller.resolved = null
                controller.toggle("mod", SearchFilter(FilterField.Category, "optimization"))
                controller.toggleExclude("mod", SearchFilter(FilterField.Loader, "forge"))
                controller.toggleExclude("mod", SearchFilter(FilterField.Disclosure, "telemetry"))
            },
            "browse-rail-mods-into" to { controller.kind = ContentKind.Mod; controller.targetId = local.id; controller.resolved = target },
            "browse-rail-resourcepacks" to { controller.kind = ContentKind.ResourcePack; controller.targetId = null; controller.resolved = null },
            "browse-rail-packs-mirror" to { controller.kind = null; controller.origin = PackOrigin.Mirror },
            "browse-rail-packs-modrinth" to { controller.kind = null; controller.origin = PackOrigin.Modrinth },
        ).forEach { (name, arrange) ->
            arrange()
            sheet(name, width = 340, height = 1200, padded = false) { Rail() }
        }
    }

    @Test
    fun `the header over the search, with a pack and filters in force`() {
        val controller = BrowseController()
        graph(controller)
        controller.kind = ContentKind.Mod
        controller.targetId = local.id
        controller.resolved = BrowseTarget(browseDestination(local.copy(cachedManifest = neoManifest), Path.of("/tmp/nexira-browse-render")), listOf(ContentKind.Mod))
        controller.query = "sodium"
        controller.toggle("mod", SearchFilter(FilterField.Category, "optimization"))
        controller.toggleExclude("mod", SearchFilter(FilterField.Disclosure, "telemetry"))
        sheet("browse-header", width = 900, height = 300) {
            val surface = SurfaceId("browse")
            Kernel(surface, null) {
                SlotRenderer(surface, SlotId("header"), Modifier.fillMaxWidth(), spacing = 8.dp)
            }
        }
    }

    private val neoManifest = CachedManifestSnapshot(minecraftVersion = "1.21.1", loaderName = "neoforge", loaderVersion = "21.1.200", javaMajor = 21)

    @Composable
    private fun Caption(text: String) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = NxInk.quiet, modifier = Modifier.padding(top = 4.dp))
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun sheet(name: String, width: Int, height: Int, padded: Boolean = true, body: @Composable () -> Unit) {
        val d = 2f
        val scene = ImageComposeScene((width * d).toInt(), (height * d).toInt(), density = Density(d)) {
            LocaleProvider(AppLocale.RUSSIAN) {
                NxTheme(dark = true) {
                    Box(Modifier.fillMaxSize().background(NxColor.page).padding(20.dp)) {
                        if (padded) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { body() }
                        } else {
                            Row { body() }
                        }
                    }
                }
            }
        }
        val t = scene.settle(frames = 30)
        val img = scene.render(t)
        scene.close()
        val out = Path.of("build/render", "$name.png")
        Files.createDirectories(out.parent)
        val bytes = img.encodeToData(EncodedImageFormat.PNG)?.bytes ?: error("PNG encode failed")
        Files.write(out, bytes)
        assertTrue(bytes.size > 20_000, "$name drew almost nothing (${bytes.size} bytes)")
    }
}

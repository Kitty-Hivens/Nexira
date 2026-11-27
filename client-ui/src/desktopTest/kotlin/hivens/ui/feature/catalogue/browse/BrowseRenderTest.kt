package hivens.ui.feature.catalogue.browse

import hivens.ui.standardSlot
import hivens.ui.standardRail
import hivens.ui.DocumentSlot
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
import hivens.catalogue.api.BrowseTarget
import hivens.catalogue.api.BrowseView
import hivens.catalogue.api.CatalogueCard
import hivens.catalogue.api.CatalogueKeys
import hivens.catalogue.api.CatalogueTags
import hivens.catalogue.api.CategoryTag
import hivens.catalogue.api.FilterField
import hivens.catalogue.api.GameVersionTag
import hivens.catalogue.api.LoaderTag
import hivens.catalogue.api.ProjectHit
import hivens.catalogue.api.ProjectKind
import hivens.catalogue.api.SearchFilter
import hivens.catalogue.api.SourceOption
import hivens.catalogue.api.SourceView
import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.core.data.PackReference
import hivens.launcher.modrinth.acceptedLoaders
import hivens.launcher.sources.PackArtResolver
import hivens.launcher.sources.SourceWorks
import hivens.source.mirror.MirrorPackSource
import hivens.source.mirror.MirrorSourceModule
import hivens.source.modrinth.ModrinthPackSource
import hivens.source.modrinth.ModrinthSourceModule
import hivens.themes.Themes
import hivens.ui.host.HostKoin
import hivens.ui.settle
import hivens.ui.surface.NxSurface
import hivens.ui.surface.SurfaceKind
import hivens.ui.theme.NxColor
import hivens.ui.theme.NxInk
import hivens.ui.theme.NxThemed
import hivens.ui.widgets.KoinLevelServicesHost
import hivens.ui.widgets.WidgetSurface
import hivens.widget.api.LocalScope
import hivens.widget.api.LocalWidgetCommandRegistry
import hivens.widget.api.LocalWidgetDataRegistry
import hivens.widget.api.LocalWidgetRegistry
import hivens.widget.api.LocalWidgetSurfaceRenderer
import hivens.widget.api.WidgetCommandRegistry
import hivens.widget.api.WidgetDataRegistry
import hivens.widget.api.WidgetScope
import hivens.widget.api.command
import hivens.widget.api.flowSource
import hivens.widget.generated.GeneratedWidgetRegistry
import hivens.widget.model.ScopeLevel
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.skia.EncodedImageFormat
import org.koin.core.KoinApplication
import org.koin.dsl.koinApplication
import org.koin.dsl.module

/**
 * Browse drawn off-screen: a project card in each state its install can be in, and
 * the rail's browse family through the real kernel.
 *
 * The card is where an action reports on itself in line, so the four states are
 * drawn side by side: three of them otherwise read as the absence of the fourth.
 * The rail is drawn through the bundled layout and the KSP registry, because each
 * block's card comes from its `@Widget(surface = ...)` and nothing paints it unless
 * the kernel is in the loop. What it reads is the window's browse, put up the way the
 * catalogue module answers it, with each sheet's state set directly.
 */
class BrowseRenderTest {

    /** What the sheets inject from: nothing until a test builds its [graph]. */
    private var koin: KoinApplication = koinApplication { }

    @AfterTest fun tearDown() = koin.close()

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
    ) = ProjectHit(
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

    @Test
    fun `mirror pack cards, its own and its community's`() {
        sheet("browse-pack-cards-mirror", width = 900, height = 340) {
            Caption("свой пак зеркала и пак сообщества с подписью владельца")
            BrowsePackCard(
                CatalogueCard(MirrorPackSource.id, "industrial", "Industrial", "Тяжёлая промышленность.", tags = listOf("tech"), mcVersion = "1.12.2"),
                onClick = {},
            )
            BrowsePackCard(
                CatalogueCard(
                    MirrorPackSource.id, "u/7/cozy", "Cozy Valley", "Фермы, уют и ни одного крипера.",
                    tags = listOf("farming"), mcVersion = "1.21.1", community = true, author = "alex",
                ),
                onClick = {},
            )
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

    private val ring = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="9"/></svg>"""

    /** The catalogue's lists, cut down to what a sheet needs and carrying one mark to draw. */
    private val tags = CatalogueTags(
        gameVersions = listOf("1.21.4", "1.21.1", "1.20.1", "24w14a", "1.19.2").map {
            GameVersionTag(it, versionType = if (it.contains('w')) GameVersionTag.SNAPSHOT else GameVersionTag.RELEASE)
        },
        categories = listOf(
            CategoryTag("optimization", "mod", "categories", ring),
            CategoryTag("utility", "mod", "categories", ring),
            CategoryTag("worldgen", "mod", "categories", ring),
            CategoryTag("decoration", "mod", "categories", ring),
            CategoryTag("realistic", "resourcepack", "categories", ring),
            CategoryTag("gui", "resourcepack", "features", ring),
            CategoryTag("16x", "resourcepack", "resolutions"),
            CategoryTag("512x+", "resourcepack", "resolutions"),
            CategoryTag("adventure", "modpack", "categories", ring),
        ),
        loaders = listOf(
            LoaderTag("fabric", listOf("mod", "modpack")),
            LoaderTag("forge", listOf("mod", "modpack")),
            LoaderTag("neoforge", listOf("mod", "modpack")),
            LoaderTag("quilt", listOf("mod", "modpack")),
            LoaderTag("liteloader", listOf("mod")),
            LoaderTag("paper", listOf("plugin", "mod")),
        ),
    )

    /** The window's browse, as the catalogue module would hold it. Each sheet sets it. */
    /** The two sources the build carries, as each module declares itself. */
    private val mirror = SourceView(MirrorPackSource.id, MirrorSourceModule.ID, MirrorPackSource.label, MirrorPackSource.icon, MirrorPackSource.filters)
    private val modrinth = SourceView(ModrinthPackSource.id, ModrinthSourceModule.ID, ModrinthPackSource.label, ModrinthPackSource.icon, packType = ModrinthPackSource.packType)

    private val browse = MutableStateFlow(BrowseView(sources = listOf(mirror, modrinth)))

    /** What the search box was asked to search for. */
    private val asked = ArrayList<String>()

    private val sources = WidgetDataRegistry().apply {
        register(CatalogueKeys.Tags, flowSource(MutableStateFlow<CatalogueTags?>(tags)))
        register(CatalogueKeys.Sources, flowSource(MutableStateFlow(listOf(mirror, modrinth))))
        registerFactory(CatalogueKeys.Browse) { flowSource(browse) }
    }

    private val commands = WidgetCommandRegistry().apply {
        registerFactory(CatalogueKeys.SetQuery) { command { asked += it } }
        register(CatalogueKeys.LoadTags, command { })
    }

    /** The window the widgets are drawn in, under an app level over [koin]. */
    private var window: WidgetScope? = null

    private fun graph() {
        val data: Path = Files.createTempDirectory("nexira-browse-render")
        koin.close()
        koin = koinApplication {
            modules(
                module {
                    single<IPackRepository> { FakeRepo(listOf(local)) }
                    single { data }
                    single { PackArtResolver(SourceWorks()) }
                    single { WidgetScope.app(sources, KoinLevelServicesHost.app(this), Dispatchers.Unconfined, commands) }
                },
            )
        }
        window = koin.koin.get<WidgetScope>().child(ScopeLevel.Window)
    }

    /** What the pack decides for a kind it takes: its game version, and for mods the loaders it runs. */
    private fun lockedBy(target: BrowseTarget, kind: ProjectKind): List<SearchFilter> = buildList {
        add(SearchFilter(FilterField.GameVersion, target.mcVersion))
        if (kind == ProjectKind.Mod) acceptedLoaders(target.loader, target.mcVersion).forEach { add(SearchFilter(FilterField.Loader, it)) }
    }

    @Composable
    private fun Kernel(content: @Composable () -> Unit) {
        CompositionLocalProvider(
            LocalWidgetRegistry provides GeneratedWidgetRegistry,
            LocalWidgetSurfaceRenderer provides { spec, body -> WidgetSurface(spec, body) },
            LocalWidgetDataRegistry provides sources,
            LocalWidgetCommandRegistry provides commands,
            LocalScope provides window,
        ) { content() }
    }

    @Composable
    private fun Rail() {
        // The rail's blocks shown on Browse, the filters.
        Kernel {
            NxSurface(kind = SurfaceKind.Field, modifier = Modifier.width(300.dp).fillMaxHeight()) {
                Column(Modifier.fillMaxSize().padding(12.dp)) {
                    DocumentSlot(standardRail("browse"), Modifier.fillMaxWidth(), spacing = 12.dp)
                }
            }
        }
    }

    @Test
    fun `the filter rail for each kind, with a pack and without`() {
        graph()
        val target = BrowseTarget(local.id, local.displayName, "1.21.1", "neoforge", listOf(ProjectKind.Mod, ProjectKind.ResourcePack))
        val chosen = mapOf(
            "mod" to setOf(
                SearchFilter(FilterField.Category, "optimization"),
                SearchFilter(FilterField.Loader, "forge", excluded = true),
                SearchFilter(FilterField.Disclosure, "telemetry", excluded = true),
            ),
        )
        listOf(
            "browse-rail-mods" to { browse.value = browse.value.copy(kind = ProjectKind.Mod, targetId = null, target = null, chosen = chosen, locked = emptyList()) },
            "browse-rail-mods-into" to {
                browse.value = browse.value.copy(kind = ProjectKind.Mod, targetId = local.id, target = target, locked = lockedBy(target, ProjectKind.Mod))
            },
            "browse-rail-resourcepacks" to { browse.value = browse.value.copy(kind = ProjectKind.ResourcePack, targetId = null, target = null, locked = emptyList()) },
            "browse-rail-packs-mirror" to { browse.value = browse.value.copy(kind = null, pickedOrigin = mirror.id, origin = mirror.id) },
            "browse-rail-packs-mirror-own" to {
                browse.value = browse.value.copy(hidden = setOf(SourceOption(mirror.id, MirrorPackSource.BUILT_BY, MirrorPackSource.COMMUNITY)))
            },
            "browse-rail-packs-modrinth" to {
                browse.value = browse.value.copy(kind = null, pickedOrigin = modrinth.id, origin = modrinth.id, hidden = emptySet())
            },
        ).forEach { (name, arrange) ->
            arrange()
            sheet(name, width = 340, height = 1200, padded = false) { Rail() }
        }
    }

    @Test
    fun `the header over the search, with a pack and filters in force`() {
        graph()
        val target = BrowseTarget(local.id, local.displayName, "1.21.1", "neoforge", listOf(ProjectKind.Mod))
        browse.value = browse.value.copy(
            kind = ProjectKind.Mod,
            targetId = local.id,
            target = target,
            locked = lockedBy(target, ProjectKind.Mod),
            query = "sodium",
            chosen = mapOf(
                "mod" to setOf(
                    SearchFilter(FilterField.Category, "optimization"),
                    SearchFilter(FilterField.Disclosure, "telemetry", excluded = true),
                ),
            ),
        )
        sheet("browse-header", width = 900, height = 300) {
            Kernel {
                DocumentSlot(standardSlot("screen.browse", "header"), Modifier.fillMaxWidth(), spacing = 8.dp)
            }
        }
        assertTrue(asked.isEmpty(), "drawing the search box searched for nothing")
    }

    @Composable
    private fun Caption(text: String) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = NxInk.quiet, modifier = Modifier.padding(top = 4.dp))
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun sheet(name: String, width: Int, height: Int, padded: Boolean = true, body: @Composable () -> Unit) {
        val d = 2f
        val scene = ImageComposeScene((width * d).toInt(), (height * d).toInt(), density = Density(d)) {
            HostKoin(koin) {
                // A pack card's badge names its source among the ones brought in.
                CompositionLocalProvider(LocalWidgetDataRegistry provides sources) {
                    ModuleLanguageProvider("ru", listOf(MirrorSourceModule, ModrinthSourceModule)) {
                        NxThemed(Themes.default, dark = true) {
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

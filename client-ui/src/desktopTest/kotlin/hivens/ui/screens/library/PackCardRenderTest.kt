package hivens.ui.screens.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import hivens.catalogue.api.CatalogueCard
import hivens.catalogue.api.CatalogueKeys
import hivens.catalogue.api.SourceView
import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.core.data.PackReference
import hivens.core.launch.InstanceWorkRegistry
import hivens.launcher.launch.RunningPackSource
import hivens.launcher.sources.EngineSources
import hivens.launcher.sources.PackArtResolver
import hivens.launcher.sources.SourceWorks
import hivens.packs.api.PackKeys
import hivens.packs.api.PackUpdateView
import hivens.packs.toPackView
import hivens.themes.Themes
import hivens.ui.feature.catalogue.browse.BrowsePackCard
import hivens.ui.host.HostKoin
import hivens.ui.i18n.ShippedLanguageProvider
import hivens.ui.navigation.NavRequests
import hivens.ui.notifications.IndicationCenter
import hivens.ui.settle
import hivens.ui.theme.NxColor
import hivens.ui.theme.NxTheme
import hivens.widget.api.LocalWidgetDataRegistry
import hivens.widget.api.WidgetDataRegistry
import hivens.widget.api.flowSource
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.skia.EncodedImageFormat
import org.koin.core.KoinApplication
import org.koin.dsl.koinApplication
import org.koin.dsl.module

/**
 * Every card that stacks metadata chips over cover art, in one sheet.
 *
 * These two carry more chips than any other surface and the least room for
 * them: a fixed-height row whose middle column holds a title, sometimes a
 * tagline, and a wrapping chip line, with a corner pill landing on top of the
 * art. Nothing here was ever covered by a picture, so a change to the shared
 * pill -- its height, its measure, the hairline it now carries -- could push a
 * line out of the card and be found by a person rather than by a build.
 *
 * Both styles, because a chip's fill and its outline are style tokens and a
 * token has to hold everywhere it can land.
 */
class PackCardRenderTest {

    private var koin: KoinApplication? = null

    @AfterTest fun tearDown() { koin?.close() }

    // Art is carried on the record, so the resolver answers from it and the two
    // catalogue clients below are never reached. A blank url is enough: the image
    // loader fails it, the card keeps its pixel-art fill, and the chips -- which
    // are what this sheet is of -- draw over exactly what they draw over live.
    private val instance = PackInstance(
        id = "inst-1",
        packRef = PackReference(EngineSources.Mirror, "Industrial", "2.8.0+1.21.11"),
        displayName = "Industrial",
        instanceDirName = "industrial",
        createdAtEpoch = 0L,
        iconUrl = "",
    )

    /** A fork, played recently, on a source whose version string is long. */
    private val forked = PackInstance(
        id = "inst-2",
        packRef = PackReference(EngineSources.Modrinth, "Prominence II", "SNAPSHOT-0.0.0-2026.07.17"),
        displayName = "Prominence II Hasturian Era",
        instanceDirName = "prominence",
        createdAtEpoch = 0L,
        lastPlayedEpochOrZero = System.currentTimeMillis() / 1000 - 3 * 3600,
        forkedFrom = PackReference(EngineSources.Mirror, "Industrial", "2.8.0"),
        iconUrl = "",
    )

    /** A pack whose source module is not brought in: it lists, and its badge says so. */
    private val orphan = PackInstance(
        id = "inst-3",
        packRef = PackReference(PackOrigin("Curseforge"), "cozy", "1.0"),
        displayName = "Cozy Valley",
        instanceDirName = "cozy",
        createdAtEpoch = 0L,
        iconUrl = "",
    )

    private val catalogue = CatalogueCard(
        origin = EngineSources.Mirror.id,
        id = "cat-1",
        title = "Fabulously Optimized",
        tagline = "Стабильный набор оптимизаций и удобств",
        tags = listOf("optimization", "utility", "vanilla-like"),
        mcVersion = "1.21.1",
    )

    private class FakeRepo(private val packs: List<PackInstance>) : IPackRepository {
        private val flow = MutableStateFlow(packs)
        override fun observe(): StateFlow<List<PackInstance>> = flow
        override suspend fun list(): List<PackInstance> = packs
        override suspend fun get(id: String): PackInstance? = packs.firstOrNull { it.id == id }
        override suspend fun put(instance: PackInstance) {}
        override suspend fun delete(id: String) {}
    }

    /** The packs module's update statuses, as it would answer them: a build waiting for the plain pack. */
    private val keys = WidgetDataRegistry().apply {
        register(PackKeys.Updates, flowSource(MutableStateFlow(mapOf("inst-1" to PackUpdateView.Pending("2.8.1", PackUpdateView.Pending.NEWER, "same")))))
        // The two sources the build carries, brought in. The orphan's is not among them.
        register(
            CatalogueKeys.Sources,
            flowSource(MutableStateFlow(listOf(SourceView(EngineSources.Mirror.id, "nexira.source.mirror", "source.name"), SourceView(EngineSources.Modrinth.id, "nexira.source.modrinth", "source.name")))),
        )
    }

    private fun graph(): KoinApplication {
        return koinApplication {
            modules(
                module {
                    single<IPackRepository> { FakeRepo(listOf(instance, forked, orphan)) }
                    // No source is loaded, so nothing asks the network for a cover.
                    single { PackArtResolver(SourceWorks()) }
                    single { IndicationCenter() }
                    single { NavRequests() }
                    // The card asks whether the pack may be deleted right now.
                    single<RunningPackSource> {
                        object : RunningPackSource {
                            override val runningPackInstanceId = MutableStateFlow<String?>(null)
                        }
                    }
                    single { InstanceWorkRegistry() }
                },
            )
        }.also { koin = it }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun sheet(name: String, graph: KoinApplication, dark: Boolean, body: @Composable () -> Unit) {
        val d = 2f
        val scene = ImageComposeScene((520 * d).toInt(), (480 * d).toInt(), density = Density(d)) {
            HostKoin(graph) {
                ShippedLanguageProvider("ru") {
                    NxTheme(Themes.default, dark = dark) {
                        CompositionLocalProvider(LocalWidgetDataRegistry provides keys) {
                            Box(Modifier.fillMaxSize().background(NxColor.page).padding(16.dp)) {
                                Column(
                                    modifier = Modifier.width(488.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                ) { body() }
                            }
                        }
                    }
                }
            }
        }
        // The art and the update status arrive in effects, so the first frame is
        // the seed rather than the card.
        val t = scene.settle(frames = 20)
        val img = scene.render(t)
        scene.close()
        File("build/render").mkdirs()
        val bytes = img.encodeToData(EncodedImageFormat.PNG)?.bytes ?: error("PNG encode failed")
        File("build/render/$name.png").writeBytes(bytes)
        assertTrue(bytes.size > 20_000, "$name drew almost nothing (${bytes.size} bytes)")
    }

    @Test
    fun `pack cards hold their chips under both styles`() {
        val graph = graph()
        listOf(true, false).forEach { dark ->
            sheet("pack-cards-${if (dark) "dark" else "light"}", graph, dark) {
                // Library: a plain pack carrying the update badge in its corner, and
                // a fork whose version string is long enough to crowd the row.
                PackCard(instance.toPackView(), onOpenDetail = {}, onOpenFolder = {}, onDelete = {})
                PackCard(forked.toPackView(), onOpenDetail = {}, onOpenFolder = {}, onDelete = {})
                PackCard(orphan.toPackView(), onOpenDetail = {}, onOpenFolder = {}, onDelete = {})
                // Browse: a title, a tagline and four chips in the same height.
                BrowsePackCard(catalogue, onClick = {})
            }
        }
    }
}

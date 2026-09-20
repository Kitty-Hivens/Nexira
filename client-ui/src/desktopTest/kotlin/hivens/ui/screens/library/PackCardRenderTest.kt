package hivens.ui.screens.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import hivens.core.api.HttpClientProvider
import hivens.core.api.catalogue.CataloguePack
import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.core.data.PackReference
import hivens.core.net.TransferEngine
import hivens.core.update.CompatChange
import hivens.core.update.PackUpdateStatus
import hivens.core.update.PackUpdateStatusHub
import hivens.core.update.UpdateDirection
import hivens.launcher.catalogue.PackArtResolver
import hivens.launcher.modrinth.ModrinthClient
import hivens.launcher.smrt.SmrtPackClient
import hivens.ui.i18n.AppLocale
import hivens.ui.i18n.LocaleProvider
import hivens.ui.navigation.NavRequests
import hivens.ui.notifications.IndicationCenter
import hivens.ui.screens.browse.BrowsePackCard
import hivens.ui.theme.NxTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.skia.EncodedImageFormat
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import hivens.ui.settle

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

    @AfterTest fun tearDown() = stopKoin()

    // Art is carried on the record, so the resolver answers from it and the two
    // catalogue clients below are never reached. A blank url is enough: the image
    // loader fails it, the card keeps its pixel-art fill, and the chips -- which
    // are what this sheet is of -- draw over exactly what they draw over live.
    private val instance = PackInstance(
        id = "inst-1",
        packRef = PackReference(PackOrigin.Mirror, "Industrial", "2.8.0+1.21.11"),
        displayName = "Industrial",
        instanceDirName = "industrial",
        createdAtEpoch = 0L,
        iconUrl = "",
    )

    /** A fork, played recently, on a source whose version string is long. */
    private val forked = PackInstance(
        id = "inst-2",
        packRef = PackReference(PackOrigin.Modrinth, "Prominence II", "SNAPSHOT-0.0.0-2026.07.17"),
        displayName = "Prominence II Hasturian Era",
        instanceDirName = "prominence",
        createdAtEpoch = 0L,
        lastPlayedEpochOrZero = System.currentTimeMillis() / 1000 - 3 * 3600,
        forkedFrom = PackReference(PackOrigin.Mirror, "Industrial", "2.8.0"),
        iconUrl = "",
    )

    private val catalogue = CataloguePack(
        origin = PackOrigin.Mirror,
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

    private class FakeHub(pending: Map<String, PackUpdateStatus>) : PackUpdateStatusHub {
        override val statuses = MutableStateFlow(pending)
        override fun report(id: String, status: PackUpdateStatus) {}
    }

    /** Selected only if something asks for the network, which is what must not happen here. */
    private fun offlineHttp() = HttpClientProvider { error("a render sheet does not go to the network") }

    private fun graph() {
        val http = offlineHttp()
        startKoin {
            modules(
                module {
                    single<IPackRepository> { FakeRepo(listOf(instance, forked)) }
                    single { PackArtResolver(ModrinthClient(http, TransferEngine(http)), SmrtPackClient(http)) }
                    single { IndicationCenter() }
                    single<PackUpdateStatusHub> {
                        FakeHub(
                            mapOf(
                                "inst-1" to PackUpdateStatus.Pending(
                                    toVersion = "2.8.1",
                                    direction = UpdateDirection.Newer,
                                    compat = CompatChange.Same,
                                ),
                            ),
                        )
                    }
                    single { NavRequests() }
                },
            )
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun sheet(name: String, dark: Boolean, body: @Composable () -> Unit) {
        val d = 2f
        val scene = ImageComposeScene((520 * d).toInt(), (480 * d).toInt(), density = Density(d)) {
            LocaleProvider(AppLocale.RUSSIAN) {
                NxTheme(useDarkTheme = dark) {
                    Box(Modifier.fillMaxSize().background(NxTheme.colors.background).padding(16.dp)) {
                        Column(
                            modifier = Modifier.width(488.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) { body() }
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
        graph()
        listOf(true, false).forEach { dark ->
            sheet("pack-cards-${if (dark) "dark" else "light"}", dark) {
                // Library: a plain pack carrying the update badge in its corner, and
                // a fork whose version string is long enough to crowd the row.
                PackCard(instance, onOpenDetail = {}, onOpenFolder = {}, onDelete = {})
                PackCard(forked, onOpenDetail = {}, onOpenFolder = {}, onDelete = {})
                // Browse: a title, a tagline and four chips in the same height.
                BrowsePackCard(catalogue, onClick = {})
            }
        }
    }
}

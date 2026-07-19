package hivens.ui.screens.detail.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import hivens.core.api.dto.smrt.SmrtPackManifest
import hivens.core.api.dto.smrt.SmrtPackSummary
import hivens.core.api.interfaces.IMirrorPackClient
import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.core.data.PackReference
import hivens.ui.theme.BrutStyle
import hivens.ui.theme.CelestiaStyle
import hivens.ui.theme.NxTheme
import hivens.ui.theme.StyleSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.skia.EncodedImageFormat
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Off-screen render smoke of the floating pack-settings window at FHD and 2K,
 * under both styles. ImageComposeScene rasterises the composition with no
 * display -- fully isolated from any live session -- so it both guards the
 * window from a compose-time crash and dumps a PNG under build/ for a manual
 * look. Only the default (General) section composes here; the header's mirror
 * reads run against an always-failing fake and must degrade to placeholders.
 */
class PackSettingsWindowRenderTest {

    @AfterTest fun tearDown() = stopKoin()

    private class FakeRepo : IPackRepository {
        private val flow = MutableStateFlow<List<PackInstance>>(emptyList())
        override fun observe(): Flow<List<PackInstance>> = flow
        override suspend fun list(): List<PackInstance> = emptyList()
        override suspend fun get(id: String): PackInstance? = null
        override suspend fun put(instance: PackInstance) {}
        override suspend fun delete(id: String) {}
    }

    private object OfflineMirror : IMirrorPackClient {
        override suspend fun fetchManifest(packId: String): SmrtPackManifest = throw IOException("offline")
        override suspend fun fetchManifestVersion(packId: String, version: String): SmrtPackManifest = throw IOException("offline")
        override suspend fun fetchSummary(packId: String): SmrtPackSummary = throw IOException("offline")
    }

    private val pack = PackInstance(
        id = "1",
        packRef = PackReference(PackOrigin.Mirror, "industrial", "5"),
        displayName = "Индустриальная",
        instanceDirName = "industrial",
        createdAtEpoch = 0L,
        notes = "тестовая заметка",
    )

    private fun render(width: Int, height: Int, style: StyleSpec, name: String) {
        startKoin {
            modules(module {
                single<IPackRepository> { FakeRepo() }
                single<IMirrorPackClient> { OfflineMirror }
            })
        }
        val out = Path.of("build/render", name)
        Files.createDirectories(out.parent)
        val scene = ImageComposeScene(width, height, density = Density(1f)) {
            NxTheme(useDarkTheme = true, style = style) {
                // A vivid backdrop so any bleed-through of the overlay surface shows
                // up as a pink tint -- proves the window is actually opaque.
                Box(Modifier.fillMaxSize().background(Color(0xFFE91E63))) {
                    PackSettingsWindow(pack = pack, instanceDir = Path.of("/tmp/render"), onInstanceChange = {}, onDismiss = {})
                }
            }
        }
        try {
            var frameNanos = 0L
            repeat(20) {
                scene.render(frameNanos)
                frameNanos += 16_000_000L
                Thread.sleep(10)
            }
            val png = scene.render(frameNanos).encodeToData(EncodedImageFormat.PNG) ?: error("PNG encode failed")
            Files.write(out, png.bytes)
        } finally {
            scene.close()
        }
        assertTrue(Files.size(out) > 0, "rendered PNG is non-empty")
    }

    @Test fun `renders at FHD 1920x1080 under Celestia`() = render(1920, 1080, CelestiaStyle, "pack-settings-fhd.png")

    @Test fun `renders at FHD 1920x1080 under Brut`() = render(1920, 1080, BrutStyle, "pack-settings-fhd-brut.png")

    @Test fun `renders at 2K 2560x1440 under Celestia`() = render(2560, 1440, CelestiaStyle, "pack-settings-2k.png")
}

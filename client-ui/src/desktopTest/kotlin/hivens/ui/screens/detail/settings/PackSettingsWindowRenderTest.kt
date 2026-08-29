package hivens.ui.screens.detail.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import hivens.core.api.dto.smrt.SmrtBuildDiff
import hivens.core.api.dto.smrt.SmrtPackManifest
import hivens.core.api.dto.smrt.SmrtPackSummary
import hivens.core.api.interfaces.IMirrorPackClient
import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.core.data.PackReference
import hivens.core.update.PackUpdater
import hivens.launcher.PackOperationService
import hivens.launcher.instance.InstanceSizeService
import hivens.ui.theme.BrutStyle
import hivens.ui.theme.CelestiaStyle
import hivens.ui.theme.NxTheme
import hivens.ui.theme.StyleSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
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
        override fun observe(): StateFlow<List<PackInstance>> = flow
        override suspend fun list(): List<PackInstance> = emptyList()
        override suspend fun get(id: String): PackInstance? = null
        override suspend fun put(instance: PackInstance) {}
        override suspend fun delete(id: String) {}
    }

    private object OfflineMirror : IMirrorPackClient {
        override suspend fun fetchManifest(packId: String): SmrtPackManifest = throw IOException("offline")
        override suspend fun fetchManifestVersion(packId: String, version: String): SmrtPackManifest = throw IOException("offline")
        override suspend fun fetchSummary(packId: String): SmrtPackSummary = throw IOException("offline")
        override suspend fun fetchDiff(packId: String, from: String, to: String): SmrtBuildDiff = throw IOException("offline")
    }

    /**
     * The rail asks the updater whether this instance has other builds to manage;
     * nothing past that question composes here, so the rest of the contract is
     * left unreachable rather than faked into something the test would imply.
     */
    private object VersionedSource : PackUpdater {
        override fun handles(instance: PackInstance) = true
        override suspend fun checkForUpdate(instance: PackInstance, forceRefresh: Boolean) = unreachable()
        override suspend fun previewSwitch(instance: PackInstance, targetVersion: String) = unreachable()
        override suspend fun applyUpdate(
            instance: PackInstance,
            targetVersion: String?,
            progress: ((Int, Int, String) -> Unit)?,
        ) = unreachable()
        override suspend fun availableBuilds(instance: PackInstance) = unreachable()
        override fun availableBuildsStream(instance: PackInstance) = unreachable()
        override fun listSnapshots(instance: PackInstance) = unreachable()
        override suspend fun rollback(instance: PackInstance, snapshotId: String) = unreachable()
        private fun unreachable(): Nothing = error("the version section does not compose in this test")
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
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        startKoin {
            modules(module {
                single<IPackRepository> { FakeRepo() }
                single<IMirrorPackClient> { OfflineMirror }
                single { InstanceSizeService(dataDir = Path.of("/tmp/render"), scope = scope) }
                // The window persists an edit on the app scope, so the graph has
                // to hold one -- a write must outlive the window that made it.
                single<CoroutineScope> { scope }
                single { PackOperationService(scope = scope, sizes = get()) }
                single<PackUpdater> { VersionedSource }
            })
        }
        val out = Path.of("build/render", name)
        Files.createDirectories(out.parent)
        val scene = ImageComposeScene(width, height, density = Density(1f)) {
            NxTheme(useDarkTheme = true, style = style) {
                // A vivid backdrop so any bleed-through of the overlay surface shows
                // up as a pink tint -- proves the window is actually opaque.
                Box(Modifier.fillMaxSize().background(Color(BACKDROP))) {
                    PackSettingsWindow(pack = pack, instanceDir = Path.of("/tmp/render"), onDismiss = {})
                }
            }
        }
        val painted: Double
        try {
            var frameNanos = 0L
            repeat(20) {
                scene.render(frameNanos)
                frameNanos += 16_000_000L
                Thread.sleep(10)
            }
            val frame = scene.render(frameNanos)
            Files.write(out, frame.encodeToData(EncodedImageFormat.PNG)?.bytes ?: error("PNG encode failed"))
            painted = paintedFraction(frame)
        } finally {
            scene.close()
        }
        // The window is drawn over a vivid pink ground on purpose. Measuring how much
        // of the frame is no longer pink says both that the window rendered and that
        // it is opaque, where file size said neither.
        assertTrue(painted > MIN_PAINTED, "the window covers ${(painted * 100).toInt()}% of the frame -- it did not render")
    }

    @Test fun `renders at FHD 1920x1080 under Celestia`() = render(1920, 1080, CelestiaStyle, "pack-settings-fhd.png")

    @Test fun `renders at FHD 1920x1080 under Brut`() = render(1920, 1080, BrutStyle, "pack-settings-fhd-brut.png")

    @Test fun `renders at 2K 2560x1440 under Celestia`() = render(2560, 1440, CelestiaStyle, "pack-settings-2k.png")

    /** Share of sampled pixels that are no longer the backdrop the window sits on. */
    private fun paintedFraction(frame: Image): Double {
        val bmp = Bitmap.makeFromImage(frame)
        var painted = 0
        var sampled = 0
        var y = 0
        while (y < bmp.height) {
            var x = 0
            while (x < bmp.width) {
                if (bmp.getColor(x, y) != BACKDROP) painted++
                sampled++
                x += 4
            }
            y += 4
        }
        return painted.toDouble() / sampled
    }

    private companion object {
        /** The vivid ground the window is drawn over, so bleed-through is visible. */
        val BACKDROP = 0xFFE91E63.toInt()

        /**
         * A floating window covers a good part of the frame; an empty one covers
         * none of it. Only has to tell those apart, not pin a layout.
         */
        const val MIN_PAINTED = 0.10
    }
}

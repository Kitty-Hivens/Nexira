package hivens.ui.screens.detail.settings

import androidx.compose.foundation.background
import hivens.core.launch.InstanceWorkRegistry
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
import hivens.ui.theme.NxTheme
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
 * Off-screen render smoke of the pack-settings sheet at FHD and 2K.
 * ImageComposeScene rasterises the composition with no display -- fully isolated
 * from any live session -- so it both guards the sheet from a compose-time crash
 * and dumps a PNG under build/ for a manual look. Only the default (General)
 * section composes here; the header's mirror reads run against an always-failing
 * fake and must degrade to placeholders.
 */
class PackSettingsSheetRenderTest {

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

    private fun render(width: Int, height: Int, name: String) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        startKoin {
            modules(module {
                single<IPackRepository> { FakeRepo() }
                single<IMirrorPackClient> { OfflineMirror }
                single { InstanceSizeService(dataDir = Path.of("/tmp/render"), scope = scope) }
                // The sheet persists an edit on the app scope, so the graph has
                // to hold one -- a write must outlive the sheet that made it.
                single<CoroutineScope> { scope }
                single { PackOperationService(scope = scope, sizes = get(), work = InstanceWorkRegistry()) }
                single<PackUpdater> { VersionedSource }
            })
        }
        val out = Path.of("build/render", name)
        Files.createDirectories(out.parent)
        val scene = ImageComposeScene(width, height, density = Density(1f)) {
            NxTheme(dark = true) {
                // A vivid backdrop so any bleed-through of the sheet shows up as a
                // pink tint -- proves the sheet is opaque and the page beside it is not.
                Box(Modifier.fillMaxSize().background(Color(BACKDROP))) {
                    PackSettingsSheet(pack = pack, instanceDir = Path.of("/tmp/render"), onDismiss = {})
                }
            }
        }
        val sheet: Double
        val page: Double
        try {
            var frameNanos = 0L
            repeat(20) {
                scene.render(frameNanos).close()
                frameNanos += 16_000_000L
                Thread.sleep(10)
            }
            val frame = scene.render(frameNanos)
            Files.write(out, frame.encodeToData(EncodedImageFormat.PNG)?.bytes ?: error("PNG encode failed"))
            // The sheet's own band at the right edge, inside its margins, and a band of
            // the page well to the left of where any sheet width reaches.
            sheet = pinkFraction(frame, xFrom = width - SHEET_BAND, xTo = width - EDGE_BAND)
            page = pinkFraction(frame, xFrom = 0, xTo = width - PAGE_CLEAR)
        } finally {
            scene.close()
        }
        // Drawn over a vivid pink ground on purpose. No pink inside the band says the
        // sheet came in and is opaque. Pink still showing to its left says the page
        // stays visible beside it under a light scrim, which is what makes it a sheet
        // rather than a dialog over everything.
        assertTrue(sheet < MAX_PINK_IN_SHEET, "${(sheet * 100).toInt()}% of the sheet band shows the page through -- it did not come in")
        assertTrue(page > MIN_PINK_BESIDE, "only ${(page * 100).toInt()}% of the page beside the sheet reads as the page -- the scrim is too heavy")
    }

    @Test fun `renders at FHD 1920x1080 under Celestia`() = render(1920, 1080, "pack-settings-fhd.png")


    @Test fun `renders at 2K 2560x1440 under Celestia`() = render(2560, 1440, "pack-settings-2k.png")

    /** Share of sampled pixels in a vertical band that still read as the pink backdrop. */
    private fun pinkFraction(frame: Image, xFrom: Int, xTo: Int): Double {
        val bmp = Bitmap.makeFromImage(frame)
        var pink = 0
        var sampled = 0
        var y = 0
        while (y < bmp.height) {
            var x = xFrom.coerceAtLeast(0)
            while (x < xTo.coerceAtMost(bmp.width)) {
                val c = bmp.getColor(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                if (r > g + PINK_MARGIN) pink++
                sampled++
                x += 4
            }
            y += 4
        }
        return pink.toDouble() / sampled
    }

    private companion object {
        /** The vivid ground the sheet is drawn over, so bleed-through is visible. */
        val BACKDROP = 0xFFE91E63.toInt()

        /** Red ahead of green by this much reads as the pink ground, tinted or not. */
        const val PINK_MARGIN = 60

        /** The band sampled for the sheet: well inside its 640dp at scale one. */
        const val SHEET_BAND = 560
        const val EDGE_BAND = 40

        /** Everything left of this distance from the right edge is page at either size. */
        const val PAGE_CLEAR = 760

        const val MAX_PINK_IN_SHEET = 0.02
        const val MIN_PINK_BESIDE = 0.90
    }
}

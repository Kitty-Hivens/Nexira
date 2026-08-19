package hivens.ui.screens.detail.versions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import hivens.core.api.dto.smrt.SmrtJava
import hivens.core.api.dto.smrt.SmrtLoader
import hivens.core.api.dto.smrt.SmrtBuildDiff
import hivens.core.api.dto.smrt.SmrtDiffEntry
import hivens.core.api.dto.smrt.SmrtDiffUpdate
import hivens.core.update.PackBuild
import hivens.core.api.dto.smrt.SmrtMinecraft
import hivens.core.api.dto.smrt.SmrtModEntry
import hivens.core.api.dto.smrt.SmrtPackManifest
import hivens.core.api.dto.smrt.SmrtPackSummary
import hivens.core.api.dto.smrt.SmrtSource
import hivens.core.api.interfaces.IMirrorPackClient
import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.core.data.PackReference
import hivens.core.smrt.ModIconResolver
import hivens.core.update.CompatChange
import hivens.core.update.PackSnapshot
import hivens.core.update.PackUpdateStatus
import hivens.core.update.PackUpdateStatusHub
import hivens.core.update.PackUpdater
import hivens.core.update.UpdateCheck
import hivens.core.update.UpdateDirection
import hivens.core.update.UpdateOutcome
import hivens.core.update.UpdatePlan
import hivens.launcher.PackOperationService
import hivens.launcher.instance.InstanceSizeService
import hivens.launcher.launch.RunningPackSource
import hivens.ui.theme.BrutStyle
import hivens.ui.theme.CelestiaStyle
import hivens.ui.theme.NxTheme
import hivens.ui.theme.StyleSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Off-screen render smoke of the pack versions screen with a scripted mirror:
 * a three-build history (top two sharing a fingerprint, so the rebuild-run
 * collapse renders), a preview with a plan, and one restore point. Runs under
 * both styles -- a style-token consumer must hold under every style.
 */
class PackVersionsScreenRenderTest {

    @AfterTest fun tearDown() = stopKoin()

    private val pack = PackInstance(
        id = "1",
        packRef = PackReference(PackOrigin.Mirror, "Create", "0.1.1"),
        displayName = "Create",
        instanceDirName = "create",
        createdAtEpoch = 0L,
        pinnedPackVersion = "0.1.1",
    )

    private class FakeRepo(private val pack: PackInstance) : IPackRepository {
        private val flow = MutableStateFlow(listOf(pack))
        override fun observe(): Flow<List<PackInstance>> = flow
        override suspend fun list(): List<PackInstance> = listOf(pack)
        override suspend fun get(id: String): PackInstance? = pack.takeIf { it.id == id }
        override suspend fun put(instance: PackInstance) {}
        override suspend fun delete(id: String) {}
    }

    private object FakeUpdater : PackUpdater {
        val builds = listOf(
            PackBuild("0.1.2", "beta", "2026-07-19T02:14:01Z", fingerprint = "ff", modsCount = 3, assetsCount = 1),
            PackBuild(
                "0.1.1", "beta", "2026-07-19T02:12:58Z", fingerprint = "ff",
                changelog = "Куратор объясняет: обновлены рендер-моды, добавлен Iris.",
                modsCount = 3, assetsCount = 1,
            ),
            PackBuild("SNAPSHOT-0.0.0-2026.07.17", "beta", "2026-07-17T22:25:16Z", fingerprint = "aa", modsCount = 2, assetsCount = 0),
            // Builds from a source that publishes no counts and no fingerprint, so
            // the row has to hold up with half its second line missing. It says
            // what it runs on instead, which is the half a listing can always give.
            PackBuild(
                "1.4.2", "release", "2026-07-16T10:00:00Z",
                changelog = "Fixed a crash on world load.",
                minecraftVersion = "1.21.1", loaderName = "neoforge",
            ),
            PackBuild(
                "1.4.1", "release", "2026-07-02T10:00:00Z",
                minecraftVersion = "1.20.1", loaderName = "fabric",
            ),
        )
        override suspend fun checkForUpdate(instance: PackInstance, forceRefresh: Boolean): UpdateCheck = UpdateCheck.UpToDate
        override suspend fun previewSwitch(instance: PackInstance, targetVersion: String): UpdateCheck =
            UpdateCheck.Available(
                instance.pinnedPackVersion, targetVersion, UpdateDirection.Newer,
                CompatChange.Same, UpdatePlan(toAdd = listOf("mods/New.jar")),
            )
        override suspend fun applyUpdate(
            instance: PackInstance,
            targetVersion: String?,
            progress: ((Int, Int, String) -> Unit)?,
        ): UpdateOutcome = UpdateOutcome.AlreadyCurrent
        override suspend fun availableBuilds(instance: PackInstance): List<PackBuild> = builds
        override fun availableBuildsStream(instance: PackInstance): Flow<List<PackBuild>> = flowOf(builds)
        override fun listSnapshots(instance: PackInstance): List<PackSnapshot> =
            listOf(PackSnapshot("snap-1", 1_752_900_000_000L, "0.1.0"))
        override suspend fun rollback(instance: PackInstance, snapshotId: String): PackInstance = instance
    }

    private object FakeMirror : IMirrorPackClient {
        private fun mod(name: String, sha1: String) = SmrtModEntry(
            filename = "$name.jar", sha1 = sha1, sizeBytes = 1000,
            source = SmrtSource.SmrtCache(url = "https://m/$sha1.jar"),
        )
        private fun manifest(version: String, fingerprint: String, mods: List<SmrtModEntry>) = SmrtPackManifest(
            schemaVersion = 2, packId = "Create", packVersion = version, generatedAt = "t",
            fingerprint = fingerprint,
            minecraft = SmrtMinecraft("1.21.1"), loader = SmrtLoader("neoforge", "21.1.186"), java = SmrtJava(21),
            mods = mods,
        )
        override suspend fun fetchManifest(packId: String): SmrtPackManifest =
            fetchManifestVersion(packId, "0.1.2")
        override suspend fun fetchManifestVersion(packId: String, version: String): SmrtPackManifest = when (version) {
            "SNAPSHOT-0.0.0-2026.07.17" -> manifest(version, "aa", listOf(mod("Architectury", "a1"), mod("Sodium", "s1")))
            else -> manifest(version, "ff", listOf(mod("Architectury", "a1"), mod("Sodium", "s2"), mod("Iris", "i1")))
        }
        override suspend fun fetchSummary(packId: String): SmrtPackSummary = SmrtPackSummary(
            packId = packId, displayName = "Create", tagline = "t", minecraftVersion = "1.21.1",
            latestPackVersion = "0.1.2", latestBuiltAt = "2026-07-19T02:14:01Z", latestChannel = "beta",
        )
        override suspend fun fetchDiff(packId: String, from: String, to: String): SmrtBuildDiff = SmrtBuildDiff(
            schemaVersion = 2, packId = packId, from = from, to = to, contentChanged = true,
            modsUpdated = listOf(SmrtDiffUpdate("Sodium.jar", versionFrom = "0.6.0", versionTo = "0.6.13", sha1From = "s1", sha1To = "s2")),
            modsAdded = listOf(SmrtDiffEntry("Iris.jar", version = "1.8.12")),
        )
    }

    private object FakeHub : PackUpdateStatusHub {
        override val statuses = MutableStateFlow<Map<String, PackUpdateStatus>>(emptyMap())
        override fun report(id: String, status: PackUpdateStatus) {}
    }

    /** Nothing is playing, so the running-pack guard stays out of the way. */
    private object IdleLaunches : RunningPackSource {
        override val runningPackInstanceId: StateFlow<String?> = MutableStateFlow(null)
    }

    private fun render(width: Int, height: Int, style: StyleSpec, name: String) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        startKoin {
            modules(module {
                single<IPackRepository> { FakeRepo(pack) }
                single<PackUpdater> { FakeUpdater }
                single<IMirrorPackClient> { FakeMirror }
                single<PackUpdateStatusHub> { FakeHub }
                single<RunningPackSource> { IdleLaunches }
                single { ModIconResolver(resolveProjectIcon = { null }) }
                // The screen narrates whatever operation the instance is running,
                // its own switch included, so it reaches for the app-scoped owner.
                single { InstanceSizeService(dataDir = Path.of("/tmp/render"), scope = scope) }
                single { PackOperationService(scope = scope, sizes = get()) }
            })
        }
        val out = Path.of("build/render", name)
        Files.createDirectories(out.parent)
        val scene = ImageComposeScene(width, height, density = Density(1f)) {
            NxTheme(useDarkTheme = true, style = style) {
                Box(Modifier.fillMaxSize().background(Color(BACKDROP))) {
                    PackVersionsScreen(instanceId = "1", onBack = {})
                }
            }
        }
        val painted: Double
        try {
            // Pump frames so the screen's suspend loads (build list, preview, diff)
            // land before the captured frame -- a single render would freeze the
            // initial spinner. Wall-clock sleeps let the IO-dispatched fakes hop back.
            var frameNanos = 0L
            repeat(40) {
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
        // A composition that throws still encodes a perfectly valid PNG of the bare
        // backdrop, so file size says nothing about whether the screen is on it.
        // Ask the frame instead.
        assertTrue(painted > MIN_PAINTED, "the screen covers ${(painted * 100).toInt()}% of the frame -- it did not render")
    }

    /**
     * Share of sampled pixels that are not the bare backdrop the scene was cleared
     * to. Sampled on a stride: this separates a drawn screen from an empty one, and
     * does not need to be exact to do that.
     */
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

    @Test fun `renders at FHD under Celestia`() = render(1920, 1080, CelestiaStyle, "pack-versions-fhd-celestia.png")

    @Test fun `renders at FHD under Brut`() = render(1920, 1080, BrutStyle, "pack-versions-fhd-brut.png")

    @Test fun `renders at 2K under Celestia`() = render(2560, 1440, CelestiaStyle, "pack-versions-2k-celestia.png")

    private companion object {
        /** What the scene is cleared to, so anything else on the frame is the screen. */
        val BACKDROP = 0xFF102030.toInt()

        /**
         * Well under what a drawn screen covers and well over the stray pixels an
         * empty one leaves (a partial spinner). Not a layout assertion -- it only
         * has to tell "rendered" from "did not".
         */
        const val MIN_PAINTED = 0.10
    }
}

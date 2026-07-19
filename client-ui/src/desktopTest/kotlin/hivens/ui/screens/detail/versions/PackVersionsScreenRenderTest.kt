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
import hivens.core.api.dto.smrt.SmrtManifestBuild
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
import hivens.core.update.PackUpdater
import hivens.core.update.UpdateCheck
import hivens.core.update.UpdateOutcome
import hivens.core.update.UpdatePlan
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
            SmrtManifestBuild("0.1.2", "beta", "2026-07-19T02:14:01Z", "ff", 3, 1),
            SmrtManifestBuild("0.1.1", "beta", "2026-07-19T02:12:58Z", "ff", 3, 1),
            SmrtManifestBuild("SNAPSHOT-0.0.0-2026.07.17", "beta", "2026-07-17T22:25:16Z", "aa", 2, 0),
        )
        override suspend fun checkForUpdate(instance: PackInstance): UpdateCheck = UpdateCheck.UpToDate
        override suspend fun previewSwitch(instance: PackInstance, targetVersion: String): UpdateCheck =
            UpdateCheck.Available(instance.pinnedPackVersion, targetVersion, CompatChange.Same, UpdatePlan(toAdd = listOf("mods/New.jar")))
        override suspend fun applyUpdate(
            instance: PackInstance,
            targetVersion: String?,
            progress: ((Int, Int, String) -> Unit)?,
        ): UpdateOutcome = UpdateOutcome.AlreadyCurrent
        override suspend fun availableBuilds(instance: PackInstance): List<SmrtManifestBuild> = builds
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
    }

    private fun render(width: Int, height: Int, style: StyleSpec, name: String) {
        startKoin {
            modules(module {
                single<IPackRepository> { FakeRepo(pack) }
                single<PackUpdater> { FakeUpdater }
                single<IMirrorPackClient> { FakeMirror }
                single { ModIconResolver(resolveProjectIcon = { null }) }
            })
        }
        val out = Path.of("build/render", name)
        Files.createDirectories(out.parent)
        val scene = ImageComposeScene(width, height, density = Density(1f)) {
            NxTheme(useDarkTheme = true, style = style) {
                Box(Modifier.fillMaxSize().background(Color(0xFF102030))) {
                    PackVersionsScreen(instanceId = "1", onBack = {})
                }
            }
        }
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
            val png = scene.render(frameNanos).encodeToData(EncodedImageFormat.PNG) ?: error("PNG encode failed")
            Files.write(out, png.bytes)
        } finally {
            scene.close()
        }
        assertTrue(Files.size(out) > 0, "rendered PNG is non-empty")
    }

    @Test fun `renders at FHD under Celestia`() = render(1920, 1080, CelestiaStyle, "pack-versions-fhd-celestia.png")

    @Test fun `renders at FHD under Brut`() = render(1920, 1080, BrutStyle, "pack-versions-fhd-brut.png")

    @Test fun `renders at 2K under Celestia`() = render(2560, 1440, CelestiaStyle, "pack-versions-2k-celestia.png")
}

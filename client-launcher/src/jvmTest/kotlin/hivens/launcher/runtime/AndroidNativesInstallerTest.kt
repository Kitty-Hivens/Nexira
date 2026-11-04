package hivens.launcher.runtime

import hivens.core.net.DigestAlgorithm
import hivens.core.net.Transfer
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AndroidNativesInstallerTest {

    private val dir: Path = Files.createTempDirectory("nexira-natives-test")
    private val lwjglSha = "a".repeat(64)
    private val hostSha = "b".repeat(64)

    private val catalog = AndroidNativesCatalog(
        listOf(
            AndroidNativesRelease(
                "3.3.3",
                listOf(
                    AndroidNativesAsset("lwjgl-3.3.3-natives-android-arm64.jar", AndroidNativesCatalog.assetUrl("3.3.3", "lwjgl-3.3.3-natives-android-arm64.jar"), lwjglSha),
                    AndroidNativesAsset("nexira-host-natives-android-arm64.jar", AndroidNativesCatalog.assetUrl("3.3.3", "nexira-host-natives-android-arm64.jar"), hostSha),
                ),
            ),
        ),
    )

    @Test
    fun `every archive of the release is fetched against its pinned digest and lands under the release's folder`() = runTest {
        val fetched = mutableListOf<Transfer>()
        val installer = AndroidNativesInstaller(dir, catalog) { transfer, _ -> fetched += transfer }
        val said = mutableListOf<String>()

        val jars = installer.natives("3.3.3") { said += it }

        assertEquals(
            listOf(
                dir.resolve("lwjgl-3.3.3/aaaaaaaaaaaaaaaa-lwjgl-3.3.3-natives-android-arm64.jar"),
                dir.resolve("lwjgl-3.3.3/bbbbbbbbbbbbbbbb-nexira-host-natives-android-arm64.jar"),
            ),
            jars,
        )
        assertEquals(jars, fetched.map { it.dest })
        assertEquals(
            listOf("https://github.com/Kitty-Hivens/nexira-android-lwjgl/releases/download/lwjgl-3.3.3/lwjgl-3.3.3-natives-android-arm64.jar",
                   "https://github.com/Kitty-Hivens/nexira-android-lwjgl/releases/download/lwjgl-3.3.3/nexira-host-natives-android-arm64.jar"),
            fetched.map { it.url },
        )
        assertTrue(fetched.all { it.expect?.algorithm == DigestAlgorithm.SHA256 }, "each is held to a SHA-256")
        assertEquals(listOf(lwjglSha, hostSha), fetched.map { it.expect?.value })
        assertEquals(2, said.size, "the download of each is announced")
    }

    @Test
    fun `a new build of an archive under the same name lands under another file name so the natives folder is rebuilt`() = runTest {
        fun catalogOf(sha: String) = AndroidNativesCatalog(
            listOf(AndroidNativesRelease("3.3.3", listOf(AndroidNativesAsset("host.jar", "https://x/host.jar", sha)))),
        )
        val before = AndroidNativesInstaller(dir, catalogOf(lwjglSha)) { _, _ -> }.natives("3.3.3") { }
        val after = AndroidNativesInstaller(dir, catalogOf(hostSha)) { _, _ -> }.natives("3.3.3") { }

        assertTrue(before.single().fileName != after.single().fileName)
    }

    @Test
    fun `a release nobody pinned gives no natives and fetches nothing`() = runTest {
        var fetches = 0
        val installer = AndroidNativesInstaller(dir, catalog) { _, _ -> fetches++ }

        val jars = installer.natives("3.2.2") { }

        assertTrue(jars.isEmpty())
        assertEquals(0, fetches)
    }

    @Test
    fun `a failed download is not swallowed`() = runTest {
        val installer = AndroidNativesInstaller(dir, catalog) { _, _ -> error("connection reset") }

        assertFailsWith<IllegalStateException> { installer.natives("3.3.3") { } }
    }

    @Test
    fun `a pin that is not a lowercase SHA-256 or a file name that is not plain is refused`() {
        assertFailsWith<IllegalArgumentException> { AndroidNativesAsset("a.jar", "https://x", "ABC") }
        assertFailsWith<IllegalArgumentException> { AndroidNativesAsset("a.jar", "https://x", "A".repeat(64)) }
        assertFailsWith<IllegalArgumentException> { AndroidNativesAsset("../a.jar", "https://x", lwjglSha) }
        assertFailsWith<IllegalArgumentException> { AndroidNativesAsset("dir/a.jar", "https://x", lwjglSha) }
        assertFailsWith<IllegalArgumentException> { AndroidNativesAsset("", "https://x", lwjglSha) }
    }

    @Test
    fun `a release holds archives under distinct names and a catalog one release per version`() {
        val one = AndroidNativesAsset("a.jar", "https://x/a", lwjglSha)
        assertFailsWith<IllegalArgumentException> { AndroidNativesRelease("3.3.3", emptyList()) }
        assertFailsWith<IllegalArgumentException> { AndroidNativesRelease("3.3.3", listOf(one, one)) }
        val release = AndroidNativesRelease("3.3.3", listOf(one))
        assertFailsWith<IllegalArgumentException> { AndroidNativesCatalog(listOf(release, release)) }
    }
}

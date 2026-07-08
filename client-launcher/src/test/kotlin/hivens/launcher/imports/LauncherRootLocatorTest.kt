package hivens.launcher.imports

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LauncherRootLocatorTest {

    private val home: Path = Paths.get("/home/tester")

    private fun linux(env: Map<String, String> = emptyMap()) =
        LauncherRootLocator(home = home, env = { env[it] }, osName = "Linux")

    @Test
    fun `modrinth candidates cover native, flatpak and snap`() {
        val c = linux().candidates(ForeignLauncher.Modrinth)
        assertContains(c, home.resolve(".local/share/ModrinthApp"), "native XDG data root")
        assertContains(
            c,
            home.resolve(".var/app/com.modrinth.ModrinthApp/data/ModrinthApp"),
            "Flatpak sandbox data root -- the case a single hardcoded ~/.local/share path would miss",
        )
        assertContains(
            c,
            home.resolve("snap/modrinth/current/.local/share/ModrinthApp"),
            "Snap confinement root",
        )
    }

    @Test
    fun `prism candidates cover flatpak app id`() {
        val c = linux().candidates(ForeignLauncher.Prism)
        assertContains(c, home.resolve(".local/share/PrismLauncher"))
        assertContains(c, home.resolve(".var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher"))
    }

    @Test
    fun `XDG_DATA_HOME override moves the native root`() {
        val c = linux(mapOf("XDG_DATA_HOME" to "/home/tester/xdg")).candidates(ForeignLauncher.Modrinth)
        assertContains(c, Paths.get("/home/tester/xdg/ModrinthApp"))
        assertTrue(c.none { it == home.resolve(".local/share/ModrinthApp") }, "default XDG root replaced by override")
    }

    @Test
    fun `vanilla is dot-minecraft on linux and Application Support on mac`() {
        assertEquals(listOf(home.resolve(".minecraft")), linux().candidates(ForeignLauncher.Vanilla))
        val mac = LauncherRootLocator(home = home, env = { null }, osName = "Mac OS X")
        assertContains(mac.candidates(ForeignLauncher.Vanilla), home.resolve("Library/Application Support/minecraft"))
    }

    @Test
    fun `candidates has no duplicates`() {
        val c = linux().candidates(ForeignLauncher.Modrinth)
        assertEquals(c.size, c.distinct().size, "candidate list must be de-duplicated")
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `existingRoots filters to real directories`() {
        val sandbox = Files.createTempDirectory("locator-test")
        try {
            val real = sandbox.resolve(".minecraft").also { Files.createDirectories(it) }
            val locator = LauncherRootLocator(home = sandbox, env = { null }, osName = "Linux")
            assertEquals(listOf(real), locator.existingRoots(ForeignLauncher.Vanilla))
        } finally {
            runCatching { sandbox.deleteRecursively() }
        }
    }
}

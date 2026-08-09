package hivens.launcher.diag

import hivens.core.diag.ActionRing
import hivens.launcher.platform.PlatformPaths
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The bundle is offered to the user as redacted and is the thing the UI
 * suggests attaching to a public issue, so what it does NOT contain is the
 * contract worth pinning.
 */
class DiagnosticBundleTest {

    private lateinit var home: Path
    private lateinit var paths: PlatformPaths

    @BeforeTest
    fun setup() {
        home = Files.createTempDirectory("nexira-bundle-test-")
        paths = PlatformPaths("Linux", home, { null }, { null })
    }

    @AfterTest
    fun teardown() {
        Files.walk(home).use { walk ->
            walk.sorted(Comparator.reverseOrder()).forEach { entry -> Files.deleteIfExists(entry) }
        }
    }

    private fun entries(zip: Path): Map<String, String> {
        val out = mutableMapOf<String, String>()
        ZipInputStream(Files.newInputStream(zip)).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                out[entry.name] = zin.readBytes().decodeToString()
            }
        }
        return out
    }

    @Test
    fun `a token recorded in the action ring does not reach the bundle`() {
        val token = "a3f19c7b42e08d5169bc0724fe3a81d0"
        ActionRing.record("Auth call failed: accessToken=$token")

        val text = entries(DiagnosticBundle.create(paths))["action-ring.txt"].orEmpty()

        assertFalse(text.contains(token), "the action ring reached the bundle unredacted")
        assertTrue(text.contains("Action ring"), "the entry must still be produced")
    }

    @Test
    fun `system info does not name the account the OS put in its paths`() {
        val text = entries(DiagnosticBundle.create(paths))["system-info.txt"].orEmpty()
        val account = Path.of(System.getProperty("user.home")).fileName?.toString()

        assertTrue(text.contains("user.home"), "the layout lines must still be there")
        if (account != null && account.isNotBlank()) {
            assertFalse(text.contains(account), "the account name leaked through a path line")
        }
    }

    @Test
    fun `system info names the modules recovery switched off`() {
        val off = entries(DiagnosticBundle.create(paths, setOf("skinema", "tray")))["system-info.txt"].orEmpty()
        assertTrue(off.contains("Modules off: skinema, tray"), "a disabled module must be visible to a support reader")

        val none = entries(DiagnosticBundle.create(paths))["system-info.txt"].orEmpty()
        assertTrue(none.contains("Modules off: (none)"), "an all-enabled launcher must say so rather than leave the line blank")
    }

    @Test
    fun `system info names the graphics backend in use`() {
        val text = entries(DiagnosticBundle.create(paths, renderBackend = "SOFTWARE_FAST"))["system-info.txt"].orEmpty()
        assertTrue(
            text.contains("Renderer   : SOFTWARE_FAST"),
            "a software fallback is felt across the machine and must be visible in the bundle",
        )
    }

    @Test
    fun `abbreviateHome folds the home prefix and leaves anything else alone`() {
        assertEquals("~", DiagnosticBundle.abbreviateHome("/home/alice", "/home/alice"))
        assertEquals("~/.local/share/nexira", DiagnosticBundle.abbreviateHome("/home/alice/.local/share/nexira", "/home/alice"))
        // A trailing separator on the home property must not defeat the match.
        assertEquals("~/x", DiagnosticBundle.abbreviateHome("/home/alice/x", "/home/alice/"))
        // A sibling that merely shares the prefix is a different directory.
        assertEquals("/home/alice-backup/x", DiagnosticBundle.abbreviateHome("/home/alice-backup/x", "/home/alice"))
        // Elsewhere on disk there is no account name to fold.
        assertEquals("/mnt/games/nexira", DiagnosticBundle.abbreviateHome("/mnt/games/nexira", "/home/alice"))
    }
}

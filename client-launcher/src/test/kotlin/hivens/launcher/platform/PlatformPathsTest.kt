package hivens.launcher.platform

import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PlatformPathsTest {

    private val home = Paths.get("/home/test")

    @Test
    fun `windows uses LOCALAPPDATA when set`() {
        val paths = PlatformPaths(
            osName = "Windows 11",
            home = home,
            env = { if (it == "LOCALAPPDATA") "C:\\Users\\test\\AppData\\Local" else null }
        )
        assertEquals(Paths.get("C:\\Users\\test\\AppData\\Local", "Nexira"), paths.dataDir)
    }

    @Test
    fun `windows falls back to home AppData Local when env missing`() {
        val paths = PlatformPaths("Windows 10", home, env = { null })
        assertEquals(home.resolve("AppData").resolve("Local").resolve("Nexira"), paths.dataDir)
    }

    @Test
    fun `macos uses Library Application Support`() {
        val paths = PlatformPaths("Mac OS X", home, env = { null })
        assertEquals(
            home.resolve("Library").resolve("Application Support").resolve("Nexira"),
            paths.dataDir
        )
    }

    @Test
    fun `macos detects darwin via os name`() {
        val paths = PlatformPaths("Darwin", home, env = { null })
        assertEquals(
            home.resolve("Library").resolve("Application Support").resolve("Nexira"),
            paths.dataDir
        )
    }

    @Test
    fun `linux uses XDG_DATA_HOME when set`() {
        val paths = PlatformPaths(
            osName = "Linux",
            home = home,
            env = { if (it == "XDG_DATA_HOME") "/home/test/data" else null }
        )
        assertEquals(Paths.get("/home/test/data", "nexira"), paths.dataDir)
    }

    @Test
    fun `linux falls back to local share when XDG missing`() {
        val paths = PlatformPaths("Linux", home, env = { null })
        assertEquals(home.resolve(".local").resolve("share").resolve("nexira"), paths.dataDir)
    }

    @Test
    fun `legacy data dirs expose Aura-era default first then pre-2_3 ~_aura`() {
        val linux = PlatformPaths(
            osName = "Linux",
            home = home,
            env = { if (it == "XDG_DATA_HOME") "/home/test/data" else null }
        )
        assertEquals(
            listOf(Paths.get("/home/test/data", "aura-launcher"), home.resolve(".aura")),
            linux.legacyDataDirs,
        )

        val win = PlatformPaths(
            osName = "Windows 11",
            home = home,
            env = { if (it == "LOCALAPPDATA") "C:\\Users\\test\\AppData\\Local" else null }
        )
        assertEquals(
            listOf(Paths.get("C:\\Users\\test\\AppData\\Local", "AuraLauncher"), home.resolve(".aura")),
            win.legacyDataDirs,
        )

        val mac = PlatformPaths("Mac OS X", home, env = { null })
        assertEquals(
            listOf(
                home.resolve("Library").resolve("Application Support").resolve("AuraLauncher"),
                home.resolve(".aura"),
            ),
            mac.legacyDataDirs,
        )
    }

    @Test
    fun `NEXIRA_DATA_DIR overrides the platform default on every os`() {
        val override = "/mnt/d/nexira"
        val onWindows = PlatformPaths("Windows 11", home) { name ->
            when (name) {
                "NEXIRA_DATA_DIR" -> override
                "LOCALAPPDATA" -> "C:\\Users\\test\\AppData\\Local"
                else -> null
            }
        }
        val onMac = PlatformPaths("Mac OS X", home) {
            if (it == "NEXIRA_DATA_DIR") override else null
        }
        val onLinux = PlatformPaths("Linux", home) {
            when (it) {
                "NEXIRA_DATA_DIR" -> override
                "XDG_DATA_HOME" -> "/should/not/be/used"
                else -> null
            }
        }
        assertEquals(Paths.get(override), onWindows.dataDir)
        assertEquals(Paths.get(override), onMac.dataDir)
        assertEquals(Paths.get(override), onLinux.dataDir)
    }

    @Test
    fun `blank NEXIRA_DATA_DIR falls through to the platform default`() {
        val paths = PlatformPaths("Linux", home) {
            when (it) {
                "NEXIRA_DATA_DIR" -> "   "
                else -> null
            }
        }
        assertEquals(home.resolve(".local").resolve("share").resolve("nexira"), paths.dataDir)
    }

    @Test
    fun `subdirectories are derived from data dir`() {
        val paths = PlatformPaths("Linux", home, env = { null })
        val data = paths.dataDir
        assertEquals(data.resolve("logs"), paths.logsDir)
        assertEquals(data.resolve("crash-reports"), paths.crashDir)
        assertEquals(data.resolve("skin-cache"), paths.skinCacheDir)
        assertEquals(data.resolve("clients"), paths.clientsDir)
        assertEquals(data.resolve("clients").resolve("Industrial"), paths.clientDir("Industrial"))
        assertEquals(data.resolve("libraries"), paths.librariesDir)
        assertEquals(data.resolve("assets"), paths.assetsDir)
    }

    // ── assetDir whitelist gate ────────────────────────────────────────────

    @Test
    fun `clientDir accepts SmartyCraft-style server identifiers`() {
        val paths = PlatformPaths("Linux", home, env = { null })
        // Real server names from upstream -- none should ever fail.
        listOf("Industrial", "RPG", "SkyBlock", "MagicRPG", "Aura.v2", "Server-1_2")
            .forEach { assertEquals(paths.clientsDir.resolve(it), paths.clientDir(it)) }
    }

    @Test
    fun `clientDir rejects parent traversal`() {
        val paths = PlatformPaths("Linux", home, env = { null })
        assertFailsWith<IllegalArgumentException> { paths.clientDir("../../etc") }
        assertFailsWith<IllegalArgumentException> { paths.clientDir("..") }
    }

    @Test
    fun `clientDir rejects path separators (forward and back slash)`() {
        val paths = PlatformPaths("Linux", home, env = { null })
        assertFailsWith<IllegalArgumentException> { paths.clientDir("Industrial/sub") }
        assertFailsWith<IllegalArgumentException> { paths.clientDir("Industrial\\sub") }
    }

    @Test
    fun `clientDir rejects whitespace, NUL, and otherwise weird characters`() {
        val paths = PlatformPaths("Linux", home, env = { null })
        assertFailsWith<IllegalArgumentException> { paths.clientDir("Server With Space") }
        assertFailsWith<IllegalArgumentException> { paths.clientDir("Server NUL") }
        assertFailsWith<IllegalArgumentException> { paths.clientDir($$"$Hostile") }
        assertFailsWith<IllegalArgumentException> { paths.clientDir("") }
    }
}

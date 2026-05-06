package hivens.launcher.platform

import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals

class PlatformPathsTest {

    private val home = Paths.get("/home/test")

    @Test
    fun `windows uses LOCALAPPDATA when set`() {
        val paths = PlatformPaths(
            osName = "Windows 11",
            home = home,
            env = { if (it == "LOCALAPPDATA") "C:\\Users\\test\\AppData\\Local" else null }
        )
        assertEquals(Paths.get("C:\\Users\\test\\AppData\\Local", "AuraLauncher"), paths.dataDir)
    }

    @Test
    fun `windows falls back to home AppData Local when env missing`() {
        val paths = PlatformPaths("Windows 10", home, env = { null })
        assertEquals(home.resolve("AppData").resolve("Local").resolve("AuraLauncher"), paths.dataDir)
    }

    @Test
    fun `macos uses Library Application Support`() {
        val paths = PlatformPaths("Mac OS X", home, env = { null })
        assertEquals(
            home.resolve("Library").resolve("Application Support").resolve("AuraLauncher"),
            paths.dataDir
        )
    }

    @Test
    fun `macos detects darwin via os name`() {
        val paths = PlatformPaths("Darwin", home, env = { null })
        assertEquals(
            home.resolve("Library").resolve("Application Support").resolve("AuraLauncher"),
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
        assertEquals(Paths.get("/home/test/data", "aura-launcher"), paths.dataDir)
    }

    @Test
    fun `linux falls back to local share when XDG missing`() {
        val paths = PlatformPaths("Linux", home, env = { null })
        assertEquals(home.resolve(".local").resolve("share").resolve("aura-launcher"), paths.dataDir)
    }

    @Test
    fun `legacy data dir is dot-aura under home regardless of os`() {
        val win = PlatformPaths("Windows 11", home) { null }
        val mac = PlatformPaths("Mac OS X", home) { null }
        val linux = PlatformPaths("Linux", home) { null }
        assertEquals(home.resolve(".aura"), win.legacyDataDir)
        assertEquals(home.resolve(".aura"), mac.legacyDataDir)
        assertEquals(home.resolve(".aura"), linux.legacyDataDir)
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
    }
}

package hivens.launcher.component

import hivens.core.data.FileData
import hivens.core.data.FileManifest
import hivens.launcher.ManifestProcessorService
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Coverage focused on the wrong-OS-natives filter. Server-side
 * manifests ship every platform's native classifier to every client; the
 * launcher must drop the foreign ones before they reach the JVM, otherwise
 * loading a `*-natives-windows.jar` on Linux either wastes RAM or -- when
 * the JVM tries to dlopen the embedded Mach-O / DLL -- raises
 * UnsatisfiedLinkError at game start.
 */
class ClasspathProviderTest {

    private lateinit var clientRoot: Path

    @BeforeTest
    fun setup() {
        clientRoot = Files.createTempDirectory("aura-classpath-test-")
        Files.createDirectories(clientRoot.resolve("libraries"))
    }

    @AfterTest
    fun teardown() {
        Files.walk(clientRoot).use { walk ->
            walk.sorted(Comparator.reverseOrder()).forEach { entry -> Files.deleteIfExists(entry) }
        }
    }

    private fun touch(rel: String) {
        val f = clientRoot.resolve(rel)
        Files.createDirectories(f.parent)
        Files.createFile(f)
    }

    private fun manifestOf(vararg paths: String): FileManifest {
        // Flat manifest at the libraries level -- ManifestProcessor.flattenManifest
        // walks both the files map and nested directories recursively. Build
        // the directory tree by splitting on '/'.
        data class Node(
            val files: MutableMap<String, FileData> = mutableMapOf(),
            val dirs: MutableMap<String, Node> = mutableMapOf(),
        )
        val root = Node()
        for (path in paths) {
            val parts = path.split('/')
            var here = root
            for (i in 0 until parts.size - 1) here = here.dirs.getOrPut(parts[i]) { Node() }
            here.files[parts.last()] = FileData(md5 = "any", size = 0)
        }
        fun toManifest(n: Node): FileManifest =
            FileManifest(files = n.files, directories = n.dirs.mapValues { toManifest(it.value) })
        return toManifest(root)
    }

    private fun provider(osName: String) =
        ClasspathProvider(ManifestProcessorService(), osName = osName)

    // ── Wrong-OS native JAR filter ──────────────────────────────────────────

    @Test
    fun `Linux build drops Windows and macOS native classifiers`() {
        touch("libraries/lwjgl-3.3.3-natives-linux.jar")
        touch("libraries/lwjgl-3.3.3-natives-windows.jar")
        touch("libraries/lwjgl-3.3.3-natives-macos.jar")
        touch("libraries/lwjgl-3.3.3-natives-osx.jar")  // legacy LWJGL2 Mac classifier

        val cp = provider("Linux").buildClasspath(
            clientRoot = clientRoot,
            manifest = manifestOf(
                "libraries/lwjgl-3.3.3-natives-linux.jar",
                "libraries/lwjgl-3.3.3-natives-windows.jar",
                "libraries/lwjgl-3.3.3-natives-macos.jar",
                "libraries/lwjgl-3.3.3-natives-osx.jar",
            ),
            excludedModules = emptyList(),
        ).split(File.pathSeparator)

        assertTrue(cp.any { it.endsWith("lwjgl-3.3.3-natives-linux.jar") }, "Linux native must be kept")
        assertFalse(cp.any { it.endsWith("natives-windows.jar") })
        assertFalse(cp.any { it.endsWith("natives-macos.jar") })
        assertFalse(cp.any { it.endsWith("natives-osx.jar") })
    }

    @Test
    fun `Windows build drops Linux and macOS native classifiers`() {
        touch("libraries/lwjgl-3.3.3-natives-linux.jar")
        touch("libraries/lwjgl-3.3.3-natives-windows.jar")
        touch("libraries/lwjgl-3.3.3-natives-macos.jar")

        val cp = provider("Windows 11").buildClasspath(
            clientRoot, manifestOf(
                "libraries/lwjgl-3.3.3-natives-linux.jar",
                "libraries/lwjgl-3.3.3-natives-windows.jar",
                "libraries/lwjgl-3.3.3-natives-macos.jar",
            ), emptyList(),
        ).split(File.pathSeparator)

        assertTrue(cp.any { it.endsWith("natives-windows.jar") })
        assertFalse(cp.any { it.endsWith("natives-linux.jar") })
        assertFalse(cp.any { it.endsWith("natives-macos.jar") })
    }

    @Test
    fun `macOS build keeps both modern macos and legacy osx classifiers`() {
        // LWJGL 2 modpacks (1.7.10 / 1.12.2) use the legacy "osx" suffix,
        // LWJGL 3 (1.13+) uses "macos". A macOS user might play both.
        touch("libraries/lwjgl-2.9.4-natives-osx.jar")
        touch("libraries/lwjgl-3.3.3-natives-macos.jar")
        touch("libraries/lwjgl-3.3.3-natives-windows.jar")

        val cp = provider("Mac OS X").buildClasspath(
            clientRoot, manifestOf(
                "libraries/lwjgl-2.9.4-natives-osx.jar",
                "libraries/lwjgl-3.3.3-natives-macos.jar",
                "libraries/lwjgl-3.3.3-natives-windows.jar",
            ), emptyList(),
        ).split(File.pathSeparator)

        assertTrue(cp.any { it.endsWith("natives-osx.jar") })
        assertTrue(cp.any { it.endsWith("natives-macos.jar") })
        assertFalse(cp.any { it.endsWith("natives-windows.jar") })
    }

    @Test
    fun `non-native JARs are unaffected by the filter`() {
        // A jar that happens to contain "natives" in its name but ISN'T a
        // platform classifier (e.g. a mod called "NativeAddons.jar") must
        // pass through. The filter is anchored to the `-natives-<os>` suffix.
        touch("libraries/NativeAddons.jar")
        touch("libraries/some-natives-related-helper.jar")

        val cp = provider("Linux").buildClasspath(
            clientRoot, manifestOf(
                "libraries/NativeAddons.jar",
                "libraries/some-natives-related-helper.jar",
            ), emptyList(),
        ).split(File.pathSeparator)

        assertTrue(cp.any { it.endsWith("NativeAddons.jar") })
        assertTrue(cp.any { it.endsWith("some-natives-related-helper.jar") })
    }

    @Test
    fun `unknown OS keeps every native (safer than dropping all)`() {
        // Defensive: if os.name comes back as something we don't recognize
        // (Plan9 etc.), prefer leaving the classpath wide rather than
        // accidentally stripping the platform's own native and crashing
        // on startup with no recourse.
        touch("libraries/lwjgl-3.3.3-natives-linux.jar")
        touch("libraries/lwjgl-3.3.3-natives-windows.jar")

        val cp = provider("Plan9").buildClasspath(
            clientRoot, manifestOf(
                "libraries/lwjgl-3.3.3-natives-linux.jar",
                "libraries/lwjgl-3.3.3-natives-windows.jar",
            ), emptyList(),
        ).split(File.pathSeparator)

        assertTrue(cp.any { it.endsWith("natives-linux.jar") })
        assertTrue(cp.any { it.endsWith("natives-windows.jar") })
    }
}

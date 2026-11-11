package hivens.launcher.legacy

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * Reads what the retired server path left behind, and reads only.
 *
 * Two questions, deliberately separate. [anyLeftBehind] is asked at every start
 * and must cost nothing: it lists one directory and stops at the first entry.
 * [scan] walks every tree to measure it, which on a real install is gigabytes of
 * `stat` calls, and is asked once when somebody opens the surface that shows the
 * answer.
 */
class RetiredClientScanner(
    private val clientsDir: Path,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val log = LoggerFactory.getLogger(RetiredClientScanner::class.java)

    /** True when `clients/` holds at least one directory. Cheap enough for boot. */
    fun anyLeftBehind(): Boolean = runCatching {
        clientsDir.isDirectory() && Files.newDirectoryStream(clientsDir).use { entries ->
            entries.any { it.isDirectory() }
        }
    }.getOrDefault(false)

    /** How many, without measuring any of them. */
    fun count(): Int = runCatching {
        if (!clientsDir.isDirectory()) 0 else clientsDir.listDirectoryEntries().count { it.isDirectory() }
    }.getOrDefault(0)

    /**
     * Every leftover client, measured and described, newest name order.
     *
     * A tree that cannot be read is reported at zero rather than dropped: it is
     * still there, and a list that quietly omits it would leave the reader
     * believing they had dealt with everything.
     */
    suspend fun scan(): List<RetiredClient> = withContext(io) {
        if (!clientsDir.isDirectory()) return@withContext emptyList()
        clientsDir.listDirectoryEntries()
            .filter { it.isDirectory() }
            .sortedBy { it.name.lowercase() }
            .map { dir ->
                currentCoroutineContext().ensureActive()
                RetiredClient(
                    name = dir.name,
                    dir = dir,
                    sizeBytes = measure(dir),
                    mcVersion = detectMcVersion(dir),
                    loader = detectLoader(dir),
                    modCount = countMods(dir),
                )
            }
    }

    private suspend fun measure(dir: Path): Long = runCatching {
        var total = 0L
        Files.walk(dir).use { tree ->
            for (path in tree) {
                currentCoroutineContext().ensureActive()
                // Symlinks are followed by `size` and would count bytes that live
                // somewhere else, which is not what "reclaim this" means.
                if (path.isRegularFile() && !Files.isSymbolicLink(path)) {
                    total += runCatching { Files.size(path) }.getOrDefault(0L)
                }
            }
        }
        total
    }.getOrElse {
        log.warn("retired client {}: could not be measured", dir.fileName, it)
        0L
    }

    private fun countMods(dir: Path): Int = runCatching {
        val mods = dir.resolve("mods")
        if (!mods.isDirectory()) 0
        else mods.listDirectoryEntries("*.jar").count()
    }.getOrDefault(0)

    /**
     * The Minecraft version, from the most specific thing in the tree that names
     * it.
     *
     * The natives directory leads because it is always version-scoped, where the
     * assets archive sometimes ships as a bare `assets.zip`. Both beat the
     * libraries directory, which a client may not have at all.
     */
    internal fun detectMcVersion(dir: Path): String? =
        firstMatch(dir.resolve("bin"), NATIVES_DIR)
            ?: firstMatch(dir, ASSETS_ZIP)
            ?: firstMatch(dir, LIBRARIES_DIR)

    private fun firstMatch(parent: Path, pattern: Regex): String? = runCatching {
        if (!parent.isDirectory()) return null
        parent.listDirectoryEntries()
            .firstNotNullOfOrNull { pattern.matchEntire(it.name)?.groupValues?.get(1) }
    }.getOrNull()

    /**
     * The loader, from the jars the client would actually boot through.
     *
     * A loader jar beats a log file, and NeoForge is asked about before Forge
     * because its own jars end in the same four letters. The signals genuinely
     * disagree on a real install -- one client has a Fabric loader log left over
     * from an attempt that failed beside a Forge-only mod -- so this is a
     * suggestion for a reader to confirm, and [RetiredClient.loader] is never
     * acted on without one.
     */
    internal fun detectLoader(dir: Path): String? {
        val jars = libraryJarNames(dir) + modJarNames(dir)
        return when {
            jars.any { it.startsWith("neoforge-") || it.startsWith("neoforged") } -> "neoforge"
            jars.any { it.startsWith("forge-") || it.startsWith("minecraftforge") } -> "forge"
            jars.any { it.startsWith("quilt-loader") } -> "quilt"
            jars.any { it.startsWith("fabric-loader") } -> "fabric"
            dir.resolve(".fabric").isDirectory() -> "fabric"
            dir.resolve("fabricloader.log").isRegularFile() -> "fabric"
            else -> null
        }
    }

    /** Jar names under every `libraries*` root, flat or maven-shaped. */
    private fun libraryJarNames(dir: Path): List<String> = runCatching {
        dir.listDirectoryEntries()
            .filter { it.isDirectory() && it.name.startsWith("libraries") }
            .flatMap { root ->
                Files.walk(root, MAVEN_DEPTH).use { tree ->
                    tree.filter { it.isRegularFile() && it.name.endsWith(".jar") }
                        .map { it.name.lowercase() }
                        .toList()
                }
            }
    }.getOrDefault(emptyList())

    private fun modJarNames(dir: Path): List<String> = runCatching {
        val mods = dir.resolve("mods")
        if (!mods.isDirectory()) emptyList()
        else mods.listDirectoryEntries("*.jar").map { it.name.lowercase() }
    }.getOrDefault(emptyList())

    private companion object {
        val NATIVES_DIR = Regex("""natives-([0-9][0-9.]*)""")
        val ASSETS_ZIP = Regex("""assets-([0-9][0-9.]*)\.zip""")
        val LIBRARIES_DIR = Regex("""libraries-([0-9][0-9.]*)""")

        /**
         * How deep a loader jar can sit under `libraries/`. A flat SmartyCraft
         * root holds them at depth one; a maven-shaped one at `group/artifact/
         * version/jar`. Bounded rather than unbounded because this runs on every
         * client and the answer is never deeper than that.
         */
        const val MAVEN_DEPTH = 5
    }
}

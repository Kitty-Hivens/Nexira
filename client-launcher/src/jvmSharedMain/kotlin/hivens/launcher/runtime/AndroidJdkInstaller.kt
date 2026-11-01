package hivens.launcher.runtime

import hivens.core.io.UnpackBudget
import hivens.core.io.UnpackLimits
import hivens.core.io.deleteTree
import hivens.core.net.Digest
import hivens.core.net.DigestAlgorithm
import hivens.core.net.Transfer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/**
 * Puts a pinned Java build for Android where the host can load it from.
 *
 * The archive is checked by the transfer engine against the pinned digest, so what
 * gets unpacked is the build the launcher was released with. Unpacking goes to a
 * sibling directory and the finished tree is swapped in, the way the desktop runtime
 * is, so a download that dies or a disk that fills leaves the runtime that was there.
 *
 * [fetch] is the transfer engine's `fetch`, passed as a function so the installer can
 * be tested without a network.
 */
class AndroidJdkInstaller(
    runtimesDir: Path,
    private val fetch: suspend (Transfer, (done: Long, total: Long) -> Unit) -> Unit,
) {
    private val log = LoggerFactory.getLogger(AndroidJdkInstaller::class.java)
    private val root: Path = runtimesDir
    private val downloads: Path = runtimesDir.resolve(".downloads")

    /** The Java home of [release], installed or just fetched. */
    suspend fun ensure(release: AndroidJdkRelease, onProgress: (String) -> Unit = {}): Path = withContext(Dispatchers.IO) {
        val home = root.resolve(folderName(release.major))
        lockFor(home).withLock { provision(release, home, onProgress) }
    }

    private suspend fun provision(release: AndroidJdkRelease, home: Path, onProgress: (String) -> Unit): Path {
        recoverInterruptedSwap(home)
        if (installedVersion(home) == release.version) return home

        onProgress("Downloading Java ${release.major}...")
        val archive = downloads.resolve("${folderName(release.major)}.zip")
        var lastPct = -1
        fetch(Transfer(url = release.url, dest = archive, expect = Digest(DigestAlgorithm.SHA256, release.sha256))) { done, total ->
            if (total > 0) {
                val pct = ((done * 100) / total).toInt().coerceIn(0, 100)
                if (pct >= lastPct + 5 || pct == 100) {
                    lastPct = pct
                    onProgress("Downloading Java ${release.major}: $pct%")
                }
            }
        }

        onProgress("Unpacking Java ${release.major}...")
        install(archive, home, release)
        Files.deleteIfExists(archive)
        onProgress("Java ${release.major} ready")
        return home
    }

    private fun install(archive: Path, home: Path, release: AndroidJdkRelease) {
        val incoming = home.resolveSibling("${home.fileName}.incoming")
        val previous = previousOf(home)
        deleteTree(incoming)
        recoverInterruptedSwap(home)
        deleteTree(previous)
        Files.createDirectories(incoming)
        try {
            unzip(archive, incoming)
            val unpacked = runtimeIn(incoming)
                ?: throw IOException("The archive for Java ${release.major} holds no runtime")
            val found = versionOf(unpacked)
            if (found != release.version) {
                throw IOException("The archive for Java ${release.major} is version ${found ?: "unknown"}, expected ${release.version}")
            }
            if (Files.exists(home)) Files.move(home, previous)
            Files.move(unpacked, home)
            deleteTree(previous)
            deleteTree(incoming)
        } catch (e: Exception) {
            runCatching { deleteTree(incoming) }
            if (!Files.exists(home) && Files.exists(previous)) runCatching { Files.move(previous, home) }
            runCatching { deleteTree(previous) }
            throw e
        }
    }

    /** The directory that holds the runtime: the archive's top-level folder, or the root when it has none. */
    private fun runtimeIn(dir: Path): Path? {
        if (hasRuntime(dir)) return dir
        return Files.list(dir).use { children ->
            children.filter { Files.isDirectory(it) && hasRuntime(it) }.findFirst().orElse(null)
        }
    }

    private fun unzip(archive: Path, dest: Path) {
        val budget = UnpackBudget(UnpackLimits.RUNTIME, archive.fileName.toString())
        ZipFile(archive.toFile()).use { zip ->
            for (entry in zip.entries()) {
                val target = dest.resolve(entry.name).normalize()
                if (!target.startsWith(dest)) throw IOException("Zip entry is outside of the target dir: ${entry.name}")
                if (entry.isDirectory) {
                    Files.createDirectories(target)
                } else {
                    Files.createDirectories(target.parent)
                    budget.entry()
                    zip.getInputStream(entry).use { budget.copyTo(it, target) }
                }
            }
        }
    }

    /** Puts back an install that a swap left under `.previous`. */
    private fun recoverInterruptedSwap(home: Path) {
        val previous = previousOf(home)
        if (Files.exists(home) || !Files.exists(previous)) return
        runCatching { Files.move(previous, home) }
            .onSuccess { log.info("Restored the Java runtime an unfinished install left at {}", previous) }
            .onFailure { log.warn("Could not restore the Java runtime left at {}", previous, it) }
    }

    private fun previousOf(home: Path): Path = home.resolveSibling("${home.fileName}.previous")

    /** The version of the runtime installed at [home], or null when there is none that could be loaded. */
    private fun installedVersion(home: Path): String? = if (hasRuntime(home)) versionOf(home) else null

    private fun hasRuntime(dir: Path): Boolean = Files.isRegularFile(dir.resolve(LIBJVM))

    private fun versionOf(home: Path): String? {
        val release = home.resolve("release")
        if (!Files.isRegularFile(release)) return null
        return Files.readAllLines(release).firstNotNullOfOrNull { line ->
            line.removePrefix("JAVA_VERSION=").takeIf { it != line }?.trim('"')
        }
    }

    companion object {
        private const val LIBJVM = "lib/server/libjvm.so"

        /** Named as the desktop names the same target, so a runtime is found under one scheme. */
        fun folderName(major: Int): String = "java-$major-linux-arm64-bionic"

        private val locks = ConcurrentHashMap<Path, Mutex>()

        private fun lockFor(dir: Path): Mutex = locks.computeIfAbsent(dir.toAbsolutePath().normalize()) { Mutex() }
    }
}

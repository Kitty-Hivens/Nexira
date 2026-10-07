package hivens.launcher.runtime.loader

import hivens.core.io.AtomicFiles
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Where a loader's source for one named version is kept once fetched: the profile
 * a meta service answered with, or the installer a release page served.
 *
 * A named loader version is fixed upstream, so what was fetched for it once is
 * what would be fetched again. Fetched on every launch instead, a pack whose
 * runtime was already on disk could not start without the network, which is the
 * one thing a warm relaunch is meant not to need. Only named versions are kept:
 * "the latest" is a question whose answer moves, and asking it is what the network
 * is for.
 *
 * A null root keeps nothing, which is what the tests that stand a resolver up
 * without a disk ask for.
 */
internal class LoaderSourceCache(private val root: Path?) {
    private val log = LoggerFactory.getLogger(LoaderSourceCache::class.java)

    /** The file [name] for [loaderId] at [key], or null when nothing is kept. */
    fun fileFor(loaderId: String, key: String, name: String): Path? =
        root?.resolve(loaderId)?.resolve(key.replace(UNSAFE, "_"))?.resolve(name)

    fun readText(file: Path?): String? {
        if (file == null || !Files.isRegularFile(file)) return null
        return runCatching { Files.readString(file) }.getOrNull()
    }

    fun writeText(file: Path?, text: String) {
        file ?: return
        runCatching {
            Files.createDirectories(file.parent)
            AtomicFiles.writeString(file, text)
        }.onFailure { log.warn("loader cache: could not keep {}", file, it) }
    }

    /** Drops an entry that turned out unreadable, so the next attempt fetches it again. */
    fun discard(file: Path?) {
        file ?: return
        runCatching { Files.deleteIfExists(file) }
    }

    private companion object {
        val UNSAFE = Regex("[^A-Za-z0-9._-]")
    }
}

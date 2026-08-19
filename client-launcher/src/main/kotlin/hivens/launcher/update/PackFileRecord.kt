package hivens.launcher.update

import hivens.launcher.util.sha1Of
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * One file the pack placed into an instance, as it was at the moment we placed it.
 *
 * [crc32] is carried only for files that came out of the pack archive. A zip's
 * central directory names the CRC of every entry, so a later version's archive
 * can be compared against this without fetching any entry bodies. It is a change
 * signal and nothing more: four bytes, not cryptographic, trivially collided on
 * purpose. What verifies is [sha1].
 */
internal data class PackFileEntry(
    val sha1: String,
    val size: Long,
    val mtimeMs: Long,
    val crc32: Long?,
)

/**
 * What the pack put into an instance, written at install and read at update.
 *
 * Without it an update cannot tell a file the previous version shipped from one
 * the player added, and the only safe answer to that ambiguity is to reinstall
 * the instance -- which takes the player's worlds and configs with it. With it,
 * an update removes what the pack retired, replaces what it changed, skips what
 * it did not, and leaves everything else alone.
 *
 * [size] and [mtimeMs] are here so a later update can find the files the player
 * has touched with `stat` instead of re-hashing hundreds of megabytes.
 *
 * Deliberately a plain text file next to the instance, in the same dot-prefixed
 * convention as its neighbours: it stays readable when something has gone wrong,
 * and a corrupt one degrades to "we know nothing" rather than to a crash.
 */
internal object PackFileRecord {

    const val FILE_NAME = ".nexira-pack"

    private val log = LoggerFactory.getLogger(PackFileRecord::class.java)

    /**
     * Takes stock of [clientDir] after an install.
     *
     * A walk rather than a tally kept during the install, because the directory
     * is the truth: it already reflects entries the installer skipped as
     * client-unsupported, and it cannot drift from what was actually written.
     * This is correct only immediately after an install creates the directory --
     * the runtime provisioner writes to the shared roots and is not even given
     * this path, so nothing else has been here yet.
     *
     * [publishedSha1] supplies hashes the pack index already pins, which the
     * download was verified against; the rest are hashed here, and in practice
     * that means the overrides, which are configs.
     */
    fun capture(
        clientDir: Path,
        publishedSha1: Map<String, String> = emptyMap(),
        archiveCrc32: Map<String, Long> = emptyMap(),
    ): Map<String, PackFileEntry> {
        val root = clientDir.normalize()
        val out = sortedMapOf<String, PackFileEntry>()
        Files.walk(root).use { stream ->
            stream.filter { Files.isRegularFile(it) }.forEach { file ->
                val rel = root.relativize(file).joinToString("/")
                if (rel == FILE_NAME) return@forEach
                if (rel.any { it == '\n' || it == '\r' }) {
                    log.warn("pack record: skipping '{}' -- a newline in a path cannot be recorded line by line", rel)
                    return@forEach
                }
                val entry = runCatching {
                    PackFileEntry(
                        sha1 = publishedSha1[rel] ?: sha1Of(file),
                        size = Files.size(file),
                        mtimeMs = Files.getLastModifiedTime(file).toMillis(),
                        crc32 = archiveCrc32[rel],
                    )
                }.getOrElse {
                    log.warn("pack record: could not read {}", rel, it)
                    return@forEach
                }
                out[rel] = entry
            }
        }
        return out
    }

    /** Sorted, one file per line, so the thing diffs and reads by eye. */
    fun write(clientDir: Path, entries: Map<String, PackFileEntry>) {
        val body = entries.entries.sortedBy { it.key }.joinToString("\n") { (path, e) ->
            "${e.sha1} ${e.size} ${e.mtimeMs} ${e.crc32?.toString() ?: "-"} $path"
        }
        runCatching { Files.writeString(clientDir.resolve(FILE_NAME), if (body.isEmpty()) "" else body + "\n") }
            .onFailure { log.warn("pack record: could not write it to {}", clientDir, it) }
            .onSuccess { log.info("pack record: {} file(s) recorded for {}", entries.size, clientDir.fileName) }
    }

    /**
     * Reads the record back. An absent, unreadable or damaged file reads as
     * empty: an update that believes it placed nothing keeps its hands off
     * everything, which is the safe direction to fail in.
     */
    fun read(clientDir: Path): Map<String, PackFileEntry> {
        val file = clientDir.resolve(FILE_NAME)
        if (!Files.isRegularFile(file)) return emptyMap()
        return runCatching {
            buildMap {
                Files.readAllLines(file).forEach { line ->
                    if (line.isBlank()) return@forEach
                    val parts = line.split(" ", limit = 5)
                    if (parts.size < 5) {
                        log.warn("pack record: dropping a malformed line in {}", file)
                        return@forEach
                    }
                    val size = parts[1].toLongOrNull()
                    val mtime = parts[2].toLongOrNull()
                    if (size == null || mtime == null) {
                        log.warn("pack record: dropping a line with unreadable numbers in {}", file)
                        return@forEach
                    }
                    put(parts[4], PackFileEntry(parts[0], size, mtime, parts[3].toLongOrNull()))
                }
            }
        }.getOrElse {
            log.warn("pack record: unreadable at {}; treating the instance as unknown", file, it)
            emptyMap()
        }
    }
}

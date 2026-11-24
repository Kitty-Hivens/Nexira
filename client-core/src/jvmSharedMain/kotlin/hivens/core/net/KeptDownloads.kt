package hivens.core.net

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.toJavaDuration

/**
 * What an install that did not finish had already downloaded, so the next one starts
 * from there.
 *
 * An install fetches into an instance directory of its own, which is removed when the
 * install fails or is cancelled, and on a bad line every attempt began again from
 * nothing. A directory that is [watch]ed has its downloads moved here before it goes
 * ([settle]), and whatever asks for the same bytes next is given them back ([claim]):
 * the next attempt, or another pack that ships the same file.
 *
 * Only a download with a pinned digest is kept, under that digest, and what is given
 * back is still checked against it by whoever asked. A part of one is kept with its
 * journal and resumed.
 *
 * Nothing stays for good: what nobody came back for in [maxAge] goes, and past
 * [maxBytes] the oldest go first.
 */
class KeptDownloads(
    private val dir: Path,
    private val maxAge: Duration = 7.days,
    private val maxBytes: Long = 4L * 1024 * 1024 * 1024,
    private val clock: () -> Instant = Instant::now,
) {
    private val log = LoggerFactory.getLogger(KeptDownloads::class.java)

    /** What each watched directory asked for, by where it goes. */
    private val watched = ConcurrentHashMap<Path, ConcurrentHashMap<Path, Digest>>()

    /** From here on, what is downloaded into [root] is worth keeping if [root] is not. */
    fun watch(root: Path) {
        watched.putIfAbsent(root.toAbsolutePath().normalize(), ConcurrentHashMap())
    }

    /**
     * Notes that [dest] is to hold the bytes of [expect], and puts there what is kept of
     * them, whole or in part. Does nothing for a destination outside every watched
     * directory, or where something already is.
     */
    fun claim(dest: Path, expect: Digest) {
        val at = dest.toAbsolutePath().normalize()
        val asked = watched.entries.firstOrNull { (root, _) -> at.startsWith(root) }?.value ?: return
        val name = nameOf(expect) ?: return
        asked[at] = expect
        val partial = TransferStaging.partialOf(at)
        if (Files.exists(at) || Files.exists(partial)) return
        runCatching {
            val whole = dir.resolve(name)
            val part = TransferStaging.partialOf(whole)
            when {
                Files.isRegularFile(whole) -> move(whole, at)
                Files.isRegularFile(part) -> {
                    val journal = TransferStaging.journalOf(part)
                    if (Files.isRegularFile(journal)) move(journal, TransferStaging.journalOf(partial))
                    move(part, partial)
                }
            }
        }.onFailure { log.warn("A kept download could not be put back at {}, it is fetched again", at, it) }
    }

    /**
     * The end of [root]'s install. With [keep] its downloads are moved out of it, and how
     * many whole files that was is answered. Without, they are [root]'s own from now on.
     */
    fun settle(root: Path, keep: Boolean): Int {
        val asked = watched.remove(root.toAbsolutePath().normalize()) ?: return 0
        if (!keep) return 0
        var whole = 0
        for ((dest, expect) in asked) {
            val name = nameOf(expect) ?: continue
            runCatching {
                val partial = TransferStaging.partialOf(dest)
                when {
                    Files.isRegularFile(dest) -> {
                        store(dest, name)
                        whole++
                    }
                    Files.isRegularFile(partial) -> {
                        val journal = TransferStaging.journalOf(partial)
                        if (Files.isRegularFile(journal)) store(journal, "$name${TransferStaging.PARTIAL_SUFFIX}${TransferStaging.JOURNAL_SUFFIX}")
                        store(partial, "$name${TransferStaging.PARTIAL_SUFFIX}")
                    }
                }
            }.onFailure { log.warn("A download could not be kept: {}", dest, it) }
        }
        prune()
        return whole
    }

    /** Drops what is older than [maxAge], and the oldest of the rest past [maxBytes]. */
    fun prune() {
        if (!Files.isDirectory(dir)) return
        runCatching {
            val oldest = clock().minus(maxAge.toJavaDuration())
            val files = Files.list(dir).use { all -> all.filter { Files.isRegularFile(it) }.toList() }
                .map { Triple(it, Files.getLastModifiedTime(it).toInstant(), Files.size(it)) }
                .sortedByDescending { it.second }
            var held = 0L
            for ((file, kept, size) in files) {
                held += size
                if (kept.isBefore(oldest) || held > maxBytes) Files.deleteIfExists(file)
            }
        }.onFailure { log.warn("Kept downloads at {} could not be tidied", dir, it) }
    }

    private fun store(file: Path, name: String) {
        val kept = dir.resolve(name)
        move(file, kept)
        Files.setLastModifiedTime(kept, FileTime.from(clock()))
    }

    private fun move(from: Path, to: Path) {
        to.parent?.let { Files.createDirectories(it) }
        Files.move(from, to, StandardCopyOption.REPLACE_EXISTING)
    }

    /** The file name of [digest] here, or null where it is not one. It comes from a manifest, so it is not trusted as a path. */
    private fun nameOf(digest: Digest): String? =
        digest.value.lowercase().takeIf { HEX.matches(it) }?.let { "${digest.algorithm.name.lowercase()}-$it" }

    private companion object {
        val HEX = Regex("[0-9a-f]{32,128}")
    }
}

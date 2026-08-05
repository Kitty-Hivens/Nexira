package hivens.core.io

import org.slf4j.LoggerFactory
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Atomic, crash-durable file writes via the tmp-then-fsync-then-rename pattern,
 * extracted from the four places that had it copy-pasted (JsonPackRepository,
 * LayoutGraphRepository, JsonServerListCacheStore, ManifestCache).
 *
 * Sequence: write `<file>.tmp`, fsync its bytes to stable storage, then
 * `Files.move(ATOMIC_MOVE)`, then fsync the parent directory so the rename
 * itself survives a power loss. ATOMIC_MOVE alone makes only the rename's
 * metadata atomic; without the fsyncs a crash can persist the rename while the
 * file's contents are still in the page cache, leaving a zero-length / garbage
 * file. The directory fsync is best-effort: opening a directory as a channel is
 * unsupported on Windows (and some non-POSIX filesystems), where it is skipped
 * -- the rename is still atomic there, just not power-loss-durable.
 *
 * Filesystems that cannot do atomic rename (FAT32 / exFAT on removable drives,
 * some SMB shares) throw [java.nio.file.AtomicMoveNotSupportedException]; we fall
 * back to a non-atomic REPLACE_EXISTING move and WARN.
 *
 * Synchronous on purpose (no dispatcher): callers already pick their thread, and
 * some write from contexts that must not suspend (e.g. a JVM shutdown-hook flush).
 */
object AtomicFiles {
    private val log = LoggerFactory.getLogger(AtomicFiles::class.java)

    /**
     * Serialises writers of the same file within this process.
     *
     * The temp file is named after its destination, so two threads publishing one
     * file share it: both write, one renames the other's bytes, and the loser
     * renames a path that is no longer there. Nothing is torn -- each write is
     * whole -- but one caller's write is lost and the other throws.
     *
     * Striped rather than a lock per path: a map would otherwise hold an entry per
     * file the process ever writes, and a download journal writes one per block.
     * Two unrelated files sharing a stripe wait on a rename, which is microseconds.
     */
    private val stripes = Array(64) { Any() }

    private fun stripeFor(file: Path): Any =
        stripes[(file.normalize().hashCode() and 0x7fffffff) % stripes.size]

    fun writeString(file: Path, content: String) {
        write(file) { tmp -> Files.writeString(tmp, content) }
    }

    fun writeBytes(file: Path, content: ByteArray) {
        write(file) { tmp -> Files.write(tmp, content) }
    }

    private inline fun write(file: Path, writeTmp: (Path) -> Unit) = synchronized(stripeFor(file)) {
        val dir = file.parent
        dir?.let { Files.createDirectories(it) }
        val tmp = file.resolveSibling("${file.fileName}.tmp")
        writeTmp(tmp)
        fsyncFile(tmp)
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            log.warn(
                "Filesystem at {} does not support ATOMIC_MOVE; falling back to " +
                    "non-atomic rename. A crash mid-rename can lose this file. Move the " +
                    "data directory to a filesystem with atomic rename (ext4, NTFS, APFS) " +
                    "for full durability.",
                file.parent,
            )
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
        }
        dir?.let { fsyncDir(it) }
    }

    /** Flush the temp file's bytes to stable storage before the rename publishes it. */
    private fun fsyncFile(file: Path) {
        runCatching {
            FileChannel.open(file, StandardOpenOption.WRITE).use { it.force(true) }
        }
    }

    /**
     * Flush the directory entry so the rename itself survives a power loss.
     * Best-effort: opening a directory channel throws on Windows (and some
     * non-POSIX filesystems), where this is a no-op.
     */
    private fun fsyncDir(dir: Path) {
        runCatching {
            FileChannel.open(dir, StandardOpenOption.READ).use { it.force(true) }
        }
    }
}

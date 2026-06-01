package hivens.core.io

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Atomic file writes via the tmp-then-rename pattern, extracted from the four
 * places that had it copy-pasted (JsonPackRepository, LayoutGraphRepository,
 * JsonServerListCacheStore, ManifestCache).
 *
 * Write to `<file>.tmp`, then `Files.move(ATOMIC_MOVE)` so a crash leaves either
 * the old or the new file, never a half-written one. Filesystems that cannot do
 * atomic rename (FAT32 / exFAT on removable drives, some SMB shares) throw
 * [java.nio.file.AtomicMoveNotSupportedException]; we fall back to a non-atomic
 * REPLACE_EXISTING move and WARN, since a power loss mid-rename can then lose the
 * file -- the caller's data is only as durable as the chosen filesystem.
 *
 * Synchronous on purpose (no dispatcher): callers already pick their thread, and
 * some write from contexts that must not suspend (e.g. a JVM shutdown-hook flush).
 */
object AtomicFiles {
    private val log = LoggerFactory.getLogger(AtomicFiles::class.java)

    fun writeString(file: Path, content: String) {
        write(file) { tmp -> Files.writeString(tmp, content) }
    }

    fun writeBytes(file: Path, content: ByteArray) {
        write(file) { tmp -> Files.write(tmp, content) }
    }

    private inline fun write(file: Path, writeTmp: (Path) -> Unit) {
        file.parent?.let { Files.createDirectories(it) }
        val tmp = file.resolveSibling("${file.fileName}.tmp")
        writeTmp(tmp)
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
    }
}

package hivens.core.io

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("PrivateFiles")

/** Owner read/write, nothing for group or other. */
private val OWNER_ONLY: Set<PosixFilePermission> =
    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)

/**
 * Writes [content] to [path] readable only by its owner, atomically.
 *
 * A credential file inherits the process umask otherwise, which on a typical
 * Linux desktop leaves it world-readable. That matters less on a
 * single-account machine and quite a lot on a shared one, in a container with
 * a mounted home, or in a backup that preserves modes.
 *
 * The permissions are applied at CREATION, not after the bytes land: setting
 * them afterwards leaves a window where the file already holds the content at
 * the umask's discretion. The content goes to an owner-only temp file that
 * [AtomicFiles] renames over [path], so a reader sees the old file or the new
 * one and never a truncated one. A kill in the middle of an in-place write
 * used to leave an empty credentials file, which reads as no accounts at all.
 *
 * POSIX only for the mode. On Windows the file inherits the parent directory's
 * ACL, which for a per-user application-data directory is already
 * owner-scoped, and there is no portable mode to set instead -- so the write
 * there is atomic and nothing more, rather than a pretence.
 */
@Throws(IOException::class)
fun writeStringOwnerOnly(path: Path, content: String) {
    val posix = path.fileSystem.supportedFileAttributeViews().contains("posix")
    if (!posix) {
        AtomicFiles.writeString(path, content)
        return
    }
    AtomicFiles.writeStringCreatedBy(path, content) { tmp ->
        runCatching { Files.createFile(tmp, PosixFilePermissions.asFileAttribute(OWNER_ONLY)) }
            .onFailure { log.debug("could not pre-create {} owner-only: {}", tmp, it.message) }
    }
    // A filesystem that refused the mode at creation still gets the attempt.
    restrictToOwner(path)
}

/**
 * Best-effort owner-only mode on an existing file. Failure is logged, never
 * thrown: a file that could not be tightened is still a file the caller needs
 * to have written, and the alternative is failing a sign-in over a mode bit.
 */
fun restrictToOwner(path: Path) {
    if (!path.fileSystem.supportedFileAttributeViews().contains("posix")) return
    runCatching { Files.setPosixFilePermissions(path, OWNER_ONLY) }
        .onFailure { log.warn("Could not restrict permissions on {}: {}", path, it.message) }
}

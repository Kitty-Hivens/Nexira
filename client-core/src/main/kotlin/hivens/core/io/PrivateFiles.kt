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
 * Writes [content] to [path] readable only by its owner.
 *
 * A credential file inherits the process umask otherwise, which on a typical
 * Linux desktop leaves it world-readable. That matters less on a
 * single-account machine and quite a lot on a shared one, in a container with
 * a mounted home, or in a backup that preserves modes.
 *
 * The permissions are applied at CREATION, not after the bytes land: setting
 * them afterwards leaves a window where the file already holds the content at
 * the umask's discretion. They are re-applied afterwards as well, since an
 * existing file keeps whatever mode it already had.
 *
 * POSIX only. On Windows the file inherits the parent directory's ACL, which
 * for a per-user application-data directory is already owner-scoped, and there
 * is no portable mode to set instead -- so this is a no-op there rather than a
 * pretence.
 */
@Throws(IOException::class)
fun writeStringOwnerOnly(path: Path, content: String) {
    path.parent?.let { Files.createDirectories(it) }
    val posix = path.fileSystem.supportedFileAttributeViews().contains("posix")

    if (posix && !Files.exists(path)) {
        runCatching { Files.createFile(path, PosixFilePermissions.asFileAttribute(OWNER_ONLY)) }
            .onFailure { log.debug("could not pre-create {} owner-only: {}", path, it.message) }
    }
    Files.writeString(path, content)
    if (posix) restrictToOwner(path)
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

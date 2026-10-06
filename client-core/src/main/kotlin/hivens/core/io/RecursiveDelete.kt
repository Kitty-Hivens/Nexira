package hivens.core.io

import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Deletes [path] and everything under it, treating a symlink as a single entry
 * rather than a door.
 *
 * `kotlin.io.File.deleteRecursively` walks with `listFiles()`, which resolves a
 * link before listing, so a symlinked directory inside the tree has its TARGET
 * emptied while the link itself survives. In a launcher that recursively
 * removes instance directories, snapshots and pack installs, that turns an
 * ordinary convenience -- a user linking `mods/` or a world save at a shared
 * folder or a second drive -- into silent data loss outside the directory the
 * user asked to remove.
 *
 * [Files.walkFileTree] does not follow links unless asked, so a link is
 * visited as a file and unlinked.
 *
 * A file Windows marks read-only refuses deletion until the mark is cleared, so a
 * refused delete clears it and tries once more. Elsewhere there is no such
 * attribute and the retry never runs.
 *
 * Missing [path] is a no-op. Anything that cannot be removed propagates:
 * callers that would rather continue already wrap this in `runCatching`, and
 * swallowing here would hide a half-deleted tree from the ones that would not.
 */
@Throws(IOException::class)
fun deleteTree(path: Path) {
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
    Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            try {
                Files.deleteIfExists(file)
            } catch (e: AccessDeniedException) {
                // Not a read-only mark, or not a filesystem that has one: the first
                // refusal is the answer.
                runCatching { Files.setAttribute(file, "dos:readonly", false, LinkOption.NOFOLLOW_LINKS) }
                    .getOrElse { throw e }
                Files.deleteIfExists(file)
            }
            return FileVisitResult.CONTINUE
        }

        override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
            Files.deleteIfExists(dir)
            return FileVisitResult.CONTINUE
        }
    })
}

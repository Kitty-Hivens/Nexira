package hivens.core.io

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
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
 * Missing [path] is a no-op. Anything that cannot be removed propagates:
 * callers that would rather continue already wrap this in `runCatching`, and
 * swallowing here would hide a half-deleted tree from the ones that would not.
 */
@Throws(IOException::class)
fun deleteTree(path: Path) {
    if (!Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
    Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            Files.deleteIfExists(file)
            return FileVisitResult.CONTINUE
        }

        override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
            Files.deleteIfExists(dir)
            return FileVisitResult.CONTINUE
        }
    })
}

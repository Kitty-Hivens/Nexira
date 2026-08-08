package hivens.launcher.util

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/**
 * What is in a directory at one instant: a name, and enough about each entry to
 * notice it being replaced in place.
 *
 * Deliberately shallow and hash-free. Two callers need to know only whether
 * something changed -- the launch guard, which then asks the sync service to look
 * properly, and the content watch, which then rescans. Hashing on every tick would
 * cost more than either does with the answer.
 *
 * Not a `WatchService`. The JDK only has a native watcher on some platforms; on the
 * rest it degrades to polling on an interval of its own choosing, measured in
 * seconds, and on Wayland a stalled window can starve the callbacks it depends on.
 * A readdir plus a stat per entry behaves the same everywhere.
 */
object DirectorySnapshot {

    /** Size and last-modified time, enough to see a file swapped for another. */
    data class Mark(val size: Long, val modifiedMillis: Long) {
        companion object {
            /**
             * For an entry that cannot be read. A file removed between the readdir
             * and the stat is exactly the race being watched for, so it reads as a
             * change rather than as a failure.
             */
            val UNREADABLE = Mark(-1L, -1L)
        }
    }

    /**
     * The entries of [dir], or an empty map when it cannot be read.
     *
     * A missing or unreadable directory is not an error here: an instance may not
     * have a `shaderpacks/` yet, and a transient read failure must not end a watch
     * that is meant to run for as long as a screen is open.
     */
    fun of(dir: Path): Map<String, Mark> = runCatching {
        Files.newDirectoryStream(dir).use { entries ->
            entries.associate { entry -> entry.fileName.toString() to mark(entry) }
        }
    }.getOrElse { emptyMap() }

    /** The entries of every directory in [dirs], keyed by `<dir>/<name>`. */
    fun ofAll(dirs: List<Path>): Map<String, Mark> {
        val all = LinkedHashMap<String, Mark>()
        for (dir in dirs) {
            for ((name, mark) in of(dir)) all["${dir.fileName}/$name"] = mark
        }
        return all
    }

    private fun mark(entry: Path): Mark =
        runCatching { Files.readAttributes(entry, BasicFileAttributes::class.java) }
            .map { Mark(it.size(), it.lastModifiedTime().toMillis()) }
            .getOrDefault(Mark.UNREADABLE)
}

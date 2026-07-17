package hivens.core.io

import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * A zip opened for reading a few named entries. Abstracts the platform-specific
 * open strategy: [readEntry] returns an entry's bytes (or null if absent) and
 * [entryNames] lists the non-directory entry names for a pattern search.
 */
interface SharedZip : Closeable {
    fun readEntry(name: String): ByteArray?
    fun entryNames(): Sequence<String>
}

private val isWindows: Boolean =
    System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

/**
 * Opens [path] as a zip for reading.
 *
 * On Windows the reader is a NIO-channel-backed Commons Compress [ZipFile]: a NIO
 * channel carries FILE_SHARE_DELETE, so holding it open does NOT block a concurrent
 * rename/delete of the archive -- an optional-mod toggle, a sync, or an antivirus
 * pass proceeds while we read. `java.util.zip.ZipFile` omits delete-sharing and locks
 * the file for the whole read, which raced a mod toggle against the content scanner
 * on Windows.
 *
 * On POSIX an open file renames freely (the race never existed -- POSIX ignores the
 * sharing distinction), so this uses the native `java.util.zip.ZipFile`, which is
 * mmap/zlib-backed and roughly an order of magnitude faster than the pure-Java channel
 * reader. That matters because the content scanner opens every jar in a pack (twice:
 * metadata + icon); the channel reader on POSIX made opening a big pack's Content tab
 * burn seconds of CPU.
 */
fun openSharedZip(path: Path): SharedZip =
    if (isWindows) CommonsSharedZip(path) else NativeSharedZip(path)

/** POSIX-fast reader over the native zip implementation. */
private class NativeSharedZip(path: Path) : SharedZip {
    private val zip = java.util.zip.ZipFile(path.toFile())

    override fun readEntry(name: String): ByteArray? =
        zip.getEntry(name)?.let { zip.getInputStream(it).use { s -> s.readBytes() } }

    override fun entryNames(): Sequence<String> {
        val out = ArrayList<String>()
        val e = zip.entries()
        while (e.hasMoreElements()) {
            val entry = e.nextElement()
            if (!entry.isDirectory) out.add(entry.name)
        }
        return out.asSequence()
    }

    override fun close() = zip.close()
}

/** Windows-safe reader over a delete-sharing NIO channel (Commons Compress). */
private class CommonsSharedZip(path: Path) : SharedZip {
    private val zip: ZipFile = run {
        val channel = Files.newByteChannel(path, StandardOpenOption.READ)
        try {
            ZipFile.builder().setSeekableByteChannel(channel).get()
        } catch (t: Throwable) {
            runCatching { channel.close() }
            throw t
        }
    }

    override fun readEntry(name: String): ByteArray? =
        zip.getEntry(name)?.let { zip.getInputStream(it).use { s -> s.readBytes() } }

    override fun entryNames(): Sequence<String> {
        val out = ArrayList<String>()
        val e = zip.entries
        while (e.hasMoreElements()) {
            val entry = e.nextElement()
            if (!entry.isDirectory) out.add(entry.name)
        }
        return out.asSequence()
    }

    override fun close() = zip.close()
}

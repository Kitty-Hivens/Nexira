package hivens.core.io

import org.apache.commons.compress.archivers.zip.ZipFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Opens [path] as a zip for reading over a NIO byte channel. On Windows a NIO
 * channel carries FILE_SHARE_DELETE, so holding this open does NOT block a
 * concurrent rename or delete of the archive -- an optional-mod toggle, a sync,
 * or an antivirus pass proceeds while we read. `java.util.zip.ZipFile` omits
 * delete-sharing and locks the file for the read's whole duration, which is what
 * raced a mod toggle against the content scanner on Windows (POSIX ignores the
 * distinction, so it never showed on Linux).
 *
 * The returned [ZipFile] owns the channel; closing it (via `.use { }`) closes the
 * channel too.
 */
fun openSharedZip(path: Path): ZipFile {
    val channel = Files.newByteChannel(path, StandardOpenOption.READ)
    return try {
        ZipFile.builder().setSeekableByteChannel(channel).get()
    } catch (t: Throwable) {
        runCatching { channel.close() }
        throw t
    }
}

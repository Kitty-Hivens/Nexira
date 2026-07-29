package hivens.core.io

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * How much an archive is allowed to become once unpacked.
 *
 * [maxEntries] and [maxBytes] are generous against real content and still
 * finite: the point is that an archive cannot decide to fill the disk. A few
 * hundred kilobytes of nested deflate streams expand to terabytes, and every
 * unpack site here writes into the user's data directory.
 */
data class UnpackLimits(val maxEntries: Int, val maxBytes: Long) {
    companion object {
        private const val MIB = 1024L * 1024L

        /**
         * Pack content: overrides, extra.zip, config trees. A large modpack
         * runs a few thousand files and well under a gigabyte unpacked, so
         * this leaves an order of magnitude of headroom.
         */
        val PACK_CONTENT = UnpackLimits(maxEntries = 50_000, maxBytes = 4096 * MIB)

        /**
         * A JDK: more files than a modpack, but still a known quantity -- a
         * full JDK 25 unpacks to roughly 350 MB.
         */
        val RUNTIME = UnpackLimits(maxEntries = 100_000, maxBytes = 4096 * MIB)
    }
}

/**
 * Tracks one unpack against its [limits].
 *
 * Counts what is actually written rather than what the archive declares. An
 * entry's stated uncompressed size is metadata the archive author controls, so
 * a bomb simply lies about it; the bytes leaving the decompressor cannot.
 *
 * Not thread-safe -- an unpack is a single loop, and sharing one budget across
 * concurrent unpacks would report a limit against the wrong archive anyway.
 */
class UnpackBudget(private val limits: UnpackLimits, private val label: String) {

    private var entries = 0
    private var bytes = 0L

    /** Charges one entry. Call before writing it. */
    fun entry() {
        entries++
        if (entries > limits.maxEntries) {
            throw IOException("$label: archive declares more than ${limits.maxEntries} entries; refusing to continue")
        }
    }

    /**
     * Copies [input] into [dest], charging every byte and stopping the moment
     * the budget runs out -- so a bomb costs the disk at most
     * [UnpackLimits.maxBytes], not however much it asked for.
     */
    fun copyTo(input: InputStream, dest: Path) {
        Files.newOutputStream(dest, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
            .use { out -> copyStream(input, out) }
    }

    /** As [copyTo], for a caller that already holds the destination stream. */
    fun copyStream(input: InputStream, out: OutputStream) {
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            bytes += read
            if (bytes > limits.maxBytes) {
                throw IOException(
                    "$label: unpacked content passed ${limits.maxBytes / (1024 * 1024)} MiB; refusing to continue"
                )
            }
            out.write(buffer, 0, read)
        }
    }
}

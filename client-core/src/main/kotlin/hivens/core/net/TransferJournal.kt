package hivens.core.net

import hivens.core.io.AtomicFiles
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * What a partly-downloaded file has actually got, so the next attempt asks only
 * for the rest.
 *
 * The offset-only resume this replaces could continue a transfer from the end of
 * one contiguous prefix and nothing else, which is the same thing as being unable
 * to resume a parallel download at all. Recording which blocks landed makes the
 * remainder an arbitrary set, so blocks may arrive in any order and a process
 * killed halfway costs nothing but the blocks that were in flight.
 *
 * [expect] and [size] are the invalidation keys. A file the mirror republished
 * under the same name has a different digest, and appending to bytes from the
 * previous version would produce a file that fails verification for a reason no
 * log would explain. When either key disagrees with the transfer being started,
 * the journal and its partial are dropped.
 */
@Serializable
data class TransferJournal(
    val url: String,
    val size: Long,
    val blockSize: Int,
    /** Expected whole-file digest value, when the caller pinned one. */
    val expect: String? = null,
    /** Response validator, when the host sent one. A change means new bytes. */
    val etag: String? = null,
    /** Indices of blocks fully written to the partial file. */
    val done: List<Int> = emptyList(),
) {
    val blockCount: Int get() = blockCountFor(size, blockSize)

    fun isComplete(): Boolean = done.size >= blockCount

    /** Byte range of block [index], clamped to the file's end. */
    fun rangeOf(index: Int): LongRange {
        val start = index.toLong() * blockSize
        val end = minOf(start + blockSize, size) - 1
        return start..end
    }
}

fun blockCountFor(size: Long, blockSize: Int): Int =
    if (size <= 0L) 0 else ((size + blockSize - 1) / blockSize).toInt()

/**
 * Reads and writes [TransferJournal] beside the partial it describes.
 *
 * The journal is written through [AtomicFiles], so a crash during the write
 * leaves the previous journal rather than a truncated one. Losing a journal
 * entirely is survivable -- the partial is then discarded and the transfer
 * starts over -- but a journal that half-describes the partial would claim
 * blocks that are not there, and the file would be committed with holes.
 */
class JournalStore(private val json: Json = DEFAULT_JSON) {

    /** The journal for [partial], or null when absent or unreadable. */
    fun read(partial: Path): TransferJournal? {
        val file = pathFor(partial)
        if (!Files.isRegularFile(file)) return null
        return runCatching { json.decodeFromString(TransferJournal.serializer(), Files.readString(file)) }
            .onFailure { log.debug("transfer: unreadable journal at {}, starting over", file) }
            .getOrNull()
    }

    fun write(partial: Path, journal: TransferJournal) {
        runCatching { AtomicFiles.writeString(pathFor(partial), json.encodeToString(TransferJournal.serializer(), journal)) }
            .onFailure { log.warn("transfer: could not write the journal for {}", partial, it) }
    }

    fun delete(partial: Path) {
        runCatching { Files.deleteIfExists(pathFor(partial)) }
    }

    /**
     * True when [journal] describes the same object [transfer] is asking for.
     * A disagreement on either key means the remote bytes are not the ones the
     * partial holds.
     */
    fun applies(journal: TransferJournal, transfer: Transfer, blockSize: Int): Boolean =
        journal.blockSize == blockSize &&
            journal.size == transfer.size &&
            journal.expect == transfer.expect?.value

    private fun pathFor(partial: Path): Path = partial.resolveSibling("${partial.fileName}.state")

    private companion object {
        val log = LoggerFactory.getLogger(JournalStore::class.java)
        val DEFAULT_JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}

package hivens.core.net

import hivens.core.io.AtomicFiles
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Per-block hashes of a file, taken at the moment its bytes were proven right.
 *
 * This is the only sub-file truth a client can have. A hash taken at any other
 * time describes whatever happens to be on disk, which is exactly the question
 * being asked -- but a block list snapped in the same read that satisfied the
 * manifest's whole-file digest describes bytes already known to be correct, and
 * stays valid for as long as [digest] is still what the file is supposed to be.
 *
 * With it, a damaged file costs the blocks that changed instead of the file: one
 * corrupted sector in a 300 MB resource pack is eight megabytes to put right, not
 * three hundred. Without it the only available repair is a full refetch.
 *
 * [digest] is the invalidation key. A pack that republishes the same path with
 * new bytes pins a different digest, and the old block list has nothing to say
 * about the new file.
 */
@Serializable
data class BlockMap(
    val algorithm: DigestAlgorithm,
    /** Whole-file digest the blocks were taken under. */
    val digest: String,
    val size: Long,
    val blockSize: Int,
    val blocks: List<String>,
) {
    /** True when this map describes the file [expect] and [size] now ask for. */
    fun applies(expect: Digest?, size: Long, blockSize: Int): Boolean =
        expect != null &&
            expect.algorithm == algorithm &&
            expect.matches(digest) &&
            this.blockSize == blockSize &&
            (size <= 0L || this.size == size) &&
            blocks.size == blockCountFor(this.size, blockSize)

    /** Byte range of block [index], clamped to the end of the file. */
    fun rangeOf(index: Int): LongRange {
        val start = index.toLong() * blockSize
        return start..(minOf(start + blockSize, size) - 1)
    }
}

/**
 * Where a file's [BlockMap] lives: a hidden sibling directory, so the map travels
 * with the content when an instance is copied or moved, and needs no registry
 * keyed on paths that change.
 *
 * Only files worth blocking get one. For anything smaller, re-reading the whole
 * file is cheaper than the bookkeeping, and a full refetch of a small file is not
 * a repair worth optimising.
 */
class BlockMapStore(private val json: Json = DEFAULT_JSON) {

    fun read(dest: Path): BlockMap? {
        val file = pathFor(dest)
        if (!Files.isRegularFile(file)) return null
        return runCatching { json.decodeFromString(BlockMap.serializer(), Files.readString(file)) }
            .onFailure { log.debug("transfer: unreadable block map at {}", file) }
            .getOrNull()
    }

    fun write(dest: Path, map: BlockMap) {
        runCatching { AtomicFiles.writeString(pathFor(dest), json.encodeToString(BlockMap.serializer(), map)) }
            .onFailure { log.debug("transfer: could not write the block map for {}", dest, it) }
    }

    fun delete(dest: Path) {
        runCatching { Files.deleteIfExists(pathFor(dest)) }
    }

    private fun pathFor(dest: Path): Path {
        val parent = dest.parent ?: dest.fileSystem.getPath(".")
        return parent.resolve(DIR_NAME).resolve("${dest.fileName}.blocks")
    }

    companion object {
        private val log = LoggerFactory.getLogger(BlockMapStore::class.java)
        private val DEFAULT_JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /**
         * Directory the maps live in, beside the files they describe. Public because
         * anything that sweeps a content directory has to recognise it as the
         * launcher's own bookkeeping: deleting it is silent -- the next repair simply
         * refetches whole files instead of the damaged blocks.
         */
        const val DIR_NAME = ".nexira-blocks"
    }
}

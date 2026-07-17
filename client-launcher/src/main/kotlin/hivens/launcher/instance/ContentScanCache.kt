package hivens.launcher.instance

import hivens.core.cache.DiskStore
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Persistent cache of one archive's parsed metadata, so re-opening a pack's Content
 * tab reads from the DB (one Xodus lookup) instead of cracking every jar again.
 * Keyed by the archive's canonical (enabled) path -- stable across an optional-toggle
 * rename -- and validated by size + last-modified time so a real content change
 * re-parses. Backed by a [DiskStore]; a miss or a size/mtime mismatch returns null.
 */
class ContentScanCache(private val diskStore: DiskStore<CachedScan>) {

    /** Cached scan for [canonicalPath] if present AND still matching [size]+[mtime], else null. */
    fun lookup(canonicalPath: String, size: Long, mtime: Long): CachedScan? {
        val entry = diskStore.read(canonicalPath)?.value ?: return null
        return entry.takeIf { it.sizeBytes == size && it.mtimeMillis == mtime }
    }

    fun put(canonicalPath: String, size: Long, mtime: Long, meta: CachedMeta?) {
        diskStore.write(canonicalPath, CachedScan(size, mtime, meta), System.currentTimeMillis())
    }
}

/** A file's identity (size + mtime) plus its parsed [meta] (null for a shader / unparsed archive). */
@Serializable
class CachedScan(
    @SerialName("size") val sizeBytes: Long,
    @SerialName("mtime") val mtimeMillis: Long,
    val meta: CachedMeta? = null,
)

/** Serializable mirror of the scanner's parsed archive metadata. */
@Serializable
class CachedMeta(
    val name: String? = null,
    val version: String? = null,
    val description: String? = null,
    val icon: ByteArray? = null,
    val homepageUrl: String? = null,
    val license: String? = null,
    val authors: List<String> = emptyList(),
    val dependencies: List<String> = emptyList(),
)

package hivens.launcher.instance

import jetbrains.exodus.ArrayByteIterable
import jetbrains.exodus.ByteIterable
import jetbrains.exodus.bindings.StringBinding
import jetbrains.exodus.env.Environment
import jetbrains.exodus.env.Store
import jetbrains.exodus.env.StoreConfig
import jetbrains.exodus.env.Transaction
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Persistent cache of parsed archive metadata over Xodus, so re-opening a pack's
 * Content tab reads from the DB instead of cracking every jar again. Keyed by the
 * archive's canonical (enabled) path -- stable across an optional-toggle rename --
 * and validated by size + last-modified time so a real content change re-parses.
 * Plaintext (sorted) path keys let [retain] range-scan an instance's entries and drop
 * the ones whose files were deleted.
 */
class ContentScanCache(
    private val env: Environment,
    private val storeName: String,
    private val json: Json,
) {
    private val log = LoggerFactory.getLogger(ContentScanCache::class.java)

    /** Cached scan for [canonicalPath] if present AND still matching [size]+[mtime], else null. */
    fun lookup(canonicalPath: String, size: Long, mtime: Long): CachedScan? {
        val bytes = runCatching {
            env.computeInTransaction { txn -> store(txn).get(txn, key(canonicalPath))?.toByteArray() }
        }.getOrNull() ?: return null
        val scan = runCatching { json.decodeFromString(CachedScan.serializer(), bytes.decodeToString()) }.getOrNull() ?: return null
        return scan.takeIf { it.sizeBytes == size && it.mtimeMillis == mtime }
    }

    fun put(canonicalPath: String, size: Long, mtime: Long, meta: CachedMeta?) {
        runCatching {
            val bytes = json.encodeToString(CachedScan.serializer(), CachedScan(size, mtime, meta)).encodeToByteArray()
            env.executeInTransaction { txn -> store(txn).put(txn, key(canonicalPath), ArrayByteIterable(bytes)) }
        }.onFailure { log.warn("scan cache write failed for {}", canonicalPath, it) }
    }

    /**
     * Drop cached entries whose key is under [dirPrefix] but absent from [keep] --
     * files removed since the last scan. [dirPrefix] MUST end with the path separator
     * so a sibling instance dir sharing a name prefix (`.../X` vs `.../X2`) is not swept.
     */
    fun retain(dirPrefix: String, keep: Set<String>) {
        val stale = runCatching {
            env.computeInTransaction { txn ->
                val out = ArrayList<String>()
                store(txn).openCursor(txn).use { c ->
                    if (c.getSearchKeyRange(key(dirPrefix)) != null) {
                        do {
                            val k = StringBinding.entryToString(c.key)
                            if (!k.startsWith(dirPrefix)) break
                            if (k !in keep) out.add(k)
                        } while (c.next)
                    }
                }
                out
            }
        }.getOrElse { emptyList() }
        if (stale.isEmpty()) return
        runCatching {
            env.executeInTransaction { txn -> val s = store(txn); stale.forEach { s.delete(txn, key(it)) } }
        }.onFailure { log.warn("scan cache prune failed for {}", dirPrefix, it) }
    }

    private fun store(txn: Transaction): Store = env.openStore(storeName, StoreConfig.WITHOUT_DUPLICATES, txn)
    private fun key(s: String): ByteIterable = StringBinding.stringToEntry(s)
    private fun ByteIterable.toByteArray(): ByteArray = bytesUnsafe.copyOf(length)
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

package hivens.launcher

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Per-server cache of "this manifest was successfully synced at time T".
 *
 * The SMARTYcraft auth response carries a multi-megabyte file manifest
 * that `FileDownloadService` walks entry-by-entry, MD5-checking every
 * file on disk. On a 1000-file modpack this dominates cold-start time
 * even when nothing has actually changed since the last login.
 *
 * If the manifest hash is identical to the last successful sync AND
 * that sync was within [TTL_MS], we presume the local install is still
 * good and short-circuit the integrity walk. The TTL is the safety
 * valve — files corrupt on disk, users delete things by hand, etc.,
 * and we don't want a stale cache to mask that forever.
 *
 * Cache location: `<dataDir>/manifest-cache/<serverId>.json`. One file
 * per server so a manifest change on `Industrial` doesn't invalidate
 * the cache for `Create`.
 */
class ManifestCache(
    private val cacheDir: Path,
    private val json: Json,
) {
    private val log = LoggerFactory.getLogger(ManifestCache::class.java)

    @Serializable
    data class Entry(val hash: String, val syncedAt: Long)

    /**
     * Computes a stable hash of [manifestJson] (the canonical JSON form
     * of `FileManifest`, encoded with the launcher's [Json] config so
     * field order and whitespace are deterministic regardless of how the
     * server serialised the response).
     */
    fun hashOf(manifestJson: String): String {
        val md = MessageDigest.getInstance("MD5")
        md.update(manifestJson.toByteArray(Charsets.UTF_8))
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun isClean(serverId: String, manifestHash: String): Boolean {
        val entry = read(serverId) ?: return false
        if (entry.hash != manifestHash) return false
        val ageMs = System.currentTimeMillis() - entry.syncedAt
        if (ageMs >= TTL_MS) {
            log.debug("manifest cache for {} expired ({}h old, TTL {}h)", serverId, ageMs / 3_600_000, TTL_MS / 3_600_000)
            return false
        }
        return true
    }

    fun markClean(serverId: String, manifestHash: String) {
        runCatching {
            Files.createDirectories(cacheDir)
            val entry = Entry(hash = manifestHash, syncedAt = System.currentTimeMillis())
            Files.writeString(cacheFile(serverId), json.encodeToString(entry))
        }.onFailure { log.warn("Failed to persist manifest-cache entry for {}", serverId, it) }
    }

    /**
     * Drops the cache for [serverId] — call when the user explicitly
     * requests "verify integrity" or after a known-corrupting event
     * (failed game launch, repair tool run). Best-effort.
     */
    fun invalidate(serverId: String) {
        runCatching { Files.deleteIfExists(cacheFile(serverId)) }
    }

    private fun read(serverId: String): Entry? {
        val file = cacheFile(serverId)
        if (!Files.exists(file)) return null
        return runCatching {
            json.decodeFromString<Entry>(Files.readString(file))
        }.getOrElse {
            log.debug("manifest-cache entry for {} unreadable; treating as miss", serverId, it)
            null
        }
    }

    private fun cacheFile(serverId: String): Path =
        cacheDir.resolve("${sanitize(serverId)}.json")

    /**
     * Defensive: server ids are trusted (they come from upstream config)
     * but we still don't want a server named `../etc/passwd` to escape
     * the cache dir. Replace anything outside `[A-Za-z0-9._-]`.
     */
    private fun sanitize(serverId: String): String =
        serverId.map { if (it.isLetterOrDigit() || it == '_' || it == '-' || it == '.') it else '_' }.joinToString("")

    companion object {
        /**
         * Re-verify weekly even if the manifest hash matches. The bigger
         * this number is, the longer a silently-corrupted local install
         * stays masked. Seven days is a tradeoff between cold-start
         * speedup and self-healing latency.
         */
        const val TTL_MS = 7L * 24 * 60 * 60 * 1000  // 7 days
    }
}

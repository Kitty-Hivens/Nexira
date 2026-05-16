package hivens.launcher.protocol

import hivens.config.Protocol
import hivens.launcher.network.ServerProtocolConfig
import hivens.config.Storage
import hivens.launcher.network.ChannelRouter
import io.ktor.client.call.body
import io.ktor.client.request.get
import org.slf4j.LoggerFactory
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/**
 * Caches the MD5 of the official `smartycraft.jar` that the server expects
 * in the `cheksum` field of `action=loader` requests.
 *
 * Background: server gates the dashboard fetch behind a launcher-version
 * check -- the request body must include the MD5 of the official launcher
 * binary corresponding to [Protocol.MIMIC_LAUNCHER_VERSION]. When the
 * upstream binary is bumped, the hash changes and previously-baked
 * [Protocol.DEFAULT_LAUNCHER_HASH] becomes stale -- server returns
 * `{"status":"UPDATE"}` until the launcher refreshes its cache.
 *
 * Self-fetch flow (Option B per Conduit planning, locked 2026-05-14):
 * 1. On startup, load cached hash from `<dataDir>/launcher-hash.txt` if
 *    file exists, else fall back to [Protocol.DEFAULT_LAUNCHER_HASH].
 * 2. Use that hash in `action=loader` requests.
 * 3. If server returns UPDATE: download [config.officialJarUrl], MD5 it,
 *    write to cache file, update in-memory value, signal caller to retry.
 * 4. Cap at [MAX_REFRESH_ATTEMPTS_PER_SESSION] refreshes per launcher session
 *    to prevent loops if the upstream server is misconfigured (returning
 *    UPDATE for any hash we send).
 *
 * Lifted out of `ServerRepository` as part of Conduit Phase 1 so the
 * concern is separated from "make HTTP request" logic.
 */
class LauncherHashCache(
    dataDir: File,
    private val router: ChannelRouter,
    private val config: ServerProtocolConfig,
) {
    private val logger = LoggerFactory.getLogger(LauncherHashCache::class.java)
    private val cacheFile = File(dataDir, Storage.HASH_CACHE_FILE)

    @Volatile
    private var current: String = readCachedOrDefault()
    /**
     * Refresh-attempt counter is read+modified from any coroutine that ends
     * up suspended on [refresh] -- a user double-clicking Play could trip
     * two parallel calls, both observing `< MAX` and both incrementing,
     * producing 3+ downloads against the cap of 2 (#189). AtomicInteger +
     * CAS makes the cap check exact even under contention.
     */
    private val refreshAttempts = AtomicInteger(0)

    /** Hash to send in the next `action=loader` request. */
    fun get(): String = current

    /**
     * Server returned UPDATE -- try to refresh by downloading + MD5'ing the
     * official jar. Returns the new hash on success, `null` on failure
     * (network error, exhausted retries, or download produced empty bytes).
     *
     * After a successful refresh, [get] returns the new value and this
     * call counts toward [MAX_REFRESH_ATTEMPTS_PER_SESSION]. Subsequent
     * UPDATE responses past the cap return `null` immediately without
     * re-downloading -- caller should surface "client too old" error to user.
     */
    suspend fun refresh(): String? {
        // CAS loop: atomically reserve a refresh slot. If the slot count
        // is at the cap, return null without spending bandwidth.
        while (true) {
            val current = refreshAttempts.get()
            if (current >= MAX_REFRESH_ATTEMPTS_PER_SESSION) {
                logger.warn("Hash refresh attempts exhausted ({}/{}); server likely demands a launcher upgrade we can't satisfy",
                    current, MAX_REFRESH_ATTEMPTS_PER_SESSION)
                return null
            }
            if (refreshAttempts.compareAndSet(current, current + 1)) break
            // Lost the CAS race -- re-read and decide again.
        }
        return try {
            logger.info("Refreshing launcher hash from {}", config.officialJarUrl)
            val bytes = router.execute { client ->
                client.get(config.officialJarUrl).body<ByteArray>()
            }
            if (bytes.isEmpty()) {
                logger.error("Official jar download returned empty bytes")
                return null
            }
            val newHash = computeMd5Hex(bytes)
            current = newHash
            saveCache(newHash)
            logger.info("Launcher hash refreshed to {} ({} bytes)", newHash, bytes.size)
            newHash
        } catch (e: Exception) {
            logger.error("Failed to refresh launcher hash", e)
            null
        }
    }

    private fun readCachedOrDefault(): String {
        if (!cacheFile.exists()) return Protocol.DEFAULT_LAUNCHER_HASH
        return runCatching { cacheFile.readText().trim() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() && it.length == 32 }
            ?: Protocol.DEFAULT_LAUNCHER_HASH
    }

    private fun saveCache(hash: String) {
        runCatching {
            cacheFile.parentFile?.mkdirs()
            cacheFile.writeText(hash)
        }.onFailure { logger.warn("Could not persist launcher hash cache to {}", cacheFile, it) }
    }

    private fun computeMd5Hex(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    companion object {
        /**
         * Max hash refreshes per launcher session. Two is enough for the
         * legitimate "upstream bumped binary, our cache stale, refresh once,
         * retry succeeds" path. Beyond that the server is misbehaving and
         * we shouldn't keep hammering.
         */
        const val MAX_REFRESH_ATTEMPTS_PER_SESSION = 2
    }
}

package hivens.launcher.protocol

import hivens.config.Protocol
import hivens.config.Storage
import hivens.core.api.HttpClientProvider
import hivens.launcher.network.ServerProtocolConfig
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import org.slf4j.LoggerFactory
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException

/**
 * Caches the MD5 of the official `smartycraft.jar` that the server
 * expects in the `cheksum` field of `action=loader` requests.
 *
 * The server gates the dashboard fetch behind a launcher-version check:
 * the request body must include the MD5 of the official launcher
 * binary corresponding to [Protocol.MIMIC_LAUNCHER_VERSION]. When
 * upstream bumps the binary, the hash changes and
 * [Protocol.DEFAULT_LAUNCHER_HASH] becomes stale -- server returns
 * `{"status":"UPDATE"}` until the launcher refreshes.
 *
 * Self-fetch flow:
 * 1. On startup, load cached hash from `<dataDir>/launcher-hash.txt`
 *    if it exists; else fall back to [Protocol.DEFAULT_LAUNCHER_HASH].
 * 2. Use that hash in `action=loader` requests.
 * 3. On UPDATE: download [ServerProtocolConfig.officialJarUrl], MD5
 *    it, write to cache, update in-memory value, signal caller to retry.
 * 4. Cap at [MAX_REFRESH_ATTEMPTS_PER_SESSION] per session to prevent
 *    loops if the upstream is misconfigured (returning UPDATE for any
 *    hash we send).
 */
class LauncherHashCache(
    dataDir: File,
    private val clientProvider: HttpClientProvider,
    private val config: ServerProtocolConfig,
) {
    private val logger = LoggerFactory.getLogger(LauncherHashCache::class.java)
    private val cacheFile = File(dataDir, Storage.HASH_CACHE_FILE)

    @Volatile
    private var current: String = readCachedOrDefault()
    /**
     * Refresh-attempt counter, read + modified from any coroutine that
     * ends up suspended on [refresh] -- a user double-clicking Play
     * could trip two parallel calls, both observing `< MAX` and both
     * incrementing, producing 3+ downloads against the cap of 2.
     * AtomicInteger + CAS makes the cap check exact under contention.
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
            val response = clientProvider.current.get(config.officialJarUrl)
            if (!response.status.isSuccess()) {
                logger.error("Official jar download returned HTTP {}", response.status)
                return null
            }
            val bytes = response.body<ByteArray>()
            if (!looksLikeArchive(bytes)) {
                logger.error("Official jar download returned {} bytes that are not an archive", bytes.size)
                return null
            }
            val newHash = computeMd5Hex(bytes)
            current = newHash
            saveCache(newHash)
            logger.info("Launcher hash refreshed to {} ({} bytes)", newHash, bytes.size)
            newHash
        } catch (e: CancellationException) {
            throw e
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

    /**
     * A jar is a zip, so a real download opens with the local-file-header magic.
     * The status check alone is not enough: a captive portal or a CDN error page
     * answers 200 with an HTML body, which is non-empty and would hash to a value
     * the server can never accept. Because that value is then persisted and
     * [readCachedOrDefault] accepts any 32-character string, one such response
     * would break SmartyCraft login on every later session too -- past the
     * refresh cap, with no path back but deleting the cache file by hand.
     */
    private fun looksLikeArchive(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() &&
            bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()

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

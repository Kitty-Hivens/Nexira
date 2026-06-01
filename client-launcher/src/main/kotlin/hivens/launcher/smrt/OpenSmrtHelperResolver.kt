package hivens.launcher.smrt

import hivens.core.api.HttpClientProvider
import hivens.core.data.SmrtHelperDescriptor
import hivens.core.data.SmrtHelperVariant
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Resolves the open-smrt-network helper jar that replaces the upstream Smarty
 * coremod for a raw SmartyCraft server of a given Minecraft version.
 *
 * The per-version pin (release tag, asset, SHA-256, Smarty filenames to strip)
 * comes from an out-of-band [SmrtHelperDescriptor] hosted in the
 * open-smrt-network repo -- so the pin moves without a Nexira release, the same
 * pattern `UpdateService` uses for `update-channel.json`. The jar is downloaded
 * from the corresponding GitHub release, SHA-256 verified, and cached once per
 * version under `<dataDir>/helpers/` for reuse across every pack.
 *
 * Every failure path returns null: no descriptor, no matching variant, network
 * error, or hash mismatch all mean "no helper available". The caller still
 * strips Smarty (never re-admits the surveillance mod) and, if nothing is
 * cached on disk either, blocks the launch rather than running it.
 */
class OpenSmrtHelperResolver(
    private val clientProvider: HttpClientProvider,
    private val json: Json,
    dataDirectory: Path,
) {
    private val log = LoggerFactory.getLogger(OpenSmrtHelperResolver::class.java)
    private val helpersDir = dataDirectory.resolve("helpers")
    private val descriptorCache = helpersDir.resolve("smrt-helper.json")
    private val client get() = clientProvider.current

    /** A resolved helper: the local jar to inject + the Smarty names it replaces. */
    data class Resolved(val jar: Path, val smartyNames: List<String>)

    /**
     * Resolves + downloads the helper for [mcVersion], or null when no usable
     * replacement could be obtained. Cheap on a warm cache (one stat + hash of
     * an already-present jar), one network round-trip cold.
     */
    suspend fun resolve(mcVersion: String): Resolved? = withContext(Dispatchers.IO) {
        // Bounded so a slow/hung CDN can't stall the launch hot path; null on
        // timeout (handled like any other resolve miss).
        withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
            val descriptor = fetchDescriptor() ?: return@withTimeoutOrNull null
            val variant = descriptor.variantFor(mcVersion) ?: run {
                log.warn("open-smrt helper: no variant for MC {} in descriptor", mcVersion)
                return@withTimeoutOrNull null
            }
            val jar = downloadVerified(mcVersion, variant) ?: return@withTimeoutOrNull null
            Resolved(jar, variant.smartyNames)
        }
    }

    /**
     * Fetches the descriptor from the open-smrt-network repo, writing every
     * successful fetch to a local last-good cache. A failed fetch (offline,
     * non-200, parse error) falls back to that cache instead of returning null
     * -- so a brief GitHub outage doesn't drop the pinned variant and, with it,
     * flip the launcher back toward the upstream Smarty jar. Returns null only
     * when the network failed AND no cache has ever been written.
     */
    internal suspend fun fetchDescriptor(): SmrtHelperDescriptor? {
        val fetched = try {
            val response = client.get(DESCRIPTOR_URL) { header("Accept", "application/json") }
            if (response.status.value != 200) {
                log.debug("smrt-helper.json fetch returned {}", response.status)
                null
            } else {
                val body = response.bodyAsText()
                val parsed = json.decodeFromString<SmrtHelperDescriptor>(body)
                // Only overwrite the last-good cache with a USEFUL descriptor: a
                // 200 serving an empty `variants:[]` (placeholder / mis-deploy)
                // must not poison the cache and leave us variant-less offline.
                if (parsed.variants.isNotEmpty()) {
                    runCatching {
                        Files.createDirectories(helpersDir)
                        Files.writeString(descriptorCache, body)
                    }
                }
                parsed
            }
        } catch (e: Exception) {
            log.debug("Failed to fetch smrt-helper.json", e)
            null
        }
        if (fetched != null) return fetched
        return readCachedDescriptor()?.also {
            log.info("open-smrt helper: using last-good descriptor cache (live fetch failed)")
        }
    }

    private fun readCachedDescriptor(): SmrtHelperDescriptor? = runCatching {
        if (Files.isRegularFile(descriptorCache)) {
            json.decodeFromString<SmrtHelperDescriptor>(Files.readString(descriptorCache))
        } else null
    }.getOrNull()

    private suspend fun downloadVerified(mcVersion: String, variant: SmrtHelperVariant): Path? {
        val dest = helpersDir.resolve(helperFileName(mcVersion))
        if (sha256Of(dest)?.equals(variant.sha256, ignoreCase = true) == true) {
            return dest
        }
        return try {
            Files.createDirectories(helpersDir)
            val url = "https://github.com/$OPEN_SMRT_REPO/releases/download/${variant.tag}/${variant.asset}"
            log.info("open-smrt helper: downloading {} <- {}", dest.fileName, url)
            downloadToFile(url, dest)
            val onDisk = sha256Of(dest)
            if (onDisk?.equals(variant.sha256, ignoreCase = true) != true) {
                log.warn(
                    "open-smrt helper: SHA-256 mismatch for {} (expected {}, got {}); discarding",
                    variant.asset, variant.sha256, onDisk,
                )
                Files.deleteIfExists(dest)
                null
            } else {
                dest
            }
        } catch (e: Exception) {
            log.warn("open-smrt helper: download failed for {}", variant.asset, e)
            runCatching { Files.deleteIfExists(dest) }
            null
        }
    }

    private suspend fun downloadToFile(url: String, dest: Path) {
        val tmp = dest.resolveSibling("${dest.fileName}.tmp")
        runCatching { Files.deleteIfExists(tmp) }
        client.prepareGet(url).execute { response ->
            if (response.status.value != 200) throw java.io.IOException("HTTP ${response.status} for $url")
            val channel = response.bodyAsChannel()
            FileOutputStream(tmp.toFile()).use { out ->
                val buf = ByteArray(64 * 1024)
                while (!channel.isClosedForRead) {
                    val n = channel.readAvailable(buf, 0, buf.size)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                }
            }
        }
        Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun sha256Of(p: Path): String? {
        if (!Files.isRegularFile(p)) return null
        return runCatching {
            val md = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(p).use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }.getOrNull()
    }

    companion object {
        private const val OPEN_SMRT_REPO = "Kitty-Hivens/open-smrt-network"
        private const val DESCRIPTOR_URL =
            "https://raw.githubusercontent.com/$OPEN_SMRT_REPO/main/meta/smrt-helper.json"

        /** Bound the whole resolve so a slow CDN can't stall the launch hot path. */
        private const val RESOLVE_TIMEOUT_MS = 15_000L

        private fun sanitize(version: String): String =
            version.map { if (it.isLetterOrDigit() || it == '.') it else '_' }.joinToString("")

        /**
         * The exact `mods/` filename the helper is injected under for [mcVersion]
         * (one per MC version, reused across pin bumps). Shared with the planner so
         * strict verification keeps THIS file by exact name rather than a wildcard
         * that would also shield a stale sibling-version jar or a lookalike.
         */
        fun helperFileName(mcVersion: String): String = "open-smrt-network-${sanitize(mcVersion)}.jar"
    }
}

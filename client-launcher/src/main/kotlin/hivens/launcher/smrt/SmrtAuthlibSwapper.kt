package hivens.launcher.smrt

import hivens.core.api.HttpClientProvider
import hivens.core.data.FileData
import hivens.core.data.FileManifest
import hivens.core.data.flatten
import hivens.core.util.retryWithBackoff
import hivens.launcher.network.ServerProtocolConfig
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.FileOutputStream
import java.io.IOException
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Supplies the SmartyCraft-patched `authlib` jar for an SC-bound pack launch.
 *
 * A pack runtime is provisioned from official CDNs, so it ships the VANILLA
 * authlib, which sends the in-game join to `sessionserver.mojang.com` and is
 * rejected (403) for an SC session token. SC's own client ships a patched authlib
 * (same Maven artifact, different bytes) that redirects the join to SC's
 * `auth_joinserver.php`. That patched jar is part of the SC client file set the
 * server distributes -- the exact source the server-list path already pulls into
 * the `clients/` cache. We fetch it for the bound serverId straight from that
 * distribution and cache it per server, so nothing of SC's is rehosted.
 *
 * The caller swaps the resolved classpath entry to the returned jar; the shared
 * `libraries` root is never touched (a patched jar there would hit every pack of
 * that MC version and be re-downloaded back to vanilla by the runtime
 * provisioner's size check).
 *
 * Every failure path returns null: no manifest, no matching entry, network error,
 * or md5 mismatch all mean "no patched authlib" -- the caller then blocks the
 * launch rather than spawning a guaranteed 403.
 */
class SmrtAuthlibSwapper(
    private val clientProvider: HttpClientProvider,
    private val config: ServerProtocolConfig,
    dataDirectory: Path,
) {
    private val log = LoggerFactory.getLogger(SmrtAuthlibSwapper::class.java)
    private val cacheRoot = dataDirectory.resolve("smrt-authlib")
    private val client get() = clientProvider.current

    /**
     * Returns the local path to the SC-patched authlib for [serverId], downloading
     * + md5-verifying it from the SC client distribution on a cold cache, or null
     * when it cannot be located or verified. Idempotent: a warm cache costs one md5.
     *
     * The authlib is matched by ARTIFACT, not by an expected filename: SC freezes
     * its own authlib version per MC release, which can differ from the
     * vanilla-provisioned one (e.g. `authlib-1.5.21.jar` vs `authlib-1.5.25.jar`).
     * The patched jar is cached + returned under its own SC filename; the caller
     * points the classpath entry at it regardless of the vanilla entry's name.
     */
    suspend fun ensurePatchedAuthlib(
        serverId: String,
        fileManifest: FileManifest?,
    ): Path? = withContext(Dispatchers.IO) {
        val manifest = fileManifest ?: run {
            log.warn("authlib swap: no file manifest for '{}'; cannot source patched authlib", serverId)
            return@withContext null
        }
        val entry = findAuthlibEntry(manifest) ?: run {
            log.warn("authlib swap: no authlib jar under libraries in SC manifest for '{}'", serverId)
            return@withContext null
        }
        val (rawPath, data) = entry
        val fileName = rawPath.substringAfterLast('/')
        val dest = cacheRoot.resolve(sanitize(serverId)).resolve(fileName)

        if (verifies(dest, data.md5)) return@withContext dest

        return@withContext try {
            Files.createDirectories(dest.parent)
            val url = "${config.clientFilesBase}/" +
                rawPath.split("/").joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
            log.info("authlib swap: downloading {} <- {}", fileName, url)
            // The SC SOCKS channel drops mid-stream periodically; this jar is
            // mandatory (a miss blocks the launch), so retry transient failures.
            retryWithBackoff(
                operation = "authlib $fileName",
                // A permanent 4xx (renamed/absent artifact) won't fix itself --
                // retrying only adds ~13s of backoff before the inevitable miss.
                // The transient stream drops this retry exists for surface as a
                // plain IOException and still retry.
                shouldRetry = { it !is CancellationException && !(it is HttpStatusException && it.statusCode in 400..499) },
            ) {
                downloadToFile(url, dest)
            }
            if (verifies(dest, data.md5)) {
                dest
            } else {
                log.warn(
                    "authlib swap: md5 mismatch for {} (expected {}, got {}); discarding",
                    fileName, data.md5, md5Of(dest),
                )
                Files.deleteIfExists(dest)
                null
            }
        } catch (e: CancellationException) {
            // An aborted launch must cancel cleanly, not surface as AuthlibUnavailable
            // (which would overwrite the abort's Idle state with an error).
            runCatching { Files.deleteIfExists(dest) }
            throw e
        } catch (e: Exception) {
            log.warn("authlib swap: download failed for {}", fileName, e)
            runCatching { Files.deleteIfExists(dest) }
            null
        }
    }

    /**
     * Finds the manifest entry for the authlib jar (basename `authlib-<ver>.jar`)
     * that sits under a `libraries`-rooted path (the SC client keeps authlib at
     * `libraries-<mc>/authlib-<ver>.jar`), returning its raw manifest path +
     * [FileData]. Restricting to a libraries path avoids matching an unrelated mod
     * that happens to share the prefix.
     */
    private fun findAuthlibEntry(manifest: FileManifest): Pair<String, FileData>? {
        return manifest.flatten().entries.firstOrNull { (path, _) ->
            val segments = path.split("/")
            val base = segments.lastOrNull() ?: return@firstOrNull false
            AUTHLIB_JAR.matches(base) && segments.any { it.startsWith("libraries") }
        }?.toPair()
    }

    /** True when [p] satisfies [expectedMd5], honouring the SC "any" skip-check sentinel. */
    private fun verifies(p: Path, expectedMd5: String): Boolean = when {
        expectedMd5 == "any" -> Files.isRegularFile(p) && Files.size(p) > 0
        expectedMd5.isBlank() -> false
        else -> md5Of(p)?.equals(expectedMd5, ignoreCase = true) == true
    }

    private suspend fun downloadToFile(url: String, dest: Path) {
        val tmp = dest.resolveSibling("${dest.fileName}.tmp")
        try {
            client.prepareGet(url).execute { response ->
                if (response.status.value != 200) {
                    throw HttpStatusException(response.status.value, "HTTP ${response.status} for $url")
                }
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
        } finally {
            runCatching { Files.deleteIfExists(tmp) }
        }
    }

    private fun md5Of(p: Path): String? {
        if (!Files.isRegularFile(p)) return null
        return runCatching {
            val md = MessageDigest.getInstance("MD5")
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

    private fun sanitize(id: String): String =
        id.map { if (it.isLetterOrDigit() || it == '.' || it == '-' || it == '_') it else '_' }.joinToString("")

    private companion object {
        private val AUTHLIB_JAR = Regex("^authlib-.*\\.jar$", RegexOption.IGNORE_CASE)
    }
}

/** A non-2xx HTTP response; carries the status so the retry predicate can skip permanent 4xx. */
private class HttpStatusException(val statusCode: Int, message: String) : IOException(message)

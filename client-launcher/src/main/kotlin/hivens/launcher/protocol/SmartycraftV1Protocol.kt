package hivens.launcher.protocol

import hivens.config.Protocol
import hivens.core.api.interfaces.IServerProtocol
import hivens.core.api.protocol.*
import hivens.launcher.network.ChannelRouter
import hivens.launcher.network.ServerProtocolConfig
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * V1 (legacy PHP-era) implementation of [IServerProtocol] talking to
 * the SmartyCraft `/launcher2/index.php` backend.
 *
 * Wire spec: `docs/dev/smartycraft-v1-protocol.md`. All requests are
 * `POST /launcher2/index.php` with `Content-Type: application/x-www-form-urlencoded`
 * (or multipart for uploads). Response always JSON despite server claiming
 * `text/html` Content-Type.
 *
 * ## Quirks deliberately preserved
 *
 * - Field name `cheksum` (not `checksum`) -- matches what server expects;
 *   typo originated upstream, we mirror.
 * - All login requests need `classPath` and `rtCheckSum` fields even
 *   though server doesn't validate their content (HTTP 500 if absent).
 *   Cargo-cult cargo, kept for compatibility.
 * - Loader UPDATE recovery is handled here transparently -- first attempt
 *   uses cached hash, on UPDATE we ask [LauncherHashCache] to refresh,
 *   second attempt uses fresh hash. Caller sees a single successful
 *   `loader()` call.
 *
 * ## Out of scope
 *
 * - HTTP retry / channel switching -- that's [HttpClientProvider]'s job
 *   (becomes ChannelRouter in Conduit Phase 2 #155).
 * - Response caching -- repositories cache their own session/dashboard
 *   results when appropriate.
 * - Crash report submission (`action=report`) -- Aura uses Beacon for
 *   local-only crash flow per privacy stance, not server-side notification.
 */
class SmartycraftV1Protocol(
    private val router: ChannelRouter,
    private val json: Json,
    private val launcherHashCache: LauncherHashCache,
    private val config: ServerProtocolConfig,
) : IServerProtocol {

    private val logger = LoggerFactory.getLogger(SmartycraftV1Protocol::class.java)

    override suspend fun loader(): LoaderResponse {
        val firstHash = launcherHashCache.get()
        val first = postLoader(firstHash)
        if (first.parsedStatus != ProtocolStatus.UPDATE) return first

        logger.info("Loader returned UPDATE; refreshing launcher hash")
        val newHash = launcherHashCache.refresh()
            ?: run {
                logger.warn("Hash refresh failed, returning UPDATE response as-is")
                return first
            }
        return postLoader(newHash)
    }

    private suspend fun postLoader(hash: String): LoaderResponse {
        val payload = json.encodeToString(
            LoaderRequest(
                version = Protocol.MIMIC_LAUNCHER_VERSION,
                cheksum = hash,
            )
        )
        val raw = postForm(
            "loader",
            Parameters.build {
                append("action", "loader")
                append("json", payload)
            }
        )
        return parseJsonTolerant<LoaderResponse>(raw)
            ?: LoaderResponse(status = "ERROR", message = "Malformed loader response")
    }

    override suspend fun login(request: LoginRequest): LoginResponse {
        val payload = json.encodeToString(request)
        val raw = postForm(
            "login",
            Parameters.build {
                append("action", "login")
                append("json", payload)
            }
        )
        return parseJsonTolerant<LoginResponse>(raw)
            ?: LoginResponse(status = "ERROR", message = "Malformed login response")
    }

    override suspend fun spawn(uid: String, login: String, server: String): StatusOnlyResponse {
        val payload = json.encodeToString(SpawnRequest(login = login, server = server))
        val signature = SmartycraftSignatureBuilder.forSpawn(uid, login, server)
        return postSignedAction("spawn", payload, signature)
    }

    override suspend fun twoauth(uid: String, login: String, code: String): StatusOnlyResponse {
        val payload = json.encodeToString(TwoAuthRequest(login = login, code = code))
        val signature = SmartycraftSignatureBuilder.forTwoAuth(uid, login, code)
        return postSignedAction("twoauth", payload, signature)
    }

    override suspend fun uploadSkin(uid: String, login: String, png: ByteArray): StatusOnlyResponse =
        postUpload("skinupload", "skin", uid, login, png)

    override suspend fun uploadCloak(uid: String, login: String, png: ByteArray): StatusOnlyResponse =
        postUpload("cloakupload", "cloak", uid, login, png)

    private suspend fun postSignedAction(action: String, jsonPayload: String, signature: String): StatusOnlyResponse {
        val raw = postForm(
            action,
            Parameters.build {
                append("action", action)
                append("json", jsonPayload)
                append("check", signature)
            }
        )
        return parseJsonTolerant<StatusOnlyResponse>(raw)
            ?: StatusOnlyResponse(status = "ERROR", message = "Malformed $action response")
    }

    private suspend fun postUpload(
        action: String,
        binaryFieldName: String,
        uid: String,
        login: String,
        bytes: ByteArray,
    ): StatusOnlyResponse {
        val jsonPayload = json.encodeToString(UploadRequest(login = login))
        val signature = SmartycraftSignatureBuilder.forUpload(uid, login)
        return try {
            val raw = router.execute { client ->
                val response = client.post(config.authUrl) {
                    setBody(MultiPartFormDataContent(
                        formData {
                            append("action", action)
                            append("json", jsonPayload)
                            append("check", signature)
                            append(binaryFieldName, bytes, Headers.build {
                                append(HttpHeaders.ContentType, "image/png")
                                append(HttpHeaders.ContentDisposition, "filename=\"$binaryFieldName.png\"")
                            })
                        }
                    ))
                }
                response.body<String>().trim()
            }
            parseJsonTolerant<StatusOnlyResponse>(raw)
                ?: StatusOnlyResponse(status = "ERROR", message = "Malformed $action response")
        } catch (e: Exception) {
            logger.error("Upload $action failed", e)
            StatusOnlyResponse(status = "ERROR", message = e.message)
        }
    }

    /**
     * Pre-Conduit this method caught Exception and returned "" so callers
     * mapped network failures into ProtocolStatus.ERROR responses. That hid
     * IOException from AuthService.retryWithBackoff's shouldRetry predicate,
     * which made the auth-flow retry chain dead code for the very class of
     * failure it was built for (SOCKS h2 resets). Now the exception
     * propagates; each protocol method's own caller decides whether to retry
     * or surface as an error response.
     */
    private suspend fun postForm(actionName: String, params: Parameters): String =
        router.execute { client ->
            val response = client.post(config.authUrl) { setBody(FormDataContent(params)) }
            response.body<String>().trim()
        }

    private inline fun <reified T> parseJsonTolerant(raw: String): T? {
        if (raw.isBlank()) return null
        // Server returns Content-Type: text/html for JSON bodies -- parse
        // only when the body actually starts with a JSON object opener.
        if (!raw.startsWith("{")) return null
        return runCatching { json.decodeFromString<T>(raw) }
            .onFailure { logger.warn("JSON decode failed for response: ${raw.take(200)}", it) }
            .getOrNull()
    }
}

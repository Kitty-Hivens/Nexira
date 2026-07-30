package hivens.launcher.protocol

import hivens.config.Protocol
import hivens.core.api.HttpClientProvider
import hivens.core.api.interfaces.IServerProtocol
import hivens.core.api.protocol.*
import hivens.launcher.network.ServerProtocolConfig
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * V1 (legacy PHP-era) implementation of [IServerProtocol] talking to
 * the SmartyCraft `/launcher2/index.php` backend. Wire spec:
 * `docs/dev/smartycraft-v1-protocol.md`. Requests are `POST` with
 * `Content-Type: application/x-www-form-urlencoded` (or multipart for
 * uploads); responses are always JSON despite server claiming
 * `text/html`.
 *
 * Quirks deliberately preserved:
 * - Field name `cheksum` (not `checksum`) -- typo originated upstream;
 *   server expects exactly that, so we mirror.
 * - Login requests need `classPath` and `rtCheckSum` fields even though
 *   server doesn't validate their content (HTTP 500 if absent).
 *   Cargo-cult, kept for compatibility.
 * - Loader UPDATE recovery is handled here transparently: first
 *   attempt uses cached hash, on UPDATE [LauncherHashCache] refreshes,
 *   second attempt uses the fresh hash. Caller sees a single
 *   successful `loader()`.
 *
 * Out of scope: HTTP retry (each caller decides), response caching
 * (repositories cache themselves), crash-report submission (Nexira uses
 * Beacon, not server-side).
 */
class SmartycraftV1Protocol(
    private val clientProvider: HttpClientProvider,
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
            val response = clientProvider.current.post(config.authUrl) {
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
            val raw = response.body<String>().trim()
            parseJsonTolerant<StatusOnlyResponse>(raw)
                ?: StatusOnlyResponse(status = "ERROR", message = "Malformed $action response")
        } catch (e: Exception) {
            logger.error("Upload $action failed", e)
            StatusOnlyResponse(status = "ERROR", message = e.message)
        }
    }

    /**
     * Network failures propagate as exceptions; each caller decides
     * whether to retry (auth flow via [hivens.core.util.retryWithBackoff])
     * or surface as an ERROR-status response. Swallowing here would hide
     * IOException from the shouldRetry predicate and make that retry chain
     * dead code.
     */
    private suspend fun postForm(params: Parameters): String {
        val response = clientProvider.current.post(config.authUrl) { setBody(FormDataContent(params)) }
        return response.body<String>().trim()
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

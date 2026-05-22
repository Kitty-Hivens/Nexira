package hivens.launcher.smrt

import hivens.core.api.HttpClientProvider
import hivens.core.api.dto.smrt.ModrinthVersion
import hivens.core.api.dto.smrt.SmrtPackListing
import hivens.core.api.dto.smrt.SmrtPackManifest
import hivens.core.api.dto.smrt.SmrtPackSummary
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import kotlinx.serialization.json.Json
import java.io.IOException

/**
 * Thin HTTP wrapper for the smrt mirror's `/v1/...` endpoints plus the
 * Modrinth `/v2/project/.../version/...` endpoint needed to resolve
 * modrinth-source items. Uses the "direct" HttpClient (strict TLS, no
 * SOCKS proxy) since both endpoints are public CDN-fronted and don't
 * need the SC channel's special handling.
 */
class SmrtPackClient(
    private val httpProvider: HttpClientProvider,
    private val mirrorBase: String = DEFAULT_MIRROR_BASE,
    private val json: Json = DEFAULT_JSON,
) {
    companion object {
        const val DEFAULT_MIRROR_BASE = "https://smrt.hivens.dev"
        const val MODRINTH_API_BASE = "https://api.modrinth.com"
        private const val USER_AGENT = "Nexira-smrt-mirror-client"

        // Tolerant decoder: spec says unknown fields and unknown source
        // variants must be silently ignored on the client side, both for
        // forward-compat (server adds new optional fields without
        // bumping schema_version) and for the open-ended display
        // metadata block.
        val DEFAULT_JSON = Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = false
        }
    }

    suspend fun fetchManifest(packId: String): SmrtPackManifest =
        getJson("$mirrorBase/v1/packs/$packId/manifest")

    suspend fun fetchSummary(packId: String): SmrtPackSummary =
        getJson("$mirrorBase/v1/packs/$packId")

    suspend fun listPacks(): SmrtPackListing =
        getJson("$mirrorBase/v1/packs")

    /**
     * Resolve a modrinth source by hitting Modrinth's project/version
     * endpoint. The wire manifest carries `project_id` + `version_id`
     * but not the file URL -- Modrinth versions can ship multiple files
     * (sources jar, deobf, signatures), and the primary one is flagged
     * via `primary: true` on the file. Caller picks the file via
     * [ModrinthVersion.primaryFile].
     */
    suspend fun resolveModrinthVersion(projectId: String, versionId: String): ModrinthVersion =
        getJson("$MODRINTH_API_BASE/v2/project/$projectId/version/$versionId")

    /**
     * Stream a download into a callback. Used for file-by-file fetching
     * so the per-file progress can be tracked and the buffer never
     * holds the whole jar in RAM. Caller writes the channel to disk.
     */
    suspend fun openDownloadStream(url: String): ByteReadChannel {
        val resp = httpProvider.current.get(url) {
            headers.append("User-Agent", USER_AGENT)
        }
        if (!resp.status.isSuccess()) {
            throw IOException("GET $url failed: ${resp.status}")
        }
        return resp.bodyAsChannel()
    }

    private suspend inline fun <reified T> getJson(url: String): T {
        val resp: HttpResponse = httpProvider.current.get(url) {
            headers.append("User-Agent", USER_AGENT)
            headers.append("Accept", "application/json")
        }
        if (!resp.status.isSuccess()) {
            val body = runCatching { resp.bodyAsText() }.getOrDefault("")
            throw IOException("GET $url failed: ${resp.status} body=$body")
        }
        val text = resp.bodyAsText()
        return json.decodeFromString(text)
    }
}

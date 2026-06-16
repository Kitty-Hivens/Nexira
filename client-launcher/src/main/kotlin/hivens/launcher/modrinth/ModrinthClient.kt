package hivens.launcher.modrinth

import hivens.core.api.HttpClientProvider
import hivens.core.api.dto.modrinth.ModrinthProject
import hivens.core.api.dto.modrinth.ModrinthVersion
import hivens.launcher.cache.ModrinthCaches
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import java.io.IOException

/**
 * HTTP client for Modrinth's public, key-less `/v2` API. Split out of
 * [hivens.launcher.smrt.SmrtPackClient] so the mirror client stays mirror-only
 * and Modrinth can grow into a first-class catalogue source. Uses the "direct"
 * HttpClient (strict TLS, no SOCKS proxy) -- api.modrinth.com is a public
 * CDN-fronted endpoint that needs none of the SmartyCraft channel's handling.
 */
class ModrinthClient(
    private val httpProvider: HttpClientProvider,
    private val json: Json = DEFAULT_JSON,
    private val caches: ModrinthCaches = ModrinthCaches.passthrough(),
) {
    /**
     * Project metadata for a Modrinth id/slug. One call per id is enough; the
     * cache holds it. Used by the Library Content-tab icon resolver today and
     * the catalogue detail render as Modrinth becomes a browsable source.
     */
    suspend fun resolveProject(projectId: String): ModrinthProject {
        val url = "$API_BASE/v2/project/$projectId"
        return caches.project.get(url) { getJson(url) }
    }

    /**
     * Resolve a specific version. The wire carries `project_id` + `version_id`
     * but not a file URL -- a version can ship multiple files, and the primary
     * one is flagged `primary: true` (see [ModrinthVersion.primaryFile]).
     */
    suspend fun resolveVersion(projectId: String, versionId: String): ModrinthVersion {
        val url = "$API_BASE/v2/project/$projectId/version/$versionId"
        return caches.version.get(url) { getJson(url) }
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
        return json.decodeFromString(resp.bodyAsText())
    }

    companion object {
        const val API_BASE = "https://api.modrinth.com"
        private const val USER_AGENT = "Nexira-modrinth-client"

        // Same tolerance as the mirror client: ignore unknown fields so a
        // Modrinth payload that grows new keys does not break the decode.
        val DEFAULT_JSON = Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = false
        }
    }
}

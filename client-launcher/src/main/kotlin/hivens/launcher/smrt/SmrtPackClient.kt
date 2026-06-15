package hivens.launcher.smrt

import hivens.core.api.HttpClientProvider
import hivens.core.api.dto.smrt.ModrinthProject
import hivens.core.api.dto.smrt.ModrinthVersion
import hivens.core.api.dto.smrt.SmrtPackListing
import hivens.core.api.dto.smrt.SmrtPackManifest
import hivens.core.api.dto.smrt.SmrtPackSummary
import hivens.core.api.interfaces.IMirrorPackClient
import hivens.launcher.cache.SmrtPackCaches
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
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
    private val caches: SmrtPackCaches = SmrtPackCaches.passthrough(),
) : IMirrorPackClient {
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

    override suspend fun fetchManifest(packId: String): SmrtPackManifest {
        val url = "$mirrorBase/v1/packs/$packId/manifest"
        return caches.manifest.get(url) { getJson(url) }
    }

    /**
     * Fetch a specific historical manifest version. Pinned-version
     * instances (the install-time `pack_version` is recorded on
     * [hivens.core.data.PackInstance.pinnedPackVersion]) must call
     * this rather than the latest endpoint, since the mirror may
     * have bumped the pack since install and a divergent latest
     * manifest's MC version / loader / Java major would not match
     * the files that were synced to disk.
     */
    override suspend fun fetchManifestVersion(packId: String, version: String): SmrtPackManifest {
        // Same cache as the latest endpoint, keyed by the version-pinned URL; a
        // pinned historical manifest is immutable so it stays a warm cache hit.
        val url = "$mirrorBase/v1/packs/$packId/manifest/$version"
        return caches.manifest.get(url) { getJson(url) }
    }

    suspend fun fetchSummary(packId: String): SmrtPackSummary {
        val url = "$mirrorBase/v1/packs/$packId"
        return caches.summary.get(url) { getJson(url) }
    }

    suspend fun listPacks(): SmrtPackListing {
        val url = "$mirrorBase/v1/packs"
        return caches.listing.get(url) { getJson(url) }
    }

    /**
     * Resolve a modrinth source by hitting Modrinth's project/version
     * endpoint. The wire manifest carries `project_id` + `version_id`
     * but not the file URL -- Modrinth versions can ship multiple files
     * (sources jar, deobf, signatures), and the primary one is flagged
     * via `primary: true` on the file. Caller picks the file via
     * [ModrinthVersion.primaryFile].
     */
    suspend fun resolveModrinthVersion(projectId: String, versionId: String): ModrinthVersion {
        val url = "$MODRINTH_API_BASE/v2/project/$projectId/version/$versionId"
        return caches.modrinthVersion.get(url) { getJson(url) }
    }

    /**
     * Fetch project metadata for a Modrinth-sourced mod -- used by the
     * Library PackDetail icon resolver when the manifest entry doesn't
     * carry its own `display.iconUrl`. One call per project_id is
     * enough; callers cache the result.
     */
    suspend fun resolveModrinthProject(projectId: String): ModrinthProject {
        val url = "$MODRINTH_API_BASE/v2/project/$projectId"
        return caches.modrinthProject.get(url) { getJson(url) }
    }

    /**
     * Stream a download through a caller-supplied consumer. The
     * consumer must drain (or copy out of) the channel before the
     * lambda returns -- the underlying response is closed on exit and
     * the channel becomes invalid.
     *
     * The `prepareGet().execute { }` pattern is mandatory here: a
     * plain `get(url).bodyAsChannel()` would route through ktor's
     * SavedHttpCall, which loads the entire response into a single
     * ByteArray before exposing the channel. On a 24 MB resource pack
     * with concurrent downloads that triggers OutOfMemoryError on the
     * compose-desktop heap. execute{} bypasses SavedHttpCall and gives
     * a true streaming channel; the 64 KB read loop downstream keeps
     * resident memory bounded regardless of file size.
     */
    suspend fun downloadStreaming(url: String, consume: suspend (ByteReadChannel) -> Unit) {
        httpProvider.current.prepareGet(url) {
            headers.append("User-Agent", USER_AGENT)
        }.execute { resp ->
            if (!resp.status.isSuccess()) {
                val body = runCatching { resp.bodyAsText() }.getOrDefault("")
                throw IOException("GET $url failed: ${resp.status} body=$body")
            }
            consume(resp.bodyAsChannel())
        }
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

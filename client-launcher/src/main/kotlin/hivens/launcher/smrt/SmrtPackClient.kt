package hivens.launcher.smrt

import hivens.core.api.HttpClientProvider
import hivens.core.api.dto.smrt.SmrtBuildDiff
import hivens.core.api.dto.smrt.SmrtManifestVersions
import hivens.core.api.dto.smrt.SmrtPackListing
import hivens.core.api.dto.smrt.SmrtPackManifest
import hivens.core.api.dto.smrt.SmrtPackSummary
import hivens.core.api.interfaces.IMirrorPackClient
import hivens.core.cache.read
import hivens.launcher.cache.SmrtPackCaches
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.io.IOException

/**
 * Thin HTTP wrapper for the smrt mirror's `/v1/...` endpoints. Uses the
 * "direct" HttpClient (strict TLS, no SOCKS proxy) since the mirror is public
 * CDN-fronted and needs none of the SC channel's special handling. Modrinth
 * reads live in [hivens.launcher.modrinth.ModrinthClient].
 */
class SmrtPackClient(
    private val httpProvider: HttpClientProvider,
    private val mirrorBase: String = DEFAULT_MIRROR_BASE,
    private val json: Json = DEFAULT_JSON,
    private val caches: SmrtPackCaches = SmrtPackCaches.passthrough(),
) : IMirrorPackClient {
    companion object {
        const val DEFAULT_MIRROR_BASE = "https://smrt.hivens.dev"
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

    override suspend fun fetchManifest(packId: String): SmrtPackManifest =
        fetchManifest(packId, forceRefresh = false)

    /**
     * The pack's current manifest. [forceRefresh] bypasses the cache TTL and is
     * for a check the user asked for; an ambient read must leave it false or a
     * screen opening turns into an unconditional round trip.
     */
    suspend fun fetchManifest(packId: String, forceRefresh: Boolean): SmrtPackManifest {
        val url = "$mirrorBase/v1/packs/$packId/manifest"
        return caches.manifest.read(url, forceRefresh) { getJson(url) }
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

    override suspend fun fetchSummary(packId: String): SmrtPackSummary =
        fetchSummary(packId, forceRefresh = false)

    /** The pack summary. See [fetchManifest] for what [forceRefresh] costs. */
    suspend fun fetchSummary(packId: String, forceRefresh: Boolean): SmrtPackSummary {
        val url = "$mirrorBase/v1/packs/$packId"
        return caches.summary.read(url, forceRefresh) { getJson(url) }
    }

    suspend fun listPacks(): SmrtPackListing {
        val url = "$mirrorBase/v1/packs"
        return caches.listing.get(url) { getJson(url) }
    }

    /**
     * The per-build listing the mirror retains for a pack, newest first. The
     * server order is canonical (publish-date across channels); callers must
     * not re-sort by version tuples.
     */
    suspend fun listBuilds(packId: String, forceRefresh: Boolean = false): SmrtManifestVersions {
        val url = "$mirrorBase/v1/packs/$packId/manifest/versions"
        return caches.versions.read(url, forceRefresh) { getJson(url) }
    }

    /**
     * Stale-then-fresh view of the build listing: emits the cached one at once
     * when it is merely stale, then the reloaded one. A one-shot [listBuilds]
     * would hand back the stale list and refresh into the void, which is how a
     * version screen ends up missing the newest builds until it is reopened.
     */
    fun buildsStream(packId: String): Flow<SmrtManifestVersions> {
        val url = "$mirrorBase/v1/packs/$packId/manifest/versions"
        return caches.versions.flow(url) { getJson(url) }.map { it.value }
    }

    /**
     * Drop the cached views of [packId] that an applied update makes stale: the
     * summary's latest-version pointer, the build listing, and the "current"
     * manifest. Version-pinned manifests are immutable and stay cached.
     */
    suspend fun invalidatePack(packId: String) {
        caches.summary.invalidate("$mirrorBase/v1/packs/$packId")
        caches.versions.invalidate("$mirrorBase/v1/packs/$packId/manifest/versions")
        caches.manifest.invalidate("$mirrorBase/v1/packs/$packId/manifest")
    }

    /**
     * Structured change summary between two retained builds. Display data for
     * the versions screen (registry-enriched version labels); uncached -- a
     * (from, to) pair is immutable but the pair space is wide and reads are rare.
     */
    override suspend fun fetchDiff(packId: String, from: String, to: String): SmrtBuildDiff {
        val url = "$mirrorBase/v1/packs/$packId/diff?from=${from.encodeURLParameter()}&to=${to.encodeURLParameter()}"
        return getJson(url)
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
        downloadStreaming(url, resumeFrom = 0L) { _, channel -> consume(channel) }
    }

    /**
     * Resuming variant. With [resumeFrom] above zero the request carries a range
     * header, and [consume] is told whether the server honoured it: `true` means the
     * channel continues from that offset and the caller appends, `false` means the
     * response is the whole resource from the start and whatever was kept must be
     * discarded. A server that ignores ranges is normal, so this is a fact to act on
     * rather than an error.
     */
    suspend fun downloadStreaming(
        url: String,
        resumeFrom: Long,
        consume: suspend (resumed: Boolean, ByteReadChannel) -> Unit,
    ) {
        httpProvider.current.prepareGet(url) {
            headers.append("User-Agent", USER_AGENT)
            if (resumeFrom > 0L) headers.append(HttpHeaders.Range, "bytes=$resumeFrom-")
        }.execute { resp ->
            if (!resp.status.isSuccess()) {
                val body = runCatching { resp.bodyAsText() }.getOrDefault("")
                throw IOException("GET $url failed: ${resp.status} body=$body")
            }
            consume(resp.status == HttpStatusCode.PartialContent, resp.bodyAsChannel())
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

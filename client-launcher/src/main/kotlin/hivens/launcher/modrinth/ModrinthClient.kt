package hivens.launcher.modrinth

import hivens.core.api.HttpClientProvider
import hivens.core.net.SkipIfPresent
import hivens.core.net.Transfer
import hivens.core.net.TransferEngine
import hivens.core.api.dto.modrinth.ModrinthDisclosures
import hivens.core.api.dto.modrinth.ModrinthGameVersion
import hivens.core.api.dto.modrinth.ModrinthTeamMember
import hivens.core.api.dto.modrinth.ModrinthProject
import hivens.core.api.dto.modrinth.ModrinthSearchResponse
import hivens.core.api.dto.modrinth.ModrinthHashQuery
import hivens.core.api.dto.modrinth.ModrinthUpdateQuery
import hivens.core.api.dto.modrinth.ModrinthVersion
import hivens.launcher.cache.ModrinthCaches
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * HTTP client for Modrinth's public, key-less `/v2` API. Split out of
 * [hivens.launcher.smrt.SmrtPackClient] so the mirror client stays mirror-only
 * and Modrinth can grow into a first-class catalogue source. Uses the "direct"
 * HttpClient (strict TLS, no per-host bypass) -- api.modrinth.com is a public
 * CDN-fronted endpoint that needs none of the SmartyCraft channel's handling.
 */
class ModrinthClient(
    private val httpProvider: HttpClientProvider,
    private val transfers: TransferEngine,
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
     * What the author declares this project does -- `GET /v3/project/{id}/disclosures`.
     *
     * The only v3 call in this client. There is no v2 equivalent: disclosures
     * arrived with v3 and the older route does not carry them, so the page reads
     * one endpoint off the other line rather than doing without.
     *
     * An empty list and a failed request must not look the same, which is why
     * this throws rather than swallowing: "the author declared nothing" is an
     * answer a reader can act on, and "we could not ask" is not.
     */
    suspend fun disclosures(projectId: String): ModrinthDisclosures =
        getJson("$API_BASE/v3/project/$projectId/disclosures")

    /**
     * Resolve a specific version. The wire carries `project_id` + `version_id`
     * but not a file URL -- a version can ship multiple files, and the primary
     * one is flagged `primary: true` (see [ModrinthVersion.primaryFile]).
     */
    suspend fun resolveVersion(projectId: String, versionId: String): ModrinthVersion {
        val url = "$API_BASE/v2/project/$projectId/version/$versionId"
        return caches.version.get(url) { getJson(url) }
    }

    /**
     * Identify a file by its SHA1 via `/v2/version_file/{hash}`. Returns the
     * owning version (whose `project_id` leads to the project icon) or null when
     * the hash is unknown to Modrinth (a local / non-Modrinth artifact -- a 404).
     * Backs the Content-tab icon fallback for origin-agnostic instances, where
     * there is no manifest project_id to look up by.
     *
     * Only the 404 answers null. A timeout, a rate limit or a cancelled caller
     * used to come back indistinguishable from it, and callers cache this answer:
     * a mod whose lookup was interrupted was then recorded as one the catalogue
     * has never heard of, for as long as the launcher ran.
     */
    suspend fun versionByHash(sha1: String): ModrinthVersion? =
        getJsonOrNull<ModrinthVersion>("$API_BASE/v2/version_file/$sha1?algorithm=sha1")

    /**
     * Search the catalogue, restricted to modpacks via a project-type facet.
     * Uncached: a query is dynamic, and the UI searches on submit (not per
     * keystroke) so API traffic stays modest.
     */
    suspend fun searchModpacks(query: String, offset: Int = 0, limit: Int = 40): ModrinthSearchResponse {
        val q = URLEncoder.encode(query, StandardCharsets.UTF_8)
        val facets = URLEncoder.encode("""[["project_type:modpack"]]""", StandardCharsets.UTF_8)
        return getJson("$API_BASE/v2/search?query=$q&facets=$facets&offset=$offset&limit=$limit")
    }

    /**
     * Who is credited on a project.
     *
     * A separate call because the project record carries no authors. Returned in
     * the catalogue's own order, which puts nobody first in particular, so the
     * caller sorts.
     */
    suspend fun members(projectId: String): List<ModrinthTeamMember> =
        getJson("$API_BASE/v2/project/$projectId/members")

    /**
     * Every Minecraft version the catalogue knows, newest first.
     *
     * A tag list rather than project data: it is the same for every project and
     * changes when Mojang ships, so the page asks once and folds every project's
     * support against it. Without it a range cannot be computed at all, because
     * nothing else says which versions are adjacent.
     */
    suspend fun gameVersions(): List<ModrinthGameVersion> =
        getJson("$API_BASE/v2/tag/game_version")

    /** All versions of a project, newest-first (Modrinth's default order). */
    suspend fun listVersions(projectId: String): List<ModrinthVersion> =
        getJson("$API_BASE/v2/project/$projectId/version")

    /**
     * Search installable MODS compatible with an instance, via project-type +
     * game-version + loader facets. Blank [mcVersion]/[loader] drop their facet
     * (broader search). Backs the Content tab's "Find projects" browser.
     */
    suspend fun searchMods(query: String, mcVersion: String, loader: String, offset: Int = 0, limit: Int = 40): ModrinthSearchResponse {
        val q = URLEncoder.encode(query, StandardCharsets.UTF_8)
        val facetList = buildList {
            add("""["project_type:mod"]""")
            if (mcVersion.isNotBlank()) add("""["versions:$mcVersion"]""")
            if (loader.isNotBlank()) add("""["categories:$loader"]""")
        }
        val facets = URLEncoder.encode("[${facetList.joinToString(",")}]", StandardCharsets.UTF_8)
        return getJson("$API_BASE/v2/search?query=$q&facets=$facets&offset=$offset&limit=$limit")
    }

    /**
     * The versions these file hashes ARE -- `POST /v2/version_files`.
     *
     * The update endpoint answers what is newest in a channel, which is not the
     * same as what is newer than the file asking. Without knowing when the
     * installed file was published, a beta from this June looks "outdated"
     * against a release from last June, and the launcher offers the rollback as
     * an update. This is where that date comes from, for every file at once.
     */
    suspend fun versionsForHashes(hashes: List<String>): Map<String, ModrinthVersion> {
        if (hashes.isEmpty()) return emptyMap()
        val out = mutableMapOf<String, ModrinthVersion>()
        for (chunk in hashes.distinct().chunked(UPDATE_QUERY_CHUNK)) {
            out += postJson<ModrinthHashQuery, Map<String, ModrinthVersion>>(
                "$API_BASE/v2/version_files",
                ModrinthHashQuery(algorithm = "sha1", hashes = chunk),
            )
        }
        return out
    }

    /**
     * For each file hash, the newest version Modrinth has that fits [loaders] and
     * [gameVersions] within [versionTypes] -- `POST /v2/version_files/update_many`.
     *
     * The server answers with the newest MATCHING version, which is not the same
     * question as "is there something newer than what you have": a file already on
     * the newest build gets itself back. Callers compare the returned file's hash
     * against the one they asked about rather than treating every answer as an
     * update.
     *
     * Chunked, because the request body carries every hash and a large instance
     * has hundreds. Uncached on purpose: a stale "no update" is the one answer
     * nobody can act on, and the check runs on an explicit open or click.
     *
     * The value is an ARRAY per hash, and the sibling route `version_files/update`
     * answers the same question with a single object per hash. Verified against
     * the live API rather than read off the docs, which describe neither shape.
     */
    suspend fun latestForHashes(
        hashes: List<String>,
        loaders: List<String>,
        gameVersions: List<String>,
        versionTypes: List<String>,
    ): Map<String, List<ModrinthVersion>> {
        if (hashes.isEmpty()) return emptyMap()
        val out = mutableMapOf<String, List<ModrinthVersion>>()
        for (chunk in hashes.distinct().chunked(UPDATE_QUERY_CHUNK)) {
            val body = ModrinthUpdateQuery(
                algorithm    = "sha1",
                hashes       = chunk,
                loaders      = loaders,
                gameVersions = gameVersions,
                versionTypes = versionTypes,
            )
            out += postJson<ModrinthUpdateQuery, Map<String, List<ModrinthVersion>>>(
                "$API_BASE/v2/version_files/update_many",
                body,
            )
        }
        return out
    }

    /**
     * The newest build of [projectId] that actually runs on this pack, or null.
     *
     * Null MEANS there is none. It used to fall back on the newest build overall,
     * which every caller then read as "here is the one for you" and installed: a
     * jar for another loader, or another game version, put into the folder without
     * a word. All three callers already treat null as "this project does not
     * support this pack", so the fallback was contradicting the only readings of
     * its own result.
     *
     * A blank axis is not a constraint, which is how a pack with no recorded
     * loader still gets an answer.
     */
    suspend fun newestMatchingVersion(projectId: String, mcVersion: String, loader: String): ModrinthVersion? =
        listVersions(projectId).firstOrNull { v ->
            (mcVersion.isBlank() || v.gameVersions.contains(mcVersion)) &&
                (loader.isBlank() || v.loaders.contains(loader))
        }

    /**
     * Fetch a mod jar to [target]; a file already there is left alone.
     *
     * Modrinth pins hashes on the version metadata, but the browser hands this
     * function a bare url, so there is nothing here to verify against -- the
     * transfer is retried and resumed, and the jar's own structure is what the
     * content scanner checks afterwards.
     */
    suspend fun downloadTo(url: String, target: Path): Unit = withContext(Dispatchers.IO) {
        if (Files.exists(target)) return@withContext
        transfers.fetch(Transfer(url = url, dest = target, userAgent = USER_AGENT, skip = SkipIfPresent.Presence))
    }

    private suspend inline fun <reified B, reified T> postJson(url: String, body: B): T {
        val resp: HttpResponse = httpProvider.current.post(url) {
            headers.append("User-Agent", USER_AGENT)
            headers.append("Accept", "application/json")
            contentType(ContentType.Application.Json)
            // Encoded here rather than handed over as an object: this client is
            // shared with the mirror's, whose content negotiation is configured
            // elsewhere, and the body's shape is part of the request contract.
            setBody(json.encodeToString(body))
            timeout { requestTimeoutMillis = METADATA_TIMEOUT_MS }
        }
        if (!resp.status.isSuccess()) {
            val text = runCatching { resp.bodyAsText() }.getOrDefault("")
            throw IOException("POST $url failed: ${resp.status} body=$text")
        }
        return json.decodeFromString(resp.bodyAsText())
    }

    private suspend inline fun <reified T> getJson(url: String): T {
        val resp = requestJson(url)
        failUnlessSuccess(resp, url)
        return json.decodeFromString(resp.bodyAsText())
    }

    /**
     * As [getJson], but a 404 is an answer rather than a failure: the catalogue
     * has no such thing. Every other status still throws, so "we could not ask"
     * never reads as "there is nothing to find".
     */
    private suspend inline fun <reified T> getJsonOrNull(url: String): T? {
        val resp = requestJson(url)
        if (resp.status == HttpStatusCode.NotFound) return null
        failUnlessSuccess(resp, url)
        return json.decodeFromString(resp.bodyAsText())
    }

    /** The GET every metadata read here shares: the agent, the accept, the tighter timeout. */
    private suspend fun requestJson(url: String): HttpResponse = httpProvider.current.get(url) {
        headers.append("User-Agent", USER_AGENT)
        headers.append("Accept", "application/json")
        timeout { requestTimeoutMillis = METADATA_TIMEOUT_MS }
    }

    private suspend fun failUnlessSuccess(resp: HttpResponse, url: String) {
        if (resp.status.isSuccess()) return
        val body = runCatching { resp.bodyAsText() }.getOrDefault("")
        throw IOException("GET $url failed: ${resp.status} body=$body")
    }

    companion object {
    /**
     * Metadata reads are bounded far tighter than the shared client's own timeout.
     *
     * That one is sized for a download -- ten minutes, correctly, for a runtime or a
     * pack archive. A catalogue listing is what a person is looking at while it runs,
     * so the same ceiling turns a stall into a spinner that outlasts anyone's
     * patience with no error, no log line and nothing to retry. Past this a stall is
     * an ordinary failure: it throws, it is written down, and the screen offers the
     * retry it already has.
     */
    private const val METADATA_TIMEOUT_MS = 20_000L
        const val API_BASE = "https://api.modrinth.com"
        private const val USER_AGENT = "Nexira-modrinth-client"

        /**
         * Hashes per update query. Modrinth accepts far more, but the body is
         * echoed in full on a failure and a chunked run reports progress that
         * moves; a folder of two hundred mods is two requests either way.
         */
        private const val UPDATE_QUERY_CHUNK = 100

        // Same tolerance as the mirror client: ignore unknown fields so a
        // Modrinth payload that grows new keys does not break the decode.
        val DEFAULT_JSON = Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = false
        }
    }
}

package hivens.launcher.runtime.loader

import hivens.core.api.HttpClientProvider
import hivens.launcher.runtime.MavenCoord
import hivens.core.net.metadataTimeout
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Path

/**
 * Fabric, Quilt and Legacy Fabric resolver. All three expose a meta API that
 * returns a ready launch profile (mainClass + maven libraries, inheriting the
 * vanilla version) with no jar patching, so one resolver serves them -- only the
 * meta base URL and the loader id differ. Verified shapes 2026-05-29, Legacy
 * Fabric 2026-10-06:
 *   fabric:       https://meta.fabricmc.net/v2/versions/loader/<mc>/<ver>/profile/json
 *   quilt:        https://meta.quiltmc.org/v3/versions/loader/<mc>/<ver>/profile/json
 *   legacy-fabric: https://meta.legacyfabric.net/v2/versions/loader/<mc>/<ver>/profile/json
 *
 * Each library entry is `{name, url, sha1?, size?}` where `url` is a maven BASE;
 * the artifact URL is that base + the coordinate's repo path. Quilt omits sha1
 * on some entries -- those download without verification.
 *
 * An entry with a `natives` map is a natives-only artifact in the old Mojang
 * shape: one jar per platform under a classifier, and no plain jar at all. Legacy
 * Fabric ships its own LWJGL 2 this way, and the Java half it ships beside it
 * does not run against vanilla's natives. Such an entry becomes the loader's
 * natives in place of vanilla's for the same artifact, see [nativesOf].
 */
class FabricLikeResolver(
    private val clientProvider: HttpClientProvider,
    private val json: Json,
    override val loaderId: String,
    private val metaBaseUrl: String,
    /** Where a named version's profile is kept, so a relaunch needs no meta call. */
    cacheDir: Path? = null,
) : LoaderResolver {

    private val log = LoggerFactory.getLogger(FabricLikeResolver::class.java)
    private val cache = LoaderSourceCache(cacheDir)

    override suspend fun resolve(mcVersion: String, loaderVersion: String): LoaderProfile =
        withContext(Dispatchers.IO) {
            // A blank loader version is the "pick the default" contract (see
            // LocalPackCreator): resolve the latest, so the URL never carries an
            // empty segment -- `/loader/<mc>//profile/json` is a 404.
            val version = loaderVersion.ifBlank { latestLoaderVersion(mcVersion) }
            val kept = cache.fileFor(loaderId, "$mcVersion-$version", "profile.json")
            val profile = cache.readText(kept)
                ?.let { runCatching { json.decodeFromString(FabricProfileJson.serializer(), it) }.getOrNull() }
                ?: run {
                    val url = "${metaBaseUrl.trimEnd('/')}/versions/loader/$mcVersion/$version/profile/json"
                    log.info("{}: fetching loader profile {}", loaderId, url)
                    val text = fetchText(url)
                    json.decodeFromString(FabricProfileJson.serializer(), text).also { cache.writeText(kept, text) }
                }
            val (natives, plain) = profile.libraries.partition { !it.natives.isNullOrEmpty() }
            val swapped = natives.mapTo(HashSet()) { MavenCoord.parse(it.name).groupArtifact }
            LoaderProfile(
                libraries = plain.map { it.toSpec() },
                mainClass = profile.mainClass,
                version = version,
                nativesOverride = natives.flatMap { nativesOf(it) }.ifEmpty { null },
                // Vanilla's natives for the same artifact go: two LWJGL native sets
                // in one library path load whichever the JVM finds first.
                removeFromBase = { it.groupArtifact in swapped },
            )
        }

    override suspend fun availableVersions(mcVersion: String): List<LoaderVersionOption> = withContext(Dispatchers.IO) {
        val url = "${metaBaseUrl.trimEnd('/')}/versions/loader/$mcVersion"
        json.decodeFromString(ListSerializer(FabricLoaderEntry.serializer()), fetchText(url))
            // Quilt flags no entry stable, which would read as every build a beta.
            .let { entries -> entries.map { LoaderVersionOption(it.loader.version, stable = it.loader.stable || entries.none { e -> e.loader.stable }) } }
    }

    /**
     * The loader version to use when a pack pins none. The meta list
     * (`/versions/loader/<mc>`) is newest-first; prefer the newest STABLE loader,
     * falling back to the newest overall (Quilt entries may not flag stability).
     */
    private suspend fun latestLoaderVersion(mcVersion: String): String {
        val url = "${metaBaseUrl.trimEnd('/')}/versions/loader/$mcVersion"
        val entries = json.decodeFromString(ListSerializer(FabricLoaderEntry.serializer()), fetchText(url))
        val chosen = entries.firstOrNull { it.loader.stable } ?: entries.firstOrNull()
            ?: throw IOException("$loaderId has no loader versions for Minecraft $mcVersion")
        log.info("{}: no loader version pinned; using latest {} for MC {}", loaderId, chosen.loader.version, mcVersion)
        return chosen.loader.version
    }

    /**
     * One spec per platform a natives-only [lib] names, each under its classifier.
     * The provisioner keeps the host's, the same way it filters vanilla's. A
     * classifier still carrying a `${arch}` placeholder names a 32/64-bit split no
     * host here needs, and is dropped.
     */
    internal fun nativesOf(lib: FabricProfileLib): List<LibrarySpec> {
        val coord = MavenCoord.parse(lib.name)
        val base = (lib.url ?: MAVEN_CENTRAL).trimEnd('/')
        return lib.natives.orEmpty().values
            .filterNot { it.contains($$"${") }
            .distinct()
            .map { classifier ->
                val c = coord.copy(classifier = classifier)
                LibrarySpec(coord = c, url = "$base/${c.relativePath}")
            }
    }

    private fun FabricProfileLib.toSpec(): LibrarySpec {
        val coord = MavenCoord.parse(name)
        val base = (url ?: MAVEN_CENTRAL).trimEnd('/')
        return LibrarySpec(coord = coord, url = "$base/${coord.relativePath}", sha1 = sha1, size = size)
    }

    private suspend fun fetchText(url: String): String =
        clientProvider.current.prepareGet(url) { metadataTimeout() }.execute { resp ->
            if (!resp.status.isSuccess()) throw IOException("GET $url -> HTTP ${resp.status}")
            resp.bodyAsText()
        }

    companion object {
        const val FABRIC_META = "https://meta.fabricmc.net/v2"
        const val QUILT_META = "https://meta.quiltmc.org/v3"
        const val LEGACY_FABRIC_META = "https://meta.legacyfabric.net/v2"
        const val MAVEN_CENTRAL = "https://repo1.maven.org/maven2"
    }
}

/** The launch-relevant subset of a Fabric/Quilt meta `profile/json`. */
@Serializable
data class FabricProfileJson(
    val mainClass: String,
    val libraries: List<FabricProfileLib> = emptyList(),
)

@Serializable
data class FabricProfileLib(
    val name: String,
    val url: String? = null,
    val sha1: String? = null,
    val size: Long = 0,
    /** Platform to classifier, on a natives-only entry. See the class KDoc. */
    val natives: Map<String, String>? = null,
)

/** One entry of a Fabric/Quilt meta loader LIST (`/versions/loader/<mc>`), newest first. */
@Serializable
data class FabricLoaderEntry(val loader: FabricLoaderInfo)

@Serializable
data class FabricLoaderInfo(val version: String, val stable: Boolean = false)

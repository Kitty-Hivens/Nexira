package hivens.launcher.runtime.loader

import hivens.core.api.HttpClientProvider
import hivens.launcher.runtime.MavenCoord
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.IOException

/**
 * Fabric + Quilt resolver. Both expose a meta API that returns a ready launch
 * profile (mainClass + maven libraries, inheriting the vanilla version) with no
 * jar patching, so one resolver serves both -- only the meta base URL and the
 * loader id differ. Verified shapes 2026-05-29:
 *   fabric: https://meta.fabricmc.net/v2/versions/loader/<mc>/<ver>/profile/json
 *   quilt:  https://meta.quiltmc.org/v3/versions/loader/<mc>/<ver>/profile/json
 *
 * Each library entry is `{name, url, sha1?, size?}` where `url` is a maven BASE;
 * the artifact URL is that base + the coordinate's repo path. Quilt omits sha1
 * on some entries -- those download without verification.
 */
class FabricLikeResolver(
    private val clientProvider: HttpClientProvider,
    private val json: Json,
    override val loaderId: String,
    private val metaBaseUrl: String,
) : LoaderResolver {

    private val log = LoggerFactory.getLogger(FabricLikeResolver::class.java)

    override suspend fun resolve(mcVersion: String, loaderVersion: String): LoaderProfile =
        withContext(Dispatchers.IO) {
            val url = "${metaBaseUrl.trimEnd('/')}/versions/loader/$mcVersion/$loaderVersion/profile/json"
            log.info("{}: fetching loader profile {}", loaderId, url)
            val profile = json.decodeFromString(FabricProfileJson.serializer(), fetchText(url))
            LoaderProfile(
                libraries = profile.libraries.map { it.toSpec() },
                mainClass = profile.mainClass,
            )
        }

    private fun FabricProfileLib.toSpec(): LibrarySpec {
        val coord = MavenCoord.parse(name)
        val base = (url ?: MAVEN_CENTRAL).trimEnd('/')
        return LibrarySpec(coord = coord, url = "$base/${coord.relativePath}", sha1 = sha1, size = size)
    }

    private suspend fun fetchText(url: String): String =
        clientProvider.current.prepareGet(url).execute { resp ->
            if (!resp.status.isSuccess()) throw IOException("GET $url -> HTTP ${resp.status}")
            resp.bodyAsText()
        }

    companion object {
        const val FABRIC_META = "https://meta.fabricmc.net/v2"
        const val QUILT_META = "https://meta.quiltmc.org/v3"
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
)

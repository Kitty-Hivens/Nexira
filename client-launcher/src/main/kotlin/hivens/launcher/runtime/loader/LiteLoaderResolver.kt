package hivens.launcher.runtime.loader

import hivens.core.api.HttpClientProvider
import hivens.launcher.runtime.MavenCoord
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Path

/**
 * LiteLoader, the launchwrapper tweaker for Minecraft 1.5 to 1.12.2, run on its
 * own.
 *
 * Its index (`dl.liteloader.com/versions/versions.json`) lists, per Minecraft
 * version, a repository and either released artefacts or snapshots, each naming
 * its jar, its tweak class and the libraries it needs. The game starts through
 * launchwrapper with that tweaker, the same shape as launchwrapper-era Forge.
 *
 * Next to Forge it needs nothing from here: FML loads a jar in `mods/` whose
 * manifest names a TweakClass, which LiteLoader's does, so a Forge pack carries
 * it as a mod.
 *
 * The index writes its repositories as plain http, and both answer over https,
 * so every fetch is made over https. A snapshot repository keeps the jar under a
 * timestamped name, read out of its `maven-metadata.xml`, and the md5 the index
 * carries does not match the file it serves, so each jar is held to the sha1 its
 * repository publishes beside it instead. A named version's resolved profile is
 * kept, so a relaunch needs no network.
 */
class LiteLoaderResolver(
    private val clientProvider: HttpClientProvider,
    private val json: Json,
    cacheDir: Path? = null,
    private val versionsUrl: String = VERSIONS_URL,
) : LoaderResolver {

    override val loaderId: String = "liteloader"

    private val log = LoggerFactory.getLogger(LiteLoaderResolver::class.java)
    private val cache = LoaderSourceCache(cacheDir)

    override suspend fun resolve(mcVersion: String, loaderVersion: String): LoaderProfile = withContext(Dispatchers.IO) {
        val named = loaderVersion.trim()
        val kept = if (named.isEmpty()) null else cache.fileFor(loaderId, "$mcVersion-$named", "profile.json")
        val profile = cache.readText(kept)
            ?.let { runCatching { json.decodeFromString(KeptProfile.serializer(), it) }.getOrNull() }
            ?: fetchProfile(mcVersion, named).also {
                cache.writeText(cache.fileFor(loaderId, "$mcVersion-${it.version}", "profile.json"), json.encodeToString(KeptProfile.serializer(), it))
            }
        LoaderProfile(
            libraries = profile.libraries.map { LibrarySpec(MavenCoord.parse(it.name), url = it.url, sha1 = it.sha1) },
            mainClass = LAUNCHWRAPPER_MAIN,
            version = profile.version,
            gameArgs = listOf("--tweakClass", profile.tweakClass),
        )
    }

    override suspend fun availableVersions(mcVersion: String): List<LoaderVersionOption> = withContext(Dispatchers.IO) {
        val entry = indexFor(mcVersion) ?: return@withContext emptyList()
        entry.builds.map { LoaderVersionOption(it.version, stable = it.stable) }.distinctBy { it.version }
    }

    private suspend fun fetchProfile(mcVersion: String, named: String): KeptProfile {
        val entry = indexFor(mcVersion) ?: throw IOException("LiteLoader has no build for Minecraft $mcVersion")
        val build = if (named.isEmpty()) {
            entry.builds.firstOrNull { it.stable } ?: entry.builds.firstOrNull()
        } else {
            entry.builds.firstOrNull { it.version == named }
        } ?: throw IOException("LiteLoader has no build '$named' for Minecraft $mcVersion")
        val jarUrl = jarUrl(entry.repoUrl, entry.repoType, build)
        log.info("liteloader: {} for {} from {}", build.version, mcVersion, jarUrl)
        val jar = KeptLibrary("com.mumfrey:liteloader:${build.version}", jarUrl, sidecarSha1(jarUrl))
        val libraries = build.libraries.map { lib ->
            val coord = MavenCoord.parse(lib.name)
            val base = (lib.url ?: if (coord.group == "net.minecraft") MOJANG_LIBRARIES else MAVEN_CENTRAL).https().trimEnd('/')
            val url = "$base/${coord.relativePath}"
            KeptLibrary(lib.name, url, sidecarSha1(url))
        }
        return KeptProfile(build.version, build.tweakClass, listOf(jar) + libraries)
    }

    /**
     * Where [build]'s jar is. An ivy repository and a released maven one keep it
     * under the name the index gives. A snapshot keeps it under the timestamp and
     * build number of its last deploy, which only the version's own metadata says.
     */
    private suspend fun jarUrl(repoUrl: String, repoType: String, build: Build): String {
        val dir = "${repoUrl.https().trimEnd('/')}/com/mumfrey/liteloader/${build.version}"
        if (repoType != "m2" || !build.version.endsWith("-SNAPSHOT")) return "$dir/${build.file}"
        val metadata = fetchText("$dir/maven-metadata.xml")
        val timestamp = TIMESTAMP.find(metadata)?.groupValues?.get(1)
        val number = BUILD_NUMBER.find(metadata)?.groupValues?.get(1)
        if (timestamp == null || number == null) throw IOException("LiteLoader ${build.version}: the repository names no snapshot build")
        return "$dir/liteloader-${build.version.removeSuffix("-SNAPSHOT")}-$timestamp-$number.jar"
    }

    /** The sha1 a repository publishes beside [url], or null when it publishes none. */
    private suspend fun sidecarSha1(url: String): String? = try {
        fetchText("$url.sha1").trim().take(40).takeIf { SHA1.matches(it) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.info("liteloader: no sha1 published for {}, fetched on https alone", url)
        null
    }

    private class Build(val version: String, val file: String, val tweakClass: String, val libraries: List<IndexLibrary>, val stable: Boolean)
    private class IndexLibrary(val name: String, val url: String?)
    private class IndexEntry(val repoUrl: String, val repoType: String, val builds: List<Build>)

    /** [mcVersion]'s entry in the index, its released builds first, or null when it has none. */
    private suspend fun indexFor(mcVersion: String): IndexEntry? {
        val root = json.parseToJsonElement(fetchText(versionsUrl)).jsonObject
        val version = root["versions"]?.jsonObject?.get(mcVersion)?.jsonObject ?: return null
        val repo = version["repo"]?.jsonObject ?: return null
        val repoUrl = repo.string("url") ?: return null
        val builds = listOf("artefacts" to true, "snapshots" to false).flatMap { (key, stable) ->
            val stream = version[key]?.jsonObject?.get(ARTIFACT)?.jsonObject ?: return@flatMap emptyList()
            // "latest" first, then the rest by the index's own order.
            val ordered = listOfNotNull(stream["latest"]) + stream.filterKeys { it != "latest" }.values
            ordered.mapNotNull { el -> runCatching { el.jsonObject.toBuild(stable) }.getOrNull() }
        }.distinctBy { it.version }
        return IndexEntry(repoUrl, repo.string("type") ?: "m2", builds)
    }

    private fun JsonObject.toBuild(stable: Boolean): Build? {
        val version = string("version") ?: return null
        val file = string("file") ?: return null
        val tweak = string("tweakClass") ?: return null
        val libs = this["libraries"]?.jsonArray.orEmpty().mapNotNull { el ->
            val o = el.jsonObject
            o.string("name")?.let { IndexLibrary(it, o.string("url")) }
        }
        return Build(version, file, tweak, libs, stable)
    }

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun String.https(): String = if (startsWith("http://")) "https://" + removePrefix("http://") else this

    private suspend fun fetchText(url: String): String =
        clientProvider.current.prepareGet(url).execute { resp ->
            if (!resp.status.isSuccess()) throw IOException("GET $url -> HTTP ${resp.status}")
            resp.bodyAsText()
        }

    companion object {
        const val VERSIONS_URL = "https://dl.liteloader.com/versions/versions.json"
        const val MOJANG_LIBRARIES = "https://libraries.minecraft.net"
        const val MAVEN_CENTRAL = "https://repo1.maven.org/maven2"
        const val LAUNCHWRAPPER_MAIN = "net.minecraft.launchwrapper.Launch"
        private const val ARTIFACT = "com.mumfrey:liteloader"
        private val TIMESTAMP = Regex("<timestamp>([^<]+)</timestamp>")
        private val BUILD_NUMBER = Regex("<buildNumber>([^<]+)</buildNumber>")
        private val SHA1 = Regex("^[0-9a-fA-F]{40}$")
    }
}

/** What a resolved LiteLoader build is kept as, so a relaunch needs no network. */
@Serializable
internal data class KeptProfile(val version: String, val tweakClass: String, val libraries: List<KeptLibrary>)

@Serializable
internal data class KeptLibrary(val name: String, val url: String, val sha1: String? = null)

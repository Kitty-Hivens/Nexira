package hivens.launcher.runtime.loader

import hivens.core.api.HttpClientProvider
import hivens.launcher.runtime.MavenCoord
import hivens.core.net.metadataTimeout
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
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
 * carries for a snapshot does not match the file it serves, so a snapshot is held
 * to the sha1 its repository publishes beside it. A release in the older ivy
 * repository has no sha1 beside it, and there the index's md5 is the file's own:
 * each release is filed under it. A named version's resolved profile is kept, so
 * a relaunch needs no network.
 *
 * The index's `latest` entry repeats one of the builds under a shortened file name
 * (`liteloader-1.7.10.jar` for the build filed as `liteloader-1.7.10_04.jar`),
 * which the repository does not serve, so it only says which build is newest and
 * the build itself is read from its own entry.
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
            libraries = profile.libraries.map { LibrarySpec(MavenCoord.parse(it.name), url = it.url, sha1 = it.sha1, md5 = it.md5) },
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
        val jar = if (entry.repoType == "m2") {
            KeptLibrary("com.mumfrey:liteloader:${build.version}", jarUrl, sidecarSha1(jarUrl))
        } else {
            KeptLibrary("com.mumfrey:liteloader:${build.version}", jarUrl, sidecarSha1(jarUrl), md5 = build.md5)
        }
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

    /**
     * The sha1 a repository publishes beside [url], or null when it publishes none.
     * Any other failure is thrown: answered as "none", it would be kept with the
     * profile and leave that build unverified for good.
     */
    private suspend fun sidecarSha1(url: String): String? {
        val text = fetchTextOrNull("$url.sha1")
        if (text == null) log.info("liteloader: no sha1 published for {}", url)
        return text?.trim()?.take(40)?.takeIf { SHA1.matches(it) }
    }

    private class Build(
        val version: String,
        val file: String,
        val tweakClass: String,
        val libraries: List<IndexLibrary>,
        val stable: Boolean,
        val md5: String?,
    )
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
            val latest = runCatching { stream["latest"]?.jsonObject?.string("version") }.getOrNull()
            val filed = stream.filterKeys { it != "latest" }.values
                .mapNotNull { el -> runCatching { el.jsonObject.toBuild(stable) }.getOrNull() }
            // The newest first, the rest in the index's own order. The latest entry
            // itself is used only when no build is filed on its own.
            val own = filed.sortedByDescending { it.version == latest }
            own.ifEmpty { listOfNotNull(runCatching { stream["latest"]?.jsonObject?.toBuild(stable) }.getOrNull()) }
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
        return Build(version, file, tweak, libs, stable, string("md5"))
    }

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun String.https(): String = if (startsWith("http://")) "https://" + removePrefix("http://") else this

    private suspend fun fetchText(url: String): String =
        fetchTextOrNull(url) ?: throw IOException("GET $url -> HTTP 404")

    /** The body at [url], or null when it is not there. Any other failure throws. */
    private suspend fun fetchTextOrNull(url: String): String? =
        clientProvider.current.prepareGet(url) { metadataTimeout() }.execute { resp ->
            if (resp.status == HttpStatusCode.NotFound) return@execute null
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
internal data class KeptLibrary(val name: String, val url: String, val sha1: String? = null, val md5: String? = null)

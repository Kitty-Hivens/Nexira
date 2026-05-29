package hivens.launcher.runtime.loader

import hivens.core.api.HttpClientProvider
import hivens.launcher.runtime.MavenCoord
import hivens.launcher.runtime.MojangArguments
import hivens.launcher.runtime.MojangLibrary
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.copyTo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * Forge resolver for the launchwrapper era (<= 1.12.2). These versions need NO
 * install-time processors: the official vanilla client plus the forge libraries
 * on the classpath is enough (validated live 2026-05-29 -- the official client
 * + canonical Forge ran the full Industrial pack; see
 * [[project_industrial_mechanics]]). Modern Forge / NeoForge (1.13+) DO patch
 * the client and are a separate resolver.
 *
 * The official installer carries a `version.json` that is a vanilla overlay:
 * `mainClass` = launchwrapper, the forge library set, and
 * `--tweakClass net.minecraftforge.fml.common.launcher.FMLTweaker` inside
 * `minecraftArguments`. Library artifacts with a URL are downloaded by the
 * provisioner; the forge universal jar carries an EMPTY url and lives in the
 * installer's `maven/<path>` tree, so it is returned as bundled bytes.
 */
class ForgeLegacyResolver(
    private val clientProvider: HttpClientProvider,
    private val json: Json,
    private val forgeMavenBase: String = FORGE_MAVEN,
) : LoaderResolver {

    override val loaderId: String = "forge"

    private val log = LoggerFactory.getLogger(ForgeLegacyResolver::class.java)

    override suspend fun resolve(mcVersion: String, loaderVersion: String): LoaderProfile =
        withContext(Dispatchers.IO) {
            val build = resolveForgeBuild(mcVersion, loaderVersion)
            val slug = "$mcVersion-$build"
            val installerUrl =
                "${forgeMavenBase.trimEnd('/')}/net/minecraftforge/forge/$slug/forge-$slug-installer.jar"
            log.info("forge: fetching installer {}", installerUrl)
            val installer = Files.createTempFile("forge-$slug-installer", ".jar")
            try {
                downloadTo(installerUrl, installer)
                ZipFile(installer.toFile()).use { zip ->
                    val versionEntry = zip.getEntry("version.json")
                        ?: throw IOException("forge installer $slug has no version.json")
                    val version = json.decodeFromString(
                        LoaderVersionJson.serializer(),
                        zip.getInputStream(versionEntry).readBytes().decodeToString(),
                    )
                    LoaderProfile(
                        libraries = version.libraries.map { toSpec(it, zip) },
                        mainClass = version.mainClass,
                        gameArgs = extractTweakArgs(version.minecraftArguments),
                    )
                }
            } finally {
                Files.deleteIfExists(installer)
            }
        }

    private fun toSpec(lib: MojangLibrary, zip: ZipFile): LibrarySpec {
        val coord = MavenCoord.parse(lib.name)
        val artifact = lib.downloads?.artifact
            ?: throw IOException("forge library ${lib.name} has no downloads.artifact")
        if (artifact.url.isNotBlank()) {
            return LibrarySpec(coord, url = artifact.url, sha1 = artifact.sha1, size = artifact.size)
        }
        val entry = zip.getEntry("maven/${artifact.path}")
            ?: throw IOException("forge bundled library missing from installer: maven/${artifact.path}")
        return LibrarySpec(
            coord = coord,
            sha1 = artifact.sha1,
            size = artifact.size,
            bundled = zip.getInputStream(entry).readBytes(),
        )
    }

    /**
     * Extracts only the forge-added `--tweakClass <x>` from the installer's
     * `minecraftArguments`; the vanilla game args (username, gameDir, ...) are
     * produced by the command builder. Falls back to the canonical FML tweaker.
     */
    internal fun extractTweakArgs(minecraftArguments: String?): List<String> {
        val tokens = minecraftArguments?.trim()?.split(Regex("\\s+")).orEmpty()
        val out = ArrayList<String>()
        var i = 0
        while (i < tokens.size) {
            if (tokens[i] == "--tweakClass" && i + 1 < tokens.size) {
                out += "--tweakClass"
                out += tokens[i + 1]
                i += 2
            } else {
                i++
            }
        }
        return out.ifEmpty { DEFAULT_TWEAK_ARGS }
    }

    /**
     * The Forge build to actually install. Returns [requested] when it is
     * published on Forge maven; otherwise the latest official build for
     * [mcVersion]. SmartyCraft declares custom/patched build numbers that were
     * never released (e.g. 1.12.2-14.23.5.2922; official 1.12.2 tops out at
     * 2864) -- a nearby official build runs the same pack, since the FML
     * handshake matches on the mod list, not the Forge build.
     */
    internal suspend fun resolveForgeBuild(mcVersion: String, requested: String): String {
        val builds = forgeBuildsFor(mcVersion)
        if (builds.isEmpty()) throw IOException("no Forge builds for Minecraft $mcVersion on Forge maven")
        if (requested in builds) return requested
        val latest = builds.maxWith { a, b -> compareForgeBuilds(a, b) }
        log.warn(
            "forge build {} is not published for {} (custom/non-official?); using nearest official {}",
            requested, mcVersion, latest,
        )
        return latest
    }

    private suspend fun forgeBuildsFor(mcVersion: String): List<String> {
        val url = "${forgeMavenBase.trimEnd('/')}/net/minecraftforge/forge/maven-metadata.xml"
        val versionPattern = Regex("<version>${Regex.escape(mcVersion)}-([^<]+)</version>")
        return versionPattern.findAll(fetchText(url)).map { it.groupValues[1] }.toList()
    }

    private suspend fun fetchText(url: String): String =
        clientProvider.current.prepareGet(url).execute { resp ->
            if (!resp.status.isSuccess()) throw IOException("GET $url -> HTTP ${resp.status}")
            resp.bodyAsText()
        }

    /** Element-wise numeric compare of dotted Forge build strings (14.23.5.2864). */
    internal fun compareForgeBuilds(a: String, b: String): Int {
        val ai = a.split('.')
        val bi = b.split('.')
        for (i in 0 until maxOf(ai.size, bi.size)) {
            val x = ai.getOrNull(i)?.toIntOrNull() ?: 0
            val y = bi.getOrNull(i)?.toIntOrNull() ?: 0
            if (x != y) return x - y
        }
        return 0
    }

    private suspend fun downloadTo(url: String, dest: Path) {
        clientProvider.current.prepareGet(url).execute { resp ->
            if (!resp.status.isSuccess()) throw IOException("GET $url -> HTTP ${resp.status}")
            FileOutputStream(dest.toFile()).use { out -> resp.bodyAsChannel().copyTo(out) }
        }
    }

    companion object {
        const val FORGE_MAVEN = "https://maven.minecraftforge.net"
        val DEFAULT_TWEAK_ARGS = listOf("--tweakClass", "net.minecraftforge.fml.common.launcher.FMLTweaker")
    }
}

/**
 * The launch-profile subset of a loader installer's `version.json` -- a vanilla
 * overlay. Distinct from [hivens.launcher.runtime.MojangVersion] (the full
 * vanilla profile) because an overlay omits `assetIndex` / `downloads`
 * (inherited from vanilla). Legacy (<=1.12.2) overlays carry a flat
 * `minecraftArguments` string; modern (1.13+) overlays carry the structured
 * `arguments` block + `inheritsFrom` instead.
 */
@Serializable
data class LoaderVersionJson(
    val mainClass: String,
    val libraries: List<MojangLibrary> = emptyList(),
    val minecraftArguments: String? = null,
    val inheritsFrom: String? = null,
    val arguments: MojangArguments? = null,
)

package hivens.launcher.runtime.loader

import hivens.core.api.HttpClientProvider
import hivens.core.platform.Platform
import hivens.launcher.runtime.MavenCoord
import hivens.launcher.runtime.MojangLibrary
import hivens.launcher.runtime.flattenArguments
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.IOException

/**
 * lwjgl3ify resolver: Minecraft 1.7.10 modernised onto LWJGL3 and Java 21+.
 *
 * Unlike Cleanroom (a legacy launchwrapper profile) lwjgl3ify ships a MODERN
 * `arguments` profile even though the game is 1.7.10. Verified against the 3.0.x
 * release `version.json` asset (self-contained, `inheritsFrom` null): the main
 * class is RetroFuturaBootstrap's `MainStartOnFirstThread`, the jvm args carry
 * `-Djava.system.class.loader=...RfbSystemClassLoader` plus the add-opens block
 * under os rules, and RFB itself is shaded into the `lwjgl3ify:forgePatches`
 * jar. Forge 1.7.10, scala, the vanilla libraries and the LWJGL3 set are all
 * resolved to Maven Central / the GTNH nexus in that one file.
 *
 * Two shapes differ from Cleanroom and are handled here:
 *  - the LWJGL3 natives are separate artifacts with the platform in the NAME
 *    (`org.lwjgl:lwjgl-opengl-natives-linux:3.4.2`), not a classifier;
 *    [MavenCoord.nativeClassifier] normalises both, so the native/classpath
 *    partition and the host filter work unchanged.
 *  - the jvm args are flattened for this host ([flattenArguments] applies the os
 *    rules) and the launcher-owned tokens (`-cp ${classpath}`,
 *    `-Djava.library.path`) are stripped. Nothing with a `${}` placeholder
 *    survives, so the command builder takes its flat-classpath path -- the
 *    1.7.10 client belongs ON `-cp`, which the module-path path would drop.
 *
 * The LWJGL swap reuses the loader model: [LoaderProfile.removeFromBase] drops
 * the vanilla LWJGL2 group and [LoaderProfile.nativesOverride] adds the LWJGL3
 * host natives, while unrelated vanilla natives (jinput) survive.
 *
 * Empty-url library entries (jinput / twitch platform natives) are skipped: they
 * carry no download source here because they come from the vanilla 1.7.10 base,
 * which the provisioner already resolves and merges under the overlay.
 *
 * Java 21 is a fixed override -- practically the highest the RFB stack targets;
 * the profile itself declares no Java major.
 */
class Lwjgl3ifyResolver(
    private val clientProvider: HttpClientProvider,
    private val json: Json,
    osName: String = System.getProperty("os.name", ""),
    private val releaseBase: String = LWJGL3IFY_RELEASES,
) : LoaderResolver {

    override val loaderId: String = "lwjgl3ify"

    private val log = LoggerFactory.getLogger(Lwjgl3ifyResolver::class.java)
    private val mojangOs: String = Platform.classify(osName).mojang

    override suspend fun resolve(mcVersion: String, loaderVersion: String): LoaderProfile =
        withContext(Dispatchers.IO) {
            val url = "${releaseBase.trimEnd('/')}/$loaderVersion/version.json"
            log.info("lwjgl3ify: fetching profile {}", url)
            val text = clientProvider.current.prepareGet(url).execute { resp ->
                if (!resp.status.isSuccess()) {
                    throw IOException("lwjgl3ify $loaderVersion: GET $url -> HTTP ${resp.status}")
                }
                resp.bodyAsText()
            }
            buildProfile(json.decodeFromString(LoaderVersionJson.serializer(), text))
        }

    /**
     * Translates the release `version.json` into a launch profile. Pure over the
     * parsed model (host os fixed at construction), so it is tested against a
     * real profile without a download.
     */
    internal fun buildProfile(version: LoaderVersionJson): LoaderProfile {
        val specs = version.libraries.mapNotNull { toSpecOrNull(it) }
        val (natives, classpath) = specs.partition { it.coord.nativeClassifier != null }
        val args = version.arguments
        return LoaderProfile(
            libraries = classpath,
            mainClass = version.mainClass,
            jvmArgs = stripCommandOwnedArgs(flattenArguments(args?.jvm.orEmpty(), mojangOs)),
            gameArgs = extractTweakClassArgs(flattenArguments(args?.game.orEmpty(), mojangOs)),
            removeFromBase = { it.group == LWJGL2_GROUP },
            nativesOverride = natives,
            javaMajor = LWJGL3IFY_JAVA_MAJOR,
        )
    }

    /** A version.json library as a Maven download spec, or null when its url is
     *  empty -- those entries (jinput / twitch platform natives) come from the
     *  vanilla 1.7.10 base and the provisioner resolves them there. */
    private fun toSpecOrNull(lib: MojangLibrary): LibrarySpec? {
        val artifact = lib.downloads?.artifact ?: return null
        val url = artifact.url.ifBlank { return null }
        return LibrarySpec(MavenCoord.parse(lib.name), url = url, sha1 = artifact.sha1, size = artifact.size)
    }

    /**
     * Drops the tokens the launch command emits for itself -- its own
     * `-cp ${classpath}` pair and `-Djava.library.path` -- from the flattened jvm
     * args, leaving RFB's system-classloader arg and the add-opens block. None of
     * those carry a `${}` placeholder, so the command builder keeps this on the
     * flat-classpath path (client jar on `-cp`), which 1.7.10 requires.
     */
    internal fun stripCommandOwnedArgs(flat: List<String>): List<String> {
        val out = ArrayList<String>(flat.size)
        var i = 0
        while (i < flat.size) {
            val token = flat[i]
            when {
                token == "-cp" || token == "-classpath" || token == "--class-path" ->
                    i += if (i + 1 < flat.size) 2 else 1
                token == $$"${classpath}" -> i += 1
                token.startsWith("-Djava.library.path") -> i += 1
                else -> {
                    out += token
                    i += 1
                }
            }
        }
        return out
    }

    companion object {
        const val LWJGL3IFY_RELEASES = "https://github.com/GTNewHorizons/lwjgl3ify/releases/download"
        /** Vanilla's LWJGL2 maven group, dropped so LWJGL3 is the only LWJGL on -cp. */
        const val LWJGL2_GROUP = "org.lwjgl.lwjgl"
        /** Target Java major; the profile declares none, upstream targets 17-21. */
        const val LWJGL3IFY_JAVA_MAJOR = 21
    }
}

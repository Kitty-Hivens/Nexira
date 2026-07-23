package hivens.launcher.runtime.loader

import hivens.core.api.HttpClientProvider
import hivens.launcher.runtime.MavenCoord
import hivens.launcher.runtime.MojangLibrary
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.copyTo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * Cleanroom loader resolver: Minecraft 1.12.2 modernised onto LWJGL3 and
 * Java 25.
 *
 * Cleanroom is launchwrapper-family, structurally the same as
 * [ForgeLegacyResolver] and NOT a modern module-path loader. Verified against
 * the 0.6.x installer: `install_profile.json` carries an empty processor list
 * (the vanilla client is never patched), and the self-contained `version.json`
 * (`inheritsFrom` null) already resolves every library to Maven Central, with
 * the Cleanroom core jar bundled in the installer's `maven/` tree under an
 * empty download url -- the same shape as the Forge universal jar. Main class
 * is `top.outlands.foundation.boot.Foundation`; FML's tweaker rides in the flat
 * `minecraftArguments`.
 *
 * What makes it more than a Forge overlay is the LWJGL swap, which the loader
 * model now expresses directly:
 *  - [LoaderProfile.removeFromBase] drops the vanilla base's LWJGL2 group. The
 *    installer json itself carries no LWJGL2, but our vanilla base does, and an
 *    additive merge would leave it on `-cp` next to LWJGL3 (different maven
 *    group, so no collision to override it).
 *  - [LoaderProfile.nativesOverride] takes the LWJGL3 natives (all platforms
 *    declared; the provisioner keeps this host's), so the extracted binaries
 *    match the LWJGL3 classes instead of vanilla's LWJGL2 `.so`/`.dll`.
 *
 * Java 25 is a fixed override: no Cleanroom artifact states the required Java
 * major machine-readably -- the installer json, `install_profile.json`, and the
 * MMC `compatibleJavaMajors` field are all silent, only the upstream docs say
 * "Java 25+". Revisit when Cleanroom bumps its baseline.
 */
class CleanroomResolver(
    private val clientProvider: HttpClientProvider,
    private val json: Json,
    private val releaseBase: String = CLEANROOM_RELEASES,
) : LoaderResolver {

    override val loaderId: String = "cleanroom"

    private val log = LoggerFactory.getLogger(CleanroomResolver::class.java)

    override suspend fun resolve(mcVersion: String, loaderVersion: String): LoaderProfile =
        withContext(Dispatchers.IO) {
            val installerUrl =
                "${releaseBase.trimEnd('/')}/$loaderVersion/cleanroom-$loaderVersion-installer.jar"
            log.info("cleanroom: fetching installer {}", installerUrl)
            val installer = Files.createTempFile("cleanroom-$loaderVersion-installer", ".jar")
            try {
                downloadTo(installerUrl, installer)
                ZipFile(installer.toFile()).use { zip ->
                    val versionEntry = zip.getEntry("version.json")
                        ?: throw IOException("cleanroom installer $loaderVersion has no version.json")
                    val version = json.decodeFromString(
                        LoaderVersionJson.serializer(),
                        zip.getInputStream(versionEntry).readBytes().decodeToString(),
                    )
                    buildProfile(
                        version.mainClass,
                        version.minecraftArguments,
                        version.libraries.map { toSpec(it, zip) },
                    )
                }
            } finally {
                Files.deleteIfExists(installer)
            }
        }

    /**
     * Turns the installer's flat library set into the launch profile. Every
     * `natives-*` classifier is routed to the native override (extracted, per
     * host); the remaining jars -- the LWJGL3 base modules plus the loader's own
     * libraries -- form the classpath overlay. Pure over [specs] so it is tested
     * against a real installer `version.json` without a download.
     */
    internal fun buildProfile(
        mainClass: String,
        minecraftArguments: String?,
        specs: List<LibrarySpec>,
    ): LoaderProfile {
        val (natives, classpath) = specs.partition { (it.coord.classifier ?: "").startsWith("natives") }
        return LoaderProfile(
            libraries = classpath,
            mainClass = mainClass,
            gameArgs = extractTweakClassArgs(minecraftArguments),
            removeFromBase = { it.group == LWJGL2_GROUP },
            nativesOverride = natives,
            javaMajor = CLEANROOM_JAVA_MAJOR,
        )
    }

    /** A version.json library as a download spec: a Maven-Central url when the
     *  entry carries one, else the bytes bundled in the installer's `maven/`
     *  tree (the Cleanroom core jar, published with an empty url). */
    private fun toSpec(lib: MojangLibrary, zip: ZipFile): LibrarySpec {
        val coord = MavenCoord.parse(lib.name)
        val artifact = lib.downloads?.artifact
            ?: throw IOException("cleanroom library ${lib.name} has no downloads.artifact")
        if (artifact.url.isNotBlank()) {
            return LibrarySpec(coord, url = artifact.url, sha1 = artifact.sha1, size = artifact.size)
        }
        val entry = zip.getEntry("maven/${artifact.path}")
            ?: throw IOException("cleanroom bundled library missing from installer: maven/${artifact.path}")
        return LibrarySpec(
            coord = coord,
            sha1 = artifact.sha1,
            size = artifact.size,
            bundled = zip.getInputStream(entry).readBytes(),
        )
    }

    private suspend fun downloadTo(url: String, dest: Path) {
        clientProvider.current.prepareGet(url).execute { resp ->
            if (!resp.status.isSuccess()) throw IOException("GET $url -> HTTP ${resp.status}")
            FileOutputStream(dest.toFile()).use { out -> resp.bodyAsChannel().copyTo(out) }
        }
    }

    companion object {
        const val CLEANROOM_RELEASES = "https://github.com/CleanroomMC/Cleanroom/releases/download"
        /** Vanilla's LWJGL2 maven group -- dropped from the base so LWJGL3 (group
         *  `org.lwjgl`) is the only LWJGL on the classpath. */
        const val LWJGL2_GROUP = "org.lwjgl.lwjgl"
        /** Required Java major, from upstream docs (not declared in any artifact). */
        const val CLEANROOM_JAVA_MAJOR = 25
    }
}

package hivens.launcher.runtime.loader

import hivens.core.api.HttpClientProvider
import hivens.core.net.SkipIfPresent
import hivens.core.net.Transfer
import hivens.core.net.TransferEngine
import hivens.core.platform.Arch
import hivens.core.platform.OS
import hivens.launcher.runtime.MavenCoord
import hivens.launcher.runtime.MojangArtifact
import hivens.launcher.runtime.MojangLibrary
import hivens.launcher.runtime.libraryRulesAllow
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
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
    private val transfers: TransferEngine,
    private val json: Json,
    private val releaseBase: String = CLEANROOM_RELEASES,
    private val releasesApi: String = CLEANROOM_RELEASES_API,
    /** Where a version's installer is kept, so a relaunch downloads nothing. */
    cacheDir: Path? = null,
    /** The host's rules name and whether it is arm64, read off the machine unless a test names them. */
    private val mojangOs: String = OS.platform.mojang,
    private val arm64: Boolean = OS.arch == Arch.ARM64,
) : LoaderResolver {

    override val loaderId: String = "cleanroom"

    private val log = LoggerFactory.getLogger(CleanroomResolver::class.java)
    private val cache = LoaderSourceCache(cacheDir)

    override suspend fun resolve(mcVersion: String, loaderVersion: String): LoaderProfile =
        withContext(Dispatchers.IO) {
            // The only index of its releases is GitHub's rate-limited API, which a
            // launch does not ask, so a blank version has nothing to resolve to. It
            // used to reach the URL as an empty segment and come back as a bare 404.
            if (loaderVersion.isBlank()) throw IOException("Cleanroom needs a version to install, and none was given")
            // It modernises one Minecraft version and nothing else, and layered onto
            // another it assembled a classpath that could only crash.
            if (mcVersion != CLEANROOM_MINECRAFT) throw IOException("Cleanroom runs on Minecraft $CLEANROOM_MINECRAFT, not $mcVersion")
            val kept = cache.fileFor(loaderId, loaderVersion, "installer.jar")
            kept?.takeIf { Files.isRegularFile(it) }?.let { cached ->
                runCatching { profileFrom(cached, loaderVersion) }
                    .onFailure { cache.discard(cached) }
                    .getOrNull()
                    ?.let { return@withContext it }
            }
            val installerUrl =
                "${releaseBase.trimEnd('/')}/$loaderVersion/cleanroom-$loaderVersion-installer.jar"
            log.info("cleanroom: fetching installer {}", installerUrl)
            val installer = kept ?: Files.createTempFile("cleanroom-$loaderVersion-installer", ".jar")
            try {
                downloadTo(installerUrl, installer)
                profileFrom(installer, loaderVersion)
            } finally {
                if (kept == null) Files.deleteIfExists(installer)
            }
        }

    private fun profileFrom(installer: Path, loaderVersion: String): LoaderProfile =
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
                hostLibraries(version.libraries).flatMap { toSpecs(it, zip) },
            ).copy(version = loaderVersion)
        }

    override suspend fun availableVersions(mcVersion: String): List<LoaderVersionOption> =
        if (mcVersion != CLEANROOM_MINECRAFT) emptyList()
        else withContext(Dispatchers.IO) {
            githubReleaseVersions(clientProvider, json, releasesApi) { "cleanroom-$it-installer.jar" }
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
        val (natives, classpath) = specs.partition { it.coord.nativeClassifier != null }
        return LoaderProfile(
            libraries = classpath,
            mainClass = mainClass,
            version = "",
            gameArgs = extractTweakClassArgs(minecraftArguments),
            // Cleanroom's installer version.json is a complete, self-contained set
            // (inheritsFrom null). Replacing the vanilla libraries wholesale drops
            // the cross-coord twins vanilla would otherwise leak in (old oshi/icu/
            // netty shadowing Cleanroom's) -- verified live: the merge left
            // oshi-project:oshi-core:1.1 ahead of com.github.oshi and LWJGL's
            // Display.create hit NoSuchMethodError getGraphicsCards().
            replacesVanillaLibraries = true,
            nativesOverride = natives,
            javaMajor = CLEANROOM_JAVA_MAJOR,
        )
    }

    /**
     * The libraries this host runs, by their rules, as the modern installer path
     * reads them. The json lists every platform's: two text2speech builds, one for
     * Apple Silicon and one for everything else, and without the rules the merge
     * kept whichever came last and put the Apple Silicon one on every machine.
     *
     * A native named with its classifier is left for the provisioner, which keeps
     * this host's by it. One listed under its library's plain name is told apart by
     * its rules alone.
     */
    internal fun hostLibraries(libraries: List<MojangLibrary>): List<MojangLibrary> =
        libraries.filter { lib ->
            MavenCoord.parse(lib.name).nativeClassifier != null || libraryRulesAllow(lib.rules, mojangOs, arm64)
        }

    /**
     * The jars one version.json library stands for. Up to 0.6 a native was a library
     * of its own, named with its classifier. From 0.7 it is an entry under its
     * library's plain name whose only jar is among the classifiers, so it is given
     * back the name it had.
     */
    private fun toSpecs(lib: MojangLibrary, zip: ZipFile): List<LibrarySpec> {
        val coord = MavenCoord.parse(lib.name)
        val jars = listOfNotNull(lib.downloads?.artifact?.let { coord to it }) +
            lib.downloads?.classifiers.orEmpty()
                .filterKeys { it.startsWith("natives") }
                .map { (classifier, jar) -> coord.copy(classifier = classifier) to jar }
        if (jars.isEmpty()) throw IOException("cleanroom library ${lib.name} has nothing to download")
        return jars.map { (named, jar) -> toSpec(named, jar, zip) }
    }

    /** One jar as a download spec: a Maven-Central url when it carries one, else the
     *  bytes bundled in the installer's `maven/` tree (the Cleanroom core jar,
     *  published with an empty url). */
    private fun toSpec(coord: MavenCoord, artifact: MojangArtifact, zip: ZipFile): LibrarySpec {
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

    /**
     * The installer jar, which the official maven pins with nothing but HTTPS -- its
     * version is chosen at runtime, so there is no hash to check it against. Staged
     * through the engine anyway: a cut transfer is retried and resumed instead of
     * leaving a truncated jar at the final path for the installer to choke on.
     */
    private suspend fun downloadTo(url: String, dest: Path) {
        transfers.fetch(Transfer(url = url, dest = dest, skip = SkipIfPresent.Never))
    }

    companion object {
        const val CLEANROOM_RELEASES = "https://github.com/CleanroomMC/Cleanroom/releases/download"
        const val CLEANROOM_RELEASES_API = "https://api.github.com/repos/CleanroomMC/Cleanroom/releases?per_page=50"
        /** The one Minecraft version Cleanroom builds on. */
        const val CLEANROOM_MINECRAFT = "1.12.2"
        /** Required Java major, from upstream docs (not declared in any artifact). */
        const val CLEANROOM_JAVA_MAJOR = 25
    }
}

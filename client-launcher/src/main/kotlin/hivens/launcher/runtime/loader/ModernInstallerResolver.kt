package hivens.launcher.runtime.loader

import hivens.core.api.HttpClientProvider
import hivens.core.net.SkipIfPresent
import hivens.core.net.Transfer
import hivens.core.net.TransferEngine
import hivens.core.io.deleteTree
import hivens.core.api.interfaces.IJavaManager
import hivens.core.platform.OS
import hivens.launcher.runtime.MavenCoord
import hivens.launcher.runtime.MojangLibrary
import hivens.launcher.runtime.flattenArguments
import hivens.launcher.runtime.libraryRulesAllow
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Resolver for the modern era (Forge 1.13+ and all NeoForge), where the
 * loader's install processors binpatch the client jar -- a step too large and
 * version-volatile to reimplement. Instead this runs the OFFICIAL installer
 * headless via the managed JDK and consumes what it produces, so upstream
 * changes track for free and no copyrighted bits are redistributed.
 *
 * The installer runs once into a persistent per-`(loader, mc, version)` cache
 * (the `--installClient` target, shaped like a `.minecraft` dir). Re-launches
 * see the completion marker and skip the multi-minute install. The produced
 * `versions/<id>/<id>.json` is a vanilla overlay (`inheritsFrom`, the modern
 * `arguments` block, the forge/neoforge library set incl. processor-output
 * jars that exist on no maven); its libraries are handed to the provisioner as
 * [LibrarySpec.localFile] copies into the shared root.
 *
 * The two loaders differ only by maven base + installer coordinate, so the
 * [neoforge] / [forge] factories parameterise one implementation.
 */
class ModernInstallerResolver(
    private val clientProvider: HttpClientProvider,
    private val transfers: TransferEngine,
    private val json: Json,
    private val javaManager: IJavaManager,
    private val cacheDir: Path,
    override val loaderId: String,
    /**
     * Java major to run the official installer under. Null means derive from the
     * MC version via [IJavaManager.detectJavaVersion] -- matches every current
     * loader (Forge / NeoForge target the MC version's own JDK). Pass the
     * loader's declared major when a future loader needs a different one
     * (e.g. Cleanroom -> 25) so the installer JDK matches the GAME's JDK.
     */
    private val installerJavaMajor: Int? = null,
    /**
     * The default (latest) loader version for a Minecraft version, resolved when a
     * pack pins none (the blank-version contract, see LocalPackCreator). Forge reads
     * its promotions, NeoForge its version index.
     */
    private val latestVersion: suspend (mcVersion: String) -> String,
    private val installerUrl: (mcVersion: String, loaderVersion: String) -> String,
) : LoaderResolver {

    private val log = LoggerFactory.getLogger(ModernInstallerResolver::class.java)

    override suspend fun resolve(mcVersion: String, loaderVersion: String): LoaderProfile =
        withContext(Dispatchers.IO) {
            // Blank = "use the default": resolve the latest so the installer URL
            // never carries an empty version segment (which 404s).
            val resolvedVersion = loaderVersion.ifBlank { latestVersion(mcVersion) }
            val dotMinecraft = cacheDir.resolve("$loaderId-$mcVersion-$resolvedVersion".replace(Regex("[^A-Za-z0-9._-]"), "_"))
            // One install per cache directory at a time. A second resolve that found
            // no marker deleted the directory the first installer was still writing
            // into; this is what happened when a stopped launch was followed by Play.
            installLock(dotMinecraft).withLock { ensureInstalled(mcVersion, resolvedVersion, dotMinecraft) }

            val versionJsonPath = locateVersionJson(dotMinecraft, mcVersion)
            val version = json.decodeFromString(
                LoaderVersionJson.serializer(),
                Files.readString(versionJsonPath),
            )
            val os = OS.platform.mojang
            LoaderProfile(
                // Rules first: a version json lists every platform's libraries, and
                // the installer only produces the ones this host needs. Harvesting a
                // mac-only entry (ca.weblite:java-objc-bridge) on Windows fails the
                // whole install over a file that was never meant to be there.
                libraries = version.libraries
                    .filter { libraryRulesAllow(it.rules, os) }
                    .map { harvest(it, dotMinecraft) },
                mainClass = version.mainClass,
                jvmArgs = version.arguments?.let { flattenArguments(it.jvm, os) } ?: emptyList(),
                gameArgs = version.arguments?.let { flattenArguments(it.game, os) } ?: emptyList(),
                placeOnlyFiles = collectPlaceOnly(dotMinecraft),
                inheritsVanillaArguments = true,
            )
        }

    /**
     * Every jar the installer placed under `<dotMinecraft>/libraries/`, as
     * place-only files. This is a superset of the version json's libraries: it
     * also carries the processor outputs (SRG/slim/extra client, neoforge
     * universal/client) that FML resolves by path at runtime but never lists as
     * classpath libraries. Copying the whole tree (skip-if-present) guarantees
     * the loader finds everything under `libraryDirectory`; the cp set still
     * comes only from [harvest]ed version.libraries.
     */
    internal fun collectPlaceOnly(dotMinecraft: Path): List<PlaceOnlyFile> {
        val libsRoot = dotMinecraft.resolve("libraries")
        if (!Files.isDirectory(libsRoot)) return emptyList()
        val out = ArrayList<PlaceOnlyFile>()
        Files.walkFileTree(libsRoot, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                out.add(PlaceOnlyFile(libsRoot.relativize(file).toString().replace('\\', '/'), file))
                return FileVisitResult.CONTINUE
            }
        })
        return out
    }

    /**
     * Runs the installer into [dotMinecraft] unless a prior run completed.
     *
     * Names its own dispatcher rather than borrowing the caller's thread: the body
     * is disk work start to finish and ends in a spawned installer, which is
     * minutes of blocking on whatever thread happened to call in.
     */
    private suspend fun ensureInstalled(
        mcVersion: String,
        loaderVersion: String,
        dotMinecraft: Path,
    ) = withContext(Dispatchers.IO) {
        val marker = dotMinecraft.resolve(INSTALLED_MARKER)
        if (Files.isRegularFile(marker)) {
            log.info("{} {} already installed in cache, skipping installer", loaderId, loaderVersion)
            return@withContext
        }
        // A leftover dir with no marker is a failed prior run -- start clean so
        // the installer never appends to half-written state.
        deleteTree(dotMinecraft)
        Files.createDirectories(dotMinecraft)
        // The installer's --installClient mode requires a launcher_profiles.json
        // in the target; it adds a profile entry there.
        Files.writeString(dotMinecraft.resolve("launcher_profiles.json"), LAUNCHER_PROFILES_STUB)

        val installer = dotMinecraft.resolve("installer.jar")
        // Trust model: the installer jar is not sha-pinned (its version is chosen
        // at runtime), so integrity rests on HTTPS to the official loader maven --
        // the installerUrl factories hardcode maven.neoforged.net /
        // maven.minecraftforge.net. Same trust the reference launchers (Prism) use.
        val url = installerUrl(mcVersion, loaderVersion)
        log.info("{}: downloading installer {}", loaderId, url)
        downloadTo(url, installer)

        val major = installerJavaMajor ?: javaManager.detectJavaVersion(mcVersion)
        val java = javaManager.getJavaPathForMajor(major)
        runInstaller(java, installer, dotMinecraft)

        Files.deleteIfExists(installer)
        Files.writeString(marker, "$loaderId $loaderVersion\n")
    }

    /** Maps one produced overlay library to a copy-from-cache spec; the file
     *  was placed in `<dotMinecraft>/libraries/` by the installer. */
    internal fun harvest(lib: MojangLibrary, dotMinecraft: Path): LibrarySpec {
        val coord = MavenCoord.parse(lib.name)
        val artifact = lib.downloads?.artifact
        val relPath = artifact?.path?.takeIf { it.isNotBlank() } ?: coord.relativePath
        val file = dotMinecraft.resolve("libraries").resolve(relPath)
        if (!Files.isRegularFile(file)) {
            throw IOException("$loaderId installer did not produce library $relPath (for ${lib.name})")
        }
        return LibrarySpec(
            coord = coord,
            sha1 = artifact?.sha1?.takeIf { it.isNotBlank() },
            size = artifact?.size ?: 0,
            localFile = file,
        )
    }

    /**
     * The loader version json the installer generated.
     *
     * An `--installClient` target holds TWO `versions/<id>/` dirs, not one: the
     * loader's own overlay and the vanilla json the installer downloaded for the
     * client-jar step (`versions/1.21.1/` beside `versions/neoforge-21.1.186/`).
     * `Files.newDirectoryStream` has no defined order, so taking whichever came
     * first was a coin flip -- and on a filesystem that enumerates by name the
     * vanilla entry wins every time, after which [harvest] demands vanilla
     * libraries the installer never produced and the launch fails. Name the
     * loader's entry instead of counting entries.
     */
    internal fun locateVersionJson(dotMinecraft: Path, mcVersion: String): Path {
        val versions = dotMinecraft.resolve("versions")
        if (!Files.isDirectory(versions)) {
            throw IOException("$loaderId installer produced no versions/ dir under $dotMinecraft")
        }
        val candidates = ArrayList<Path>()
        Files.newDirectoryStream(versions).use { stream ->
            for (dir in stream) {
                if (!Files.isDirectory(dir)) continue
                val versionJson = dir.resolve("${dir.fileName}.json")
                if (Files.isRegularFile(versionJson)) candidates.add(versionJson)
            }
        }
        if (candidates.isEmpty()) {
            throw IOException("$loaderId installer produced no versions/<id>/<id>.json under $versions")
        }
        // Sorted first so every fallback below is deterministic rather than
        // inheriting the directory stream's order.
        val byName = candidates.sortedBy { it.parent.fileName.toString() }
        val chosen = byName.firstOrNull { it.parent.fileName.toString().contains(loaderId, ignoreCase = true) }
            ?: byName.firstOrNull { it.parent.fileName.toString() != mcVersion }
            ?: byName.first()
        log.debug("{}: picked version json {} of {} candidates", loaderId, chosen.parent.fileName, candidates.size)
        return chosen
    }

    /**
     * Runs the installer and waits for it, for as long as the caller wants it.
     *
     * The wait is interruptible, and a cancelled one kills the installer and its
     * children on the way out. It used to park on a plain `waitFor` for up to
     * twenty minutes that no cancellation could reach, so a stopped launch left the
     * installer writing into a cache the next launch was about to delete.
     */
    private suspend fun runInstaller(java: Path, installer: Path, dotMinecraft: Path) {
        // --installClient runs the installer's console (non-GUI) path;
        // headless=true keeps it from touching AWT on a display-less host.
        val command = listOf(
            java.toString(),
            "-Djava.awt.headless=true",
            "-jar",
            installer.toString(),
            "--installClient",
            dotMinecraft.toString(),
        )
        log.info("{}: running installer: {}", loaderId, command.joinToString(" "))
        val process = ProcessBuilder(command)
            .directory(dotMinecraft.toFile())
            .redirectErrorStream(true)
            .start()

        val tail = ArrayDeque<String>()
        val drain = Thread {
            try {
                process.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(tail) {
                        tail.addLast(line)
                        if (tail.size > INSTALLER_LOG_TAIL) tail.removeFirst()
                    }
                    log.debug("[{} installer] {}", loaderId, line)
                }
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true; start() }

        val finished = try {
            runInterruptible { process.waitFor(INSTALL_TIMEOUT_MINUTES, TimeUnit.MINUTES) }
        } catch (e: CancellationException) {
            log.info("{}: launch cancelled, stopping the installer", loaderId)
            kill(process)
            throw e
        }
        if (!finished) {
            kill(process)
            throw IOException("$loaderId installer timed out after $INSTALL_TIMEOUT_MINUTES min")
        }
        drain.join(2000)
        if (process.exitValue() != 0) {
            val recent = synchronized(tail) { tail.joinToString("\n") }
            throw IOException("$loaderId installer exited ${process.exitValue()}:\n$recent")
        }
    }

    /** Ends the installer and anything it started, and waits briefly for it to go. */
    private fun kill(process: Process) {
        runCatching { process.descendants().forEach { it.destroyForcibly() } }
        runCatching { process.destroyForcibly() }
        runCatching { process.waitFor(5, TimeUnit.SECONDS) }
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
        const val NEOFORGE_MAVEN = "https://maven.neoforged.net/releases"
        const val FORGE_MAVEN = "https://maven.minecraftforge.net"
        const val FORGE_PROMOTIONS = "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json"
        const val NEOFORGE_META_VERSIONS = "https://maven.neoforged.net/api/maven/versions/releases/net/neoforged"

        private const val INSTALLED_MARKER = ".nexira-installed"

        /** The one Minecraft version NeoForge published under Forge's coordinates. */
        private const val NEOFORGE_FORGE_ERA = "1.20.1"

        /** One lock per cache directory, shared by every resolver instance in the process. */
        private val installLocks = ConcurrentHashMap<Path, Mutex>()

        private fun installLock(dir: Path): Mutex = installLocks.computeIfAbsent(dir.toAbsolutePath().normalize()) { Mutex() }
        private const val INSTALL_TIMEOUT_MINUTES = 20L
        private const val INSTALLER_LOG_TAIL = 40

        // Minimal launcher_profiles.json the Forge/NeoForge installer accepts
        // in --installClient mode (it only reads/writes the profiles map).
        private const val LAUNCHER_PROFILES_STUB =
            "{\"profiles\":{},\"selectedProfile\":\"\"," +
                "\"clientToken\":\"00000000-0000-0000-0000-000000000000\"," +
                "\"authenticationDatabase\":{},\"launcherVersion\":{\"name\":\"2.0\",\"format\":21}}"

        /**
         * Where NeoForge publishes its builds for one Minecraft version, and how their
         * version strings begin.
         *
         * Three schemes, by Minecraft version:
         * - 1.20.1, NeoForge's first, continued Forge's coordinates: the artifact is
         *   `net.neoforged:forge` and a version is `1.20.1-47.1.106`.
         * - 1.20.2 through 1.21.x drop the leading "1.": MC `1.21.1` -> `21.1.x`,
         *   MC `1.21` -> `21.0.x`.
         * - The year-numbered releases keep every part: MC `26.3` -> `26.3.0.x`,
         *   MC `26.1.2` -> `26.1.2.x`.
         *
         * Only the middle one was known here, so the first failed on a path that does
         * not exist and the third asked for `3.0.` and found nothing: every release
         * newer than 1.21.11 could not install NeoForge.
         */
        internal fun neoforgeLine(mc: String): NeoForgeLine {
            if (mc == NEOFORGE_FORGE_ERA) return NeoForgeLine(artifact = "forge", prefix = "$mc-")
            val parts = mc.split('.')
            val major = parts.getOrNull(0)?.toIntOrNull()
            val minor = parts.getOrNull(1)?.takeIf { it.all(Char::isDigit) && it.isNotEmpty() }
            if (major == null || minor == null) throw IOException("cannot derive a NeoForge version from Minecraft '$mc'")
            val patch = parts.getOrNull(2)?.takeIf { it.all(Char::isDigit) && it.isNotEmpty() } ?: "0"
            val prefix = if (major == 1) "$minor.$patch." else "$major.$minor.$patch."
            return NeoForgeLine(artifact = "neoforge", prefix = prefix)
        }

        /**
         * The newest release build among [versions] for [line], else the newest
         * pre-release: a Minecraft version NeoForge has only published betas for is
         * still one it supports, and that is every release for its first weeks.
         * [versions] is in the index's own order, which is publication order.
         */
        internal fun pickNeoForge(versions: List<String>, line: NeoForgeLine): String? {
            val matching = versions.filter { it.startsWith(line.prefix) && '+' !in it }
            return matching.lastOrNull { '-' !in it.removePrefix(line.prefix) } ?: matching.lastOrNull()
        }

        /**
         * A version as the maven names it for [line], accepting what a person types:
         * `47.1.106` for 1.20.1 is `1.20.1-47.1.106`.
         */
        internal fun neoforgeCoordinate(line: NeoForgeLine, version: String): String =
            if (line.artifact == "forge" && !version.startsWith(line.prefix)) line.prefix + version else version

        /** The Forge build part of a version, whether or not it was typed with its Minecraft prefix. */
        internal fun forgeBuild(mc: String, version: String): String = version.removePrefix("$mc-")

        /** The recommended build for [mc] from a promotions_slim.json body, else the latest. */
        internal fun pickForgePromotion(json: Json, promotionsBody: String, mc: String): String? {
            val promos = json.parseToJsonElement(promotionsBody).jsonObject["promos"]?.jsonObject ?: return null
            return (promos["$mc-recommended"] ?: promos["$mc-latest"])?.jsonPrimitive?.contentOrNull
        }

        private suspend fun fetchText(clientProvider: HttpClientProvider, url: String): String =
            clientProvider.current.prepareGet(url).execute { resp ->
                if (!resp.status.isSuccess()) throw IOException("GET $url -> HTTP ${resp.status}")
                resp.bodyAsText()
            }

        /** NeoForge: the version string encodes the Minecraft version, so the
         *  installer coordinate carries no mc segment. */
        fun neoforge(
            clientProvider: HttpClientProvider,
            transfers: TransferEngine,
            json: Json,
            javaManager: IJavaManager,
            cacheDir: Path,
        ): ModernInstallerResolver = ModernInstallerResolver(
            clientProvider, transfers, json, javaManager, cacheDir, loaderId = "neoforge",
            latestVersion = { mc ->
                val line = neoforgeLine(mc)
                val index = json.parseToJsonElement(fetchText(clientProvider, "$NEOFORGE_META_VERSIONS/${line.artifact}"))
                    .jsonObject["versions"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
                pickNeoForge(index, line)
                    ?: throw IOException("no NeoForge version for Minecraft $mc (prefix ${line.prefix})")
            },
        ) { mc, version ->
            val line = neoforgeLine(mc)
            val coordinate = neoforgeCoordinate(line, version)
            "$NEOFORGE_MAVEN/net/neoforged/${line.artifact}/$coordinate/${line.artifact}-$coordinate-installer.jar"
        }

        /** Modern Forge: `<mc>-<build>` slug, same shape as the legacy maven. */
        fun forge(
            clientProvider: HttpClientProvider,
            transfers: TransferEngine,
            json: Json,
            javaManager: IJavaManager,
            cacheDir: Path,
        ): ModernInstallerResolver = ModernInstallerResolver(
            clientProvider, transfers, json, javaManager, cacheDir, loaderId = "forge",
            latestVersion = { mc ->
                pickForgePromotion(json, fetchText(clientProvider, FORGE_PROMOTIONS), mc)
                    ?: throw IOException("no Forge promotion for Minecraft $mc")
            },
        ) { mc, version ->
            // Typed in full (`1.20.1-47.2.0`) the Minecraft part used to be added twice.
            val build = forgeBuild(mc, version)
            "$FORGE_MAVEN/net/minecraftforge/forge/$mc-$build/forge-$mc-$build-installer.jar"
        }
    }
}

/**
 * Routes the "forge" loader id to the right resolver by Minecraft version.
 * Launchwrapper-era Forge (<=1.12.2) needs no install processors and is served
 * by [ForgeLegacyResolver]; 1.13+ Forge patches the client through the official
 * installer ([ModernInstallerResolver]). Registered under one id because a
 * manifest only says `forge`, not which era.
 */
class ForgeResolver(
    private val legacy: ForgeLegacyResolver,
    private val modern: ModernInstallerResolver,
) : LoaderResolver {
    override val loaderId: String = "forge"

    override suspend fun resolve(mcVersion: String, loaderVersion: String): LoaderProfile =
        if (isLaunchwrapperEra(mcVersion)) legacy.resolve(mcVersion, loaderVersion)
        else modern.resolve(mcVersion, loaderVersion)

    companion object {
        /** Forge that launches through launchwrapper -- Minecraft 1.12.2 and earlier. */
        fun isLaunchwrapperEra(mcVersion: String): Boolean {
            val parts = mcVersion.split('.')
            val major = parts.getOrNull(0)?.toIntOrNull() ?: return false
            val minor = parts.getOrNull(1)?.toIntOrNull() ?: return false
            return major == 1 && minor <= 12
        }
    }
}

/** One NeoForge release line: the maven artifact and the prefix its versions share. */
internal data class NeoForgeLine(val artifact: String, val prefix: String)

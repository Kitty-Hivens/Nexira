package hivens.launcher.runtime.loader

import hivens.core.api.HttpClientProvider
import hivens.core.api.interfaces.IJavaManager
import hivens.core.platform.OS
import hivens.launcher.runtime.MavenCoord
import hivens.launcher.runtime.MojangLibrary
import hivens.launcher.runtime.flattenArguments
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
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit

/**
 * Resolver for the modern era (Forge 1.13+ and all NeoForge), where the
 * loader's install processors binpatch the client jar -- a step too large and
 * version-volatile to reimplement. Instead this runs the OFFICIAL installer
 * headless via the managed JDK and consumes what it produces, so upstream
 * changes track for free and no copyrighted bits are redistributed
 * ([[feedback_no_official_runtime_redistribution]]).
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
    private val installerUrl: (mcVersion: String, loaderVersion: String) -> String,
) : LoaderResolver {

    private val log = LoggerFactory.getLogger(ModernInstallerResolver::class.java)

    override suspend fun resolve(mcVersion: String, loaderVersion: String): LoaderProfile =
        withContext(Dispatchers.IO) {
            val dotMinecraft = cacheDir.resolve("$loaderId-$mcVersion-$loaderVersion".replace(Regex("[^A-Za-z0-9._-]"), "_"))
            ensureInstalled(mcVersion, loaderVersion, dotMinecraft)

            val versionJsonPath = locateVersionJson(dotMinecraft)
            val version = json.decodeFromString(
                LoaderVersionJson.serializer(),
                Files.readString(versionJsonPath),
            )
            val os = OS.platform.mojang
            LoaderProfile(
                libraries = version.libraries.map { harvest(it, dotMinecraft) },
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

    /** Runs the installer into [dotMinecraft] unless a prior run completed. */
    private suspend fun ensureInstalled(mcVersion: String, loaderVersion: String, dotMinecraft: Path) {
        val marker = dotMinecraft.resolve(INSTALLED_MARKER)
        if (Files.isRegularFile(marker)) {
            log.info("{} {} already installed in cache, skipping installer", loaderId, loaderVersion)
            return
        }
        // A leftover dir with no marker is a failed prior run -- start clean so
        // the installer never appends to half-written state.
        deleteRecursively(dotMinecraft)
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
     * The loader version json the installer generated. A fresh `--installClient`
     * target holds exactly one `versions/<id>/` dir; pick its `<id>.json`.
     */
    internal fun locateVersionJson(dotMinecraft: Path): Path {
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
        return candidates.singleOrNull()
            ?: candidates.firstOrNull()
            ?: throw IOException("$loaderId installer produced no versions/<id>/<id>.json under $versions")
    }

    private fun runInstaller(java: Path, installer: Path, dotMinecraft: Path) {
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

        if (!process.waitFor(INSTALL_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
            throw IOException("$loaderId installer timed out after $INSTALL_TIMEOUT_MINUTES min")
        }
        drain.join(2000)
        if (process.exitValue() != 0) {
            val recent = synchronized(tail) { tail.joinToString("\n") }
            throw IOException("$loaderId installer exited ${process.exitValue()}:\n$recent")
        }
    }

    private suspend fun downloadTo(url: String, dest: Path) {
        clientProvider.current.prepareGet(url).execute { resp ->
            if (!resp.status.isSuccess()) throw IOException("GET $url -> HTTP ${resp.status}")
            FileOutputStream(dest.toFile()).use { out -> resp.bodyAsChannel().copyTo(out) }
        }
    }

    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path)) return
        Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    companion object {
        const val NEOFORGE_MAVEN = "https://maven.neoforged.net/releases"
        const val FORGE_MAVEN = "https://maven.minecraftforge.net"

        private const val INSTALLED_MARKER = ".nexira-installed"
        private const val INSTALL_TIMEOUT_MINUTES = 20L
        private const val INSTALLER_LOG_TAIL = 40

        // Minimal launcher_profiles.json the Forge/NeoForge installer accepts
        // in --installClient mode (it only reads/writes the profiles map).
        private const val LAUNCHER_PROFILES_STUB =
            "{\"profiles\":{},\"selectedProfile\":\"\"," +
                "\"clientToken\":\"00000000-0000-0000-0000-000000000000\"," +
                "\"authenticationDatabase\":{},\"launcherVersion\":{\"name\":\"2.0\",\"format\":21}}"

        /** NeoForge: the version string encodes the Minecraft version, so the
         *  installer coordinate carries no mc segment. */
        fun neoforge(
            clientProvider: HttpClientProvider,
            json: Json,
            javaManager: IJavaManager,
            cacheDir: Path,
        ): ModernInstallerResolver = ModernInstallerResolver(
            clientProvider, json, javaManager, cacheDir, loaderId = "neoforge",
        ) { _, version -> "$NEOFORGE_MAVEN/net/neoforged/neoforge/$version/neoforge-$version-installer.jar" }

        /** Modern Forge: `<mc>-<build>` slug, same shape as the legacy maven. */
        fun forge(
            clientProvider: HttpClientProvider,
            json: Json,
            javaManager: IJavaManager,
            cacheDir: Path,
        ): ModernInstallerResolver = ModernInstallerResolver(
            clientProvider, json, javaManager, cacheDir, loaderId = "forge",
        ) { mc, version -> "$FORGE_MAVEN/net/minecraftforge/forge/$mc-$version/forge-$mc-$version-installer.jar" }
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

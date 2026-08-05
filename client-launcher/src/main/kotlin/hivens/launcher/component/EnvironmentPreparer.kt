package hivens.launcher.component

import hivens.core.net.SkipIfPresent
import hivens.core.net.Transfer
import hivens.core.net.TransferEngine
import hivens.core.platform.OS
import hivens.core.util.ZipUtils
import hivens.launcher.util.ClientFileHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.stream.Collectors

class EnvironmentPreparer(private val transfers: TransferEngine) {
    private val log = LoggerFactory.getLogger(EnvironmentPreparer::class.java)

    // Modules for Modern versions (1.13+)
    private val lwjgl3Modules = listOf(
        "lwjgl", "lwjgl-jemalloc", "lwjgl-openal", "lwjgl-opengl",
        "lwjgl-glfw", "lwjgl-stb", "lwjgl-tinyfd"
    )
    // Java 25+ runtimes need a newer LWJGL than this; bump when that path lands.
    private val lwjgl3Version = "3.3.3"

    // Mirrors tried in order until one succeeds. Mojang's libraries CDN
    // is FIRST because Maven Central removed legacy LWJGL2 nightly
    // SNAPSHOT artifacts (the 2.9.4-nightly-20150209 build that 1.12.x
    // packs reference) from its public index; libraries.minecraft.net
    // still serves them and matches the same /maven2/-style path layout
    // that the rest of the Minecraft launcher ecosystem expects.
    // repo1.maven.org stays as a fallback for the niche where Mojang's
    // CDN ever blackouts and for the more modern LWJGL3 artifacts that
    // Mojang doesn't itself rebroadcast.
    private val nativesMirrors = listOf(
        "https://libraries.minecraft.net",
        "https://repo1.maven.org/maven2",
    )

    suspend fun prepareNatives(clientRoot: Path, nativesDirName: String, version: String) = withContext(Dispatchers.IO) {
        val binDir = clientRoot.resolve("bin")
        val nativesDir = clientRoot.resolve(nativesDirName)
        val osSuffix = OS.platform.lwjgl

        // 1. Check: If the folder is valid, we do nothing
        if (isFolderValidForOs(nativesDir, osSuffix)) {
            log.info("Natives valid for $osSuffix ($version).")
            return@withContext
        }

        // Cleaning the folder before trying again
        if (Files.exists(nativesDir)) {
            ClientFileHelper.cleanDirectory(nativesDir, emptySet(), log)
        }
        ClientFileHelper.ensureDirectoryExists(nativesDir)

        log.info("Preparing natives for $version ($osSuffix)...")

        // 2. Trying to find local Zip (from server)
        val targetZipName = "natives-$version-$osSuffix.zip"
        val genericZipName = "natives-$version.zip"

        var nativesZip = binDir.resolve(targetZipName)
        if (!Files.exists(nativesZip)) {
            val generic = binDir.resolve(genericZipName)
            if (Files.exists(generic)) nativesZip = generic
        }

        var unpackedSuccessfully = false
        if (Files.exists(nativesZip)) {
            log.info("Found local zip: ${nativesZip.fileName}. Unpacking...")
            try {
                ZipUtils.unzip(nativesZip.toFile(), nativesDir.toFile())
                flattenNatives(nativesDir)

                if (isFolderValidForOs(nativesDir, osSuffix)) {
                    unpackedSuccessfully = true
                } else {
                    log.warn("Local zip content was invalid/empty for $osSuffix. Cleaning...")
                    ClientFileHelper.cleanDirectory(nativesDir, emptySet(), log)
                }
            } catch (e: Exception) {
                log.error("Failed to unzip local natives", e)
            }
        }

        // 3. Download via Ktor
        if (!unpackedSuccessfully) {
            log.warn("Natives missing. Downloading via Ktor...")

            if (version == "1.7.10" || version.startsWith("1.7.")) {
                downloadLegacyLWJGL2(nativesDir, osSuffix, "2.9.1")
            } else if (version == "1.12.2" || version.startsWith("1.12.")) {
                downloadLegacyLWJGL2(nativesDir, osSuffix, "2.9.4-nightly-20150209")
            } else {
                downloadModernLWJGL3(nativesDir, osSuffix)
            }

            flattenNatives(nativesDir)
            if (!isFolderValidForOs(nativesDir, osSuffix)) {
                log.error("CRITICAL: Failed to provide natives!")
            }
        }
    }

    /**
     * Extracts the host natives from the jars the runtime provisioner resolved
     * from the version manifest. Unlike [prepareNatives] (the SC path, which
     * derives a hardcoded LWJGL version from the MC version), this matches the
     * EXACT LWJGL version the resolved classpath references -- a fixed fallback
     * version mismatches the bindings and LWJGL refuses to start. Idempotent: a
     * valid natives dir short-circuits. The jars are already downloaded and
     * sha1-verified by the provisioner; this only unpacks + flattens them, so
     * it makes no network calls.
     */
    suspend fun prepareNativesFromManifest(
        clientRoot: Path,
        nativesDirName: String,
        nativeJars: List<Path>,
        rebuild: Boolean = false,
    ) = withContext(Dispatchers.IO) {
        val nativesDir = clientRoot.resolve(nativesDirName)
        val osSuffix = OS.platform.lwjgl

        // The folder check answers "is there something loadable here", which is
        // what a first launch needs and not what a launch carrying a session
        // token needs. `java.library.path` points here, so whatever sits under
        // these names is what the JVM loads -- and a native library runs inside
        // the game process, which is a stronger position than any mod has. The
        // contents are wholly derived from jars already verified on download, so
        // a bound launch re-derives them rather than trusting what it finds.
        val trustFolder = !rebuild || !allPresent(nativeJars)
        if (trustFolder && isFolderValidForOs(nativesDir, osSuffix)) {
            if (rebuild) {
                // Wiping with no complete source to rebuild from would cost the
                // instance its natives for a reason the user cannot act on.
                log.error("Natives cannot be re-derived for $osSuffix -- source jars incomplete, keeping what is on disk")
            } else {
                log.info("Natives valid for $osSuffix.")
            }
            return@withContext
        }
        if (Files.exists(nativesDir)) {
            ClientFileHelper.cleanDirectory(nativesDir, emptySet(), log)
        }
        ClientFileHelper.ensureDirectoryExists(nativesDir)

        if (nativeJars.isEmpty()) {
            log.error("No native libraries resolved from the manifest for $osSuffix -- natives directory will be empty")
            return@withContext
        }
        for (jar in nativeJars) {
            if (!Files.isRegularFile(jar)) {
                log.warn("Resolved native jar missing on disk, skipping: $jar")
                continue
            }
            try {
                ZipUtils.unzip(jar.toFile(), nativesDir.toFile())
            } catch (e: Exception) {
                log.error("Failed to unpack native jar $jar", e)
            }
        }
        flattenNatives(nativesDir)
        if (!isFolderValidForOs(nativesDir, osSuffix)) {
            log.error("CRITICAL: manifest natives incomplete for $osSuffix")
        }
    }

    /** Every declared native jar is on disk, so a rebuild can complete. */
    private fun allPresent(nativeJars: List<Path>): Boolean =
        nativeJars.isNotEmpty() && nativeJars.all { Files.isRegularFile(it) }

    /**
     * Downloading for old versions (1.7.10, 1.12.2) -> LWJGL 2.
     *
     * The 2.9.4-nightly-20150209 build that 1.12.x references is no
     * longer on Maven Central (snapshot artifacts purged from the
     * public index). libraries.minecraft.net mirrors the legacy native
     * jars under the same /maven2/-style path layout and is the
     * canonical source the wider Minecraft launcher ecosystem uses for
     * exactly this case.
     */
    private suspend fun downloadLegacyLWJGL2(destDir: Path, os: String, version: String) {
        val mavenOs = if (os == "macos") "macosx" else os // LWJGL 2 used 'macosx'

        val artifactPaths = listOf(
            "org/lwjgl/lwjgl/lwjgl-platform/$version/lwjgl-platform-$version-natives-$mavenOs.jar",
            "net/java/jinput/jinput-platform/2.0.5/jinput-platform-2.0.5-natives-$mavenOs.jar",
        )

        artifactPaths.forEach { path ->
            downloadAndUnzipFromMirrors(path, destDir)
        }
    }

    /**
     * Download for new versions (1.13+) -> LWJGL 3.
     * Modern LWJGL3 lives on Maven Central; Mojang also rebroadcasts
     * it via libraries.minecraft.net. Either mirror works.
     */
    private suspend fun downloadModernLWJGL3(destDir: Path, os: String) {
        val mavenOsClassifier = "natives-$os"

        log.info("Downloading LWJGL $lwjgl3Version natives...")

        for (module in lwjgl3Modules) {
            val fileName = "$module-$lwjgl3Version-$mavenOsClassifier.jar"
            val path = "org/lwjgl/$module/$lwjgl3Version/$fileName"
            downloadAndUnzipFromMirrors(path, destDir)
        }
    }

    /**
     * Tries each mirror in order; returns once one succeeds. Logs
     * every miss so a future infra change (mirror retired, certificate
     * expired, geo-block) shows up clearly in launcher.log instead of
     * looking like a transient network blip.
     */
    private suspend fun downloadAndUnzipFromMirrors(artifactPath: String, destDir: Path) {
        for (mirror in nativesMirrors) {
            val url = "$mirror/$artifactPath"
            if (tryDownloadAndUnzip(url, destDir)) return
        }
        log.error("All mirrors exhausted for $artifactPath -- natives directory will be incomplete")
    }

    private suspend fun tryDownloadAndUnzip(urlStr: String, destDir: Path): Boolean {
        log.info("Downloading: $urlStr")
        // Named after the artifact and kept inside the natives directory: the
        // partial beside it is what a retry continues from, and a fresh system temp
        // per attempt cannot be continued at all.
        val jar = destDir.resolve(DOWNLOAD_DIR).resolve(urlStr.substringAfterLast('/'))
        return try {
            // Maven Central pins these by coordinate and publishes no hash we read,
            // so the archive's own structure is the check: a truncated jar fails to
            // unzip and the next mirror is tried.
            transfers.fetch(Transfer(url = urlStr, dest = jar, skip = SkipIfPresent.Never))
            ZipUtils.unzip(jar.toFile(), destDir.toFile())
            true
        } catch (e: CancellationException) {
            // A cancelled download is not a miss. Read as one, it sent the caller
            // on to the next mirror -- and the one after that -- on a coroutine
            // nobody was waiting for, ending in an "all mirrors exhausted" error
            // that never happened.
            throw e
        } catch (e: Exception) {
            log.warn("Mirror miss: $urlStr ({})", e.message)
            false
        } finally {
            runCatching { Files.deleteIfExists(jar) }
        }
    }

    /**
     * Pulls .so/.dll to the root of the folder if they are in subfolders
     */
    internal fun flattenNatives(dir: Path) {
        try {
            if (!Files.exists(dir)) return
            // .use{} closes the stream's underlying directory handle. Without
            // it the OS fd leaks until GC eventually collects the stream object.
            val libraries = Files.walk(dir).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) }
                    .filter {
                        val name = it.fileName.toString()
                        name.endsWith(".so") || name.endsWith(".dll") || name.endsWith(".dylib")
                    }
                    .collect(Collectors.toList())
            }

            for (lib in libraries) {
                val target = dir.resolve(lib.fileName)
                if (lib.parent != dir) {
                    Files.move(lib, target, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        } catch (e: Exception) {
            log.warn("Failed to flatten natives directory", e)
        }
    }

    /**
     * The natives directory is "valid" only when an actual lwjgl
     * native is present, not just *any* file with the platform's
     * extension. Catching only the extension treats a directory
     * containing only `libjinput-*.so` as valid (jinput is a `.so`
     * too), letting [prepareNatives] short-circuit on a half-populated
     * dir; the game then dies with
     * `UnsatisfiedLinkError: no lwjgl64 in java.library.path`.
     *
     * Substring match on `lwjgl` keeps the gate version-agnostic: it
     * catches LWJGL 2 (`liblwjgl.so` + `liblwjgl64.so`) and LWJGL 3
     * (`liblwjgl.so`, `liblwjgl-glfw.so`, …) plus the missing-`lib`-
     * prefix Windows naming (`lwjgl.dll`) without enumerating module
     * names that could drift across versions.
     */
    internal fun isFolderValidForOs(dir: Path, os: String): Boolean {
        if (!Files.exists(dir)) return false
        val extension = when (os) {
            "linux"   -> ".so"
            "windows" -> ".dll"
            "macos"   -> ".dylib"
            else -> return false
        }
        return try {
            Files.list(dir).use { stream ->
                stream.anyMatch {
                    val name = it.fileName.toString().lowercase()
                    name.contains("lwjgl") && name.endsWith(extension)
                }
            }
        } catch (_: Exception) { false }
    }

    fun prepareAssets(clientRoot: Path, assetsZipName: String) {
        val assetsDir = clientRoot.resolve("assets")
        val objectsDir = assetsDir.resolve("objects")
        val assetsZip = clientRoot.resolve(assetsZipName)

        var needUnzip = false
        if (Files.exists(assetsZip)) {
            // If there is no assets folder or objects are empty, unpack it
            if (!Files.exists(assetsDir) || !Files.exists(objectsDir)) {
                needUnzip = true
            } else {
                try {
                    // Rough check: if there are few files, then the unpacking was incorrect.
                    // .use{} ensures the directory stream is closed even though .count() is
                    // a terminal operation -- defensive against future refactor regressions.
                    val count = Files.list(objectsDir).use { it.count() }
                    if (count < 10) needUnzip = true
                } catch (_: Exception) { needUnzip = true }
            }
        } else {
            // If the requested archive is not available, let's try to find the standard assets.zip
            val fallbackZip = clientRoot.resolve("assets.zip")
            if (Files.exists(fallbackZip)) {
                log.info("Requested $assetsZipName not found, but assets.zip exists. Using fallback.")
                // Recursive call, but with the right name
                prepareAssets(clientRoot, "assets.zip")
                return
            }
        }

        if (needUnzip) {
            log.info("Unpacking assets archive: $assetsZipName...")
            try {
                ClientFileHelper.ensureDirectoryExists(assetsDir)
                ZipUtils.unzip(assetsZip.toFile(), assetsDir.toFile())
            } catch (e: IOException) {
                log.error("Failed to unzip assets", e)
            }
        }
    }


    private companion object {
        /** Where a natives archive is staged, inside the directory it unpacks into. */
        const val DOWNLOAD_DIR = ".downloads"
    }
}

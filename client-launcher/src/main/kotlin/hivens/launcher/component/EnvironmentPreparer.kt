package hivens.launcher.component

import hivens.core.platform.OS
import hivens.core.util.ZipUtils
import hivens.launcher.util.ClientFileHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.stream.Collectors

/**
 * The per-instance natives directory: unpacked from the jars the runtime
 * provisioner already resolved and verified, so nothing here reaches the
 * network.
 */
class EnvironmentPreparer {
    private val log = LoggerFactory.getLogger(EnvironmentPreparer::class.java)

    /**
     * Extracts the host natives from the jars the runtime provisioner resolved
     * from the version manifest. Matches the EXACT LWJGL version the resolved
     * classpath references -- a fixed fallback version mismatches the bindings
     * and LWJGL refuses to start. Idempotent: a
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
     * too), letting [prepareNativesFromManifest] short-circuit on a
     * half-populated dir; the game then dies with
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
}

package hivens.launcher.smrt

import hivens.core.io.fileOpRetry
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Shared `mods/` mutation behind the Smarty -> open-smrt swap. Both call sites --
 * the server-list sync ([hivens.launcher.FileDownloadService]) and the SC-bound
 * pack launch ([hivens.launcher.LauncherService]) -- inject the helper jar and
 * strip the surveillance Smarty jar through one implementation, so the two paths
 * can never drift on "what counts as a Smarty jar" or "where the helper lands".
 */
object ModInjector {
    private val logger = LoggerFactory.getLogger(ModInjector::class.java)

    /**
     * Copies the open-smrt-network helper into `<baseDir>/mods/`, replacing any
     * prior copy. Always overwrites: the source bytes were already hash-verified
     * by the resolver, and a size-only skip would miss a same-size helper rebuild.
     * The jar is tiny, so an unconditional copy per launch is negligible. No-op
     * when the source is missing.
     */
    fun injectHelperJar(baseDir: Path, jar: Path) {
        if (!Files.isRegularFile(jar)) {
            logger.warn("open-smrt helper: jar {} missing at inject time; skipping", jar)
            return
        }
        runCatching {
            val modsDir = baseDir.resolve("mods")
            Files.createDirectories(modsDir)
            val dest = modsDir.resolve(jar.fileName.toString())
            fileOpRetry("open-smrt inject ${jar.fileName}") { Files.copy(jar, dest, StandardCopyOption.REPLACE_EXISTING) }
            logger.info("open-smrt helper: injected {}", dest.fileName)
        }.onFailure { logger.warn("open-smrt helper: failed to inject {}", jar, it) }
    }

    /**
     * Deletes every jar under `<baseDir>/mods/` whose basename matches one of
     * [globs] (e.g. `Smarty*.jar`). Used to strip the upstream surveillance jar
     * before injecting the helper, on paths that do NOT run exact manifest
     * verification (the pack path, whose mods are pack-managed and must not be
     * blanket-pruned). The recursive walk covers top-level `mods/` and version
     * subdirs. Returns the number removed.
     */
    fun stripByGlobs(baseDir: Path, globs: List<String>): Int {
        val modsDir = baseDir.resolve("mods")
        if (globs.isEmpty() || !Files.isDirectory(modsDir)) return 0
        val patterns = globs.map { globToRegex(it) }
        var removed = 0
        try {
            Files.walk(modsDir).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jar") }
                    .forEach { jar ->
                        if (patterns.any { it.matches(jar.fileName.toString()) }) {
                            runCatching {
                                fileOpRetry("Smarty strip $jar") { Files.delete(jar) }
                                removed++
                                logger.debug("Smarty strip: removed {}", jar)
                            }.onFailure { logger.warn("Smarty strip: failed to remove {}", jar, it) }
                        }
                    }
            }
        } catch (e: Exception) {
            logger.error("Smarty strip: error walking mods folder", e)
        }
        if (removed > 0) logger.info("Smarty strip: removed {} upstream jar(s) from mods/", removed)
        return removed
    }

    /** Converts a `*`/`?` filename glob to a case-insensitive [Regex]. */
    fun globToRegex(glob: String): Regex {
        val sb = StringBuilder()
        for (c in glob) {
            when (c) {
                '*' -> sb.append(".*")
                '?' -> sb.append('.')
                else -> sb.append(Regex.escape(c.toString()))
            }
        }
        return Regex(sb.toString(), RegexOption.IGNORE_CASE)
    }
}

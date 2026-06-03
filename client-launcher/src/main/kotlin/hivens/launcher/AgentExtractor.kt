package hivens.launcher

import hivens.config.Storage
import hivens.core.io.AtomicFiles
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Makes the profiler agent jar available on disk so the launcher can pass
 * `-javaagent:<path>` to the game JVM. The jar ships as an opaque resource
 * inside the launcher (bundled by the build, never on the compile classpath --
 * see client-ui's resource wiring) and is extracted once to
 * `<dataDir>/runtime/`.
 *
 * The on-disk name carries a short content hash, so a launcher update that
 * changes the agent re-extracts under a new name instead of silently reusing the
 * stale jar. Returns null when the resource isn't bundled (e.g. a dev run before
 * the build wiring lands) -- the caller then launches without the agent rather
 * than failing.
 */
class AgentExtractor(private val dataDir: Path) {

    private val log = LoggerFactory.getLogger(AgentExtractor::class.java)

    fun ensureExtracted(): Path? {
        val bytes = javaClass.getResourceAsStream(RESOURCE_PATH)?.use { it.readBytes() }
            ?: run {
                log.warn("Profiler agent resource {} not bundled; launching without it", RESOURCE_PATH)
                return null
            }
        val target = dataDir.resolve(Storage.RUNTIME_DIR).resolve("profiler-agent-${shortHash(bytes)}.jar")
        if (Files.exists(target) && runCatching { Files.size(target) }.getOrNull() == bytes.size.toLong()) {
            return target
        }
        return try {
            AtomicFiles.writeBytes(target, bytes)
            target
        } catch (e: Exception) {
            log.error("Failed to extract profiler agent to {}; launching without it", target, e)
            null
        }
    }

    private fun shortHash(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
            .substring(0, 12)

    companion object {
        const val RESOURCE_PATH = "/runtime/profiler-agent.jar"
    }
}

package hivens.launcher

import hivens.config.Storage
import hivens.core.io.AtomicFiles
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Makes the bundled `-javaagent` jars available on disk so the launcher can pass
 * `-javaagent:<path>` to the game JVM. Each jar ships as an opaque resource
 * inside the launcher (bundled by the build, never on the compile classpath --
 * see client-ui's resource wiring) and is extracted once to `<dataDir>/runtime/`.
 *
 * Two agents share this path:
 *  - the profiler agent (heap sampling for adaptive memory), and
 *  - the authlib-redirect agent (points an SC-bound join + skin whitelist at
 *    SmartyCraft, the alternative to swapping SC's patched authlib jar).
 *
 * The on-disk name carries a short content hash, so a launcher update that
 * changes an agent re-extracts under a new name instead of silently reusing the
 * stale jar. Returns null when the resource isn't bundled (e.g. a dev run before
 * the build wiring lands) -- the caller then launches without the agent rather
 * than failing.
 */
class AgentExtractor(private val dataDir: Path) {

    private val log = LoggerFactory.getLogger(AgentExtractor::class.java)

    /** Heap-profiler agent for adaptive memory; null if not bundled / extraction fails. */
    fun ensureProfilerAgent(): Path? = extract(PROFILER_RESOURCE, "profiler-agent")

    /** Authlib-redirect agent for an SC-bound join; null if not bundled / extraction fails. */
    fun ensureAuthlibAgent(): Path? = extract(AUTHLIB_RESOURCE, "authlib-agent")

    private fun extract(resourcePath: String, namePrefix: String): Path? {
        val bytes = javaClass.getResourceAsStream(resourcePath)?.use { it.readBytes() }
            ?: run {
                log.warn("Agent resource {} not bundled; launching without it", resourcePath)
                return null
            }
        val target = dataDir.resolve(Storage.RUNTIME_DIR).resolve("$namePrefix-${shortHash(bytes)}.jar")
        // Content, not length. The name carries a hash of the bundled bytes, but
        // matching it against the file's SIZE let anything of the right length pass
        // as the agent -- and a jar is a zip, so padding one to an exact byte count
        // is a comment field. The launcher would then hand `-javaagent:` a stranger's
        // code and the game a session token. Re-reading the file costs a few hundred
        // KB per launch; the alternative is trusting an attacker-writable path.
        val expected = digest(bytes)
        if (Files.exists(target) && runCatching { digest(Files.readAllBytes(target)) }.getOrNull().contentEquals(expected)) {
            return target
        }
        return try {
            AtomicFiles.writeBytes(target, bytes)
            target
        } catch (e: Exception) {
            log.error("Failed to extract agent to {}; launching without it", target, e)
            null
        }
    }

    private fun digest(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun shortHash(bytes: ByteArray): String =
        digest(bytes)
            .joinToString("") { "%02x".format(it) }
            .substring(0, 12)

    companion object {
        const val PROFILER_RESOURCE = "/runtime/profiler-agent.jar"
        const val AUTHLIB_RESOURCE = "/runtime/authlib-agent.jar"
    }
}

package hivens.ui.editor.presets

import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.io.path.copyTo
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

// File-per-preset under <dataDir>/presets/<safe-name>-<digest>.json. Per-file
// scheme makes share / import dead simple (just hand someone the file). Atomic
// write via .tmp + ATOMIC_MOVE mirrors LayoutGraphRepository.persist.
//
// The filename is derived from the preset name but is NOT the preset name: the
// sanitiser maps everything outside [A-Za-z0-9_-] to an underscore, so any two
// Cyrillic names of equal length collapse to the same string. "Ночь" and "День"
// both became "____.json", the second save replaced the first, and the list --
// which used to read its display names back off the filenames -- showed one row
// called "____" with the typed name unrecoverable. The digest of the original
// name keeps the paths apart, and the display name is read from the envelope,
// which has carried it all along.
class PresetRepository(
    private val presetsDir: Path,
    private val json: Json,
) {
    private val log = LoggerFactory.getLogger(PresetRepository::class.java)

    fun list(): List<PresetMeta> {
        if (!Files.exists(presetsDir)) return emptyList()
        return Files.list(presetsDir).use { stream ->
            stream
                .filter { it.isRegularFile() && it.name.endsWith(".json") }
                .map { path ->
                    val stored = runCatching {
                        json.decodeFromString<PresetEnvelope>(Files.readString(path)).name
                    }.getOrNull()
                    val mtime = runCatching { Files.getLastModifiedTime(path).toMillis() }
                        .getOrDefault(0L)
                    // Fall back to the file stem only when the envelope will not
                    // parse -- a name is better than an empty row.
                    val name = stored?.takeIf { it.isNotBlank() } ?: path.name.removeSuffix(".json")
                    PresetMeta(name = name, createdAt = mtime, sourcePath = path)
                }
                .sorted(compareByDescending { it.createdAt })
                .toList()
        }
    }

    fun load(name: String): PresetEnvelope? {
        val path = resolveExisting(name)
        if (!Files.exists(path)) return null
        return try {
            json.decodeFromString<PresetEnvelope>(Files.readString(path))
        } catch (e: Exception) {
            log.warn("Failed to load preset {}: {}", name, e.message)
            null
        }
    }

    fun save(envelope: PresetEnvelope) {
        Files.createDirectories(presetsDir)
        val target = pathFor(envelope.name)
        val tmp = target.resolveSibling("${target.fileName}.tmp")
        Files.writeString(tmp, json.encodeToString(envelope))
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            log.warn(
                "Filesystem at {} does not support ATOMIC_MOVE; falling back to non-atomic rename for preset {}",
                presetsDir, envelope.name,
            )
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun delete(name: String): Boolean {
        return Files.deleteIfExists(resolveExisting(name))
    }

    fun export(name: String, destination: Path): Boolean {
        val source = resolveExisting(name)
        if (!Files.exists(source)) return false
        source.copyTo(destination, overwrite = true)
        return true
    }

    fun import(source: Path): PresetEnvelope? {
        return try {
            val envelope = json.decodeFromString<PresetEnvelope>(Files.readString(source))
            save(envelope)
            envelope
        } catch (e: Exception) {
            log.warn("Failed to import preset from {}: {}", source, e.message)
            null
        }
    }

    /**
     * Filename for [name]. The readable half keeps [A-Za-z0-9_-] and maps the rest
     * to an underscore -- that is what stops path traversal and filesystem-illegal
     * characters -- and the digest half distinguishes names the readable half
     * cannot. Derived from the trimmed original, so it is stable across calls and
     * the same name always resolves to the same file.
     */
    private fun fileNameFor(name: String): String {
        val trimmed = name.trim()
        val cleaned = trimmed.replace(Regex("[^A-Za-z0-9_\\-]"), "_").ifBlank { "preset" }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(trimmed.toByteArray(Charsets.UTF_8))
            .take(4)
            .joinToString("") { "%02x".format(it) }
        return "$cleaned-$digest.json"
    }

    /** Where a save for [name] goes. */
    private fun pathFor(name: String): Path = presetsDir.resolve(fileNameFor(name))

    /**
     * Where [name] can be READ from: the current path, else the pre-digest one.
     * Presets saved before the suffix existed sit at the bare sanitised name, and
     * an upgrade must not orphan them. A later save republishes under the current
     * path -- collisions among legacy files stay as they were, because their
     * distinguishing information was already lost when they were written.
     */
    private fun resolveExisting(name: String): Path {
        val current = pathFor(name)
        if (Files.exists(current)) return current
        val legacy = presetsDir.resolve(
            "${name.trim().replace(Regex("[^A-Za-z0-9_\\-]"), "_").ifBlank { "preset" }}.json"
        )
        return if (Files.exists(legacy)) legacy else current
    }
}

package hivens.ui.editor.presets

import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.copyTo
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

// File-per-preset under <dataDir>/presets/<safe-name>.json. Per-file
// scheme makes share / import dead simple (just hand someone the
// file). Atomic write via .tmp + ATOMIC_MOVE mirrors
// LayoutGraphRepository.persist.
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
                    val name = path.name.removeSuffix(".json")
                    val mtime = runCatching { Files.getLastModifiedTime(path).toMillis() }
                        .getOrDefault(0L)
                    PresetMeta(name = name, createdAt = mtime, sourcePath = path)
                }
                .sorted(compareByDescending { it.createdAt })
                .toList()
        }
    }

    fun load(name: String): PresetEnvelope? {
        val path = presetsDir.resolve("${sanitize(name)}.json")
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
        val target = presetsDir.resolve("${sanitize(envelope.name)}.json")
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
        val path = presetsDir.resolve("${sanitize(name)}.json")
        return Files.deleteIfExists(path)
    }

    fun export(name: String, destination: Path): Boolean {
        val source = presetsDir.resolve("${sanitize(name)}.json")
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

    // Filename sanitization. [A-Za-z0-9_-] passes; everything else
    // becomes underscore. Empty result coerces to "preset". Prevents
    // path traversal + filesystem-illegal chars (slash, colon, etc.).
    private fun sanitize(name: String): String {
        val cleaned = name.trim().replace(Regex("[^A-Za-z0-9_\\-]"), "_")
        return cleaned.ifBlank { "preset" }
    }
}

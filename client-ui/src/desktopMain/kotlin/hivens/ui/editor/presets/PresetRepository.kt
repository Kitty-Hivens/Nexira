package hivens.ui.editor.presets

import hivens.core.io.AtomicFiles
import hivens.ui.customization.CustomizationSettings
import hivens.ui.layout.JsonMigrations
import hivens.ui.layout.LayoutReconcile
import hivens.widget.model.LayoutGraph
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
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

    /**
     * Reads a preset and brings its graph up to the current shape.
     *
     * The same floor the layout file is held to applies here, and it used not to:
     * the refusal to read a pre-surface-schema graph lived in the repository that
     * owns the layout, while a preset went straight to the merge. Files from
     * before that schema exist in people's preset directories right now, and
     * applying one dropped every widget's plane on decode, silently, and then
     * persisted the result as current. A preset this build cannot read faithfully
     * is refused instead, and says so.
     */
    fun load(name: String): LoadedPreset? {
        val path = resolveExisting(name)
        if (!Files.exists(path)) return null
        return try {
            val envelope = json.decodeFromString<PresetEnvelope>(Files.readString(path))
            if (envelope.schemaVersion < LayoutReconcile.SURFACE_SCHEMA) {
                log.warn(
                    "Preset '{}' is schema_version {} and describes widget surfaces in a form " +
                        "with no faithful reading here; not applying it. The file is left as it is.",
                    name, envelope.schemaVersion,
                )
                return null
            }
            val structural = JsonMigrations.apply(envelope.schemaVersion, envelope.graph)
            LoadedPreset(
                schemaVersion = envelope.schemaVersion,
                name          = envelope.name,
                graph         = json.decodeFromJsonElement(LayoutGraph.serializer(), structural),
                customization = envelope.customization,
            )
        } catch (e: Exception) {
            log.warn("Failed to load preset {}: {}", name, e.message)
            null
        }
    }

    /**
     * Writes the current layout and look under [name], stamped with the schema
     * this build writes, so a later one knows how far to carry it.
     */
    fun save(name: String, graph: LayoutGraph, customization: CustomizationSettings) {
        val envelope = PresetEnvelope(
            schemaVersion = LayoutReconcile.CURRENT_SCHEMA,
            name          = name,
            createdAt     = System.currentTimeMillis(),
            graph         = json.encodeToJsonElement(LayoutGraph.serializer(), graph).jsonObject,
            customization = customization,
        )
        // The hand-rolled tmp-then-rename this replaces published whole files but
        // never flushed them, so a power loss could persist the rename over bytes
        // still in the page cache. AtomicFiles is the same sequence with the fsyncs.
        AtomicFiles.writeString(pathFor(name), json.encodeToString(envelope))
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

    /**
     * Copies a preset file somebody was handed into this profile's directory.
     *
     * Republished under this build's naming rather than copied byte for byte, so
     * a name the old scheme could not keep apart lands on its own path. The graph
     * is NOT migrated here: a file is stored as it arrived and carried forward
     * when it is applied, which keeps one answer to "when does a preset migrate".
     */
    fun import(source: Path): String? {
        return try {
            val envelope = json.decodeFromString<PresetEnvelope>(Files.readString(source))
            AtomicFiles.writeString(pathFor(envelope.name), json.encodeToString(envelope))
            envelope.name
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

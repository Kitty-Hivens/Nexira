package hivens.ui.editor.presets

import hivens.core.io.AtomicFiles
import hivens.core.io.readUtf8
import hivens.ui.customization.CustomizationSettings
import hivens.ui.widgets.state.instanceIdsIn
import hivens.widget.model.Document
import hivens.widget.model.DocumentCodec
import hivens.widget.model.DocumentRead
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.stream.Collectors
import kotlin.io.path.copyTo
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

// File-per-preset under <dataDir>/presets/<safe-name>-<digest>.json. Per-file
// scheme makes share / import dead simple (just hand someone the file), written
// atomically through AtomicFiles.
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
                    val stored = runCatching { json.decodeFromString<PresetEnvelope>(readUtf8(path)) }.getOrNull()
                    val mtime = runCatching { Files.getLastModifiedTime(path).toMillis() }
                        .getOrDefault(0L)
                    // Fall back to the file stem only when the envelope will not
                    // parse: a name is better than an empty row.
                    val name = stored?.name?.takeIf { it.isNotBlank() } ?: path.name.removeSuffix(".json")
                    PresetMeta(name = name, createdAt = mtime, sourcePath = path, older = stored != null && stored.document == null)
                }
                .sorted(compareByDescending { it.createdAt })
                .collect(Collectors.toList())
        }
    }

    /**
     * Reads a preset, or null where there is none to apply: none by that name, one made before the interface was a
     * document, one a newer build wrote, or a file that is not a preset. Each is logged, and the file is left as it is.
     */
    fun load(name: String): LoadedPreset? {
        val path = resolveExisting(name)
        if (!Files.exists(path)) return null
        return try {
            val envelope = json.decodeFromString<PresetEnvelope>(readUtf8(path))
            val raw = envelope.document ?: run {
                log.warn("Preset '{}' was made before the interface was a document, and nothing reads its arrangement now", name)
                return null
            }
            when (val read = DocumentCodec.read(json.encodeToString(JsonElement.serializer(), raw))) {
                is DocumentRead.Whole -> LoadedPreset(envelope.name, read.document, envelope.customization)
                is DocumentRead.Newer -> {
                    log.warn("Preset '{}' was written by a newer build (schema {}), not applying it", name, read.schema)
                    null
                }
                is DocumentRead.Broken -> {
                    log.warn("Preset '{}' holds no document ({}), not applying it", name, read.reason)
                    null
                }
            }
        } catch (e: Exception) {
            log.warn("Failed to load preset {}: {}", name, e.message)
            null
        }
    }

    /**
     * Writes [document] and the look under [name]. Without the module list, which is the person's launcher's and not
     * the arrangement's: applying a preset keeps the modules the launcher has.
     */
    fun save(name: String, document: Document, customization: CustomizationSettings) {
        val envelope = PresetEnvelope(
            name          = name,
            createdAt     = System.currentTimeMillis(),
            document      = json.parseToJsonElement(DocumentCodec.write(document.copy(modules = emptyList()))),
            customization = customization,
        )
        // The hand-rolled tmp-then-rename this replaces published whole files but
        // never flushed them, so a power loss could persist the rename over bytes
        // still in the page cache. AtomicFiles is the same sequence with the fsyncs.
        AtomicFiles.writeString(pathFor(name), json.encodeToString(envelope))
    }

    /**
     * Every widget instance id any saved preset names.
     *
     * The state collector keeps what these ids own. A preset brings its widgets back
     * under the ids it saved them with, and the state those widgets carry (a note's
     * text, a checklist) lives beside the arrangement keyed by those ids, so pruning
     * against the live document alone emptied every one of them the moment another
     * preset was applied. Read from the raw file rather than a decoded document, so a
     * preset of any age keeps its widgets' state too. A file that will not parse
     * contributes nothing.
     */
    fun referencedInstanceIds(): Set<String> {
        if (!Files.exists(presetsDir)) return emptySet()
        val ids = HashSet<String>()
        Files.list(presetsDir).use { stream ->
            stream.filter { it.isRegularFile() && it.name.endsWith(".json") }.forEach { path ->
                runCatching { json.decodeFromString<PresetEnvelope>(readUtf8(path)) }
                    .onSuccess { envelope ->
                        envelope.graph?.let { ids += instanceIdsIn(it) }
                        envelope.document?.let { ids += instanceIdsIn(it) }
                    }
            }
        }
        return ids
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
     * a name the old scheme could not keep apart lands on its own path. The document
     * is stored as it arrived and read when it is applied.
     */
    fun import(source: Path): String? {
        return try {
            val envelope = json.decodeFromString<PresetEnvelope>(readUtf8(source))
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

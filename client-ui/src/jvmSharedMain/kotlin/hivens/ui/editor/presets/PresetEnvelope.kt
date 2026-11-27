package hivens.ui.editor.presets

import hivens.ui.customization.CustomizationSettings
import hivens.widget.model.Document
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * One named preset: the whole interface as a document, with the look ([customization]) it was saved under. Saved and
 * applied whole. Persisted as one file per preset under <dataDir>/presets/.
 *
 * The document is held raw and read through the document's own codec, which knows its schema: a preset written by a
 * newer build is refused rather than decoded with what this build does not name dropped. A preset made before the
 * interface was a document carries [graph] instead, an arrangement in a form nothing reads any more. It stays listed
 * so it can be exported or deleted, and it cannot be applied.
 */
@Serializable
data class PresetEnvelope(
    /** The layout schema an old preset's [graph] was written at. Kept so the field is read back as it was written. */
    @SerialName("schema_version") val schemaVersion: Int = 1,
    val name: String,
    @SerialName("created_at") val createdAt: Long,
    val graph: JsonObject? = null,
    val document: JsonElement? = null,
    val customization: CustomizationSettings = CustomizationSettings(),
)

/** A preset read off disk, ready to apply. */
data class LoadedPreset(
    val name: String,
    val document: Document,
    val customization: CustomizationSettings,
)

/** What a listing needs of a preset without reading the whole of it. */
data class PresetMeta(
    val name: String,
    val createdAt: Long,
    val sourcePath: java.nio.file.Path,
    /** Made before the interface was a document: listed, never applied. */
    val older: Boolean = false,
)

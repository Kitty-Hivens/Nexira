package hivens.ui.editor.presets

import hivens.ui.customization.CustomizationSettings
import hivens.widget.model.LayoutGraph
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// One named preset = a snapshot of (LayoutGraph + CustomizationSettings).
// Save: capture current state. Load: restore both at once. Persisted as
// one file per preset under <dataDir>/presets/.
//
// The graph is held as a raw object rather than a decoded LayoutGraph, for the
// reason the layout file is read the same way: the structural half of the
// migration ladder runs before the decoder, and a field this build no longer
// declares is dropped by ignoreUnknownKeys the moment it decodes. A preset is
// exactly the case that matters -- it is the one document a person carries
// between builds on purpose, and it predates the current shape by definition.
//
// schema_version is the LAYOUT schema the graph was written at, which is what
// the ladder is keyed on. Forward-compat on the envelope's own fields is
// handled by Json { ignoreUnknownKeys = true } in PresetRepository.
@Serializable
data class PresetEnvelope(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    val name: String,
    @SerialName("created_at") val createdAt: Long,
    val graph: JsonObject,
    val customization: CustomizationSettings,
)

/**
 * A preset read off disk and brought up to the current shape, but not yet
 * merged into anything.
 *
 * [schemaVersion] is the version the FILE carried, and it is still needed after
 * the structural migration, because the other half of the ladder (the one that
 * retires a widget kind) runs on the decoded graph and is keyed on the same
 * number.
 */
data class LoadedPreset(
    val schemaVersion: Int,
    val name: String,
    val graph: LayoutGraph,
    val customization: CustomizationSettings,
)

// Light metadata for listing without loading the full envelope. Useful
// for the PresetManagerPanel which only needs name + mtime for the
// row UI.
data class PresetMeta(
    val name: String,
    val createdAt: Long,
    val sourcePath: java.nio.file.Path,
)

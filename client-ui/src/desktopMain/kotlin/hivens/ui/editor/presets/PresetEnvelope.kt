package hivens.ui.editor.presets

import hivens.ui.customization.CustomizationSettings
import hivens.widget.model.LayoutGraph
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// One named preset = a snapshot of (LayoutGraph + CustomizationSettings).
// Save: capture current state. Load: restore both at once. Persisted as
// one file per preset under <dataDir>/presets/.
//
// schema_version: bump when the shape changes. Forward-compat handled
// by Json { ignoreUnknownKeys = true } in PresetRepository.
@Serializable
data class PresetEnvelope(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    val name: String,
    @SerialName("created_at") val createdAt: Long,
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

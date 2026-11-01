package hivens.core.api.dto.smrt

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire shape of `GET /v1/packs/{id}/diff?from=&to=`: the mirror's structured
 * change summary between two builds, matched by stable identity server-side
 * and enriched with registry version labels the manifests themselves do not
 * carry. Display-only data: the update reconcile stays a client-side
 * three-way computation, and this feeds the versions screen's rows.
 */
@Serializable
data class SmrtBuildDiff(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("pack_id") val packId: String,
    val from: String,
    val to: String,
    /** False when the two builds share a content fingerprint (a relabel). */
    @SerialName("content_changed") val contentChanged: Boolean,
    val loader: SmrtFieldChange? = null,
    val minecraft: SmrtFieldChange? = null,
    val java: SmrtFieldChange? = null,
    @SerialName("mods_added") val modsAdded: List<SmrtDiffEntry> = emptyList(),
    @SerialName("mods_removed") val modsRemoved: List<SmrtDiffEntry> = emptyList(),
    @SerialName("mods_updated") val modsUpdated: List<SmrtDiffUpdate> = emptyList(),
    @SerialName("mods_toggled") val modsToggled: List<SmrtDiffToggle> = emptyList(),
    @SerialName("assets_added") val assetsAdded: List<SmrtDiffEntry> = emptyList(),
    @SerialName("assets_removed") val assetsRemoved: List<SmrtDiffEntry> = emptyList(),
    @SerialName("assets_updated") val assetsUpdated: List<SmrtDiffUpdate> = emptyList(),
)

/** A scalar that changed between the two builds, verbatim. */
@Serializable
data class SmrtFieldChange(val from: String, val to: String)

/** One entry present on only one side; [version] filled where the registry knows the artifact. */
@Serializable
data class SmrtDiffEntry(
    val filename: String,
    val version: String? = null,
)

/** One entry present on both sides whose artifact changed. [filename] is the `to` side's. */
@Serializable
data class SmrtDiffUpdate(
    val filename: String,
    @SerialName("version_from") val versionFrom: String? = null,
    @SerialName("version_to") val versionTo: String? = null,
    @SerialName("sha1_from") val sha1From: String,
    @SerialName("sha1_to") val sha1To: String,
)

/** An entry whose install-time default flipped (content unchanged). */
@Serializable
data class SmrtDiffToggle(
    val filename: String,
    @SerialName("default_enabled_from") val defaultEnabledFrom: Boolean,
    @SerialName("default_enabled_to") val defaultEnabledTo: Boolean,
)

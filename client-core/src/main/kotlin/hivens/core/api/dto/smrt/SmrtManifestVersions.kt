package hivens.core.api.dto.smrt

import kotlinx.serialization.Serializable

/**
 * Wire shape of `GET /v1/packs/{id}/manifest/versions`: the list of build
 * versions the mirror retains for a pack. Ordering is not guaranteed by the
 * wire; callers sort with [hivens.core.update.comparePackVersions]. Unknown
 * fields (e.g. `schema_version`) are ignored by the tolerant decoder.
 */
@Serializable
data class SmrtManifestVersions(
    val versions: List<String> = emptyList(),
)

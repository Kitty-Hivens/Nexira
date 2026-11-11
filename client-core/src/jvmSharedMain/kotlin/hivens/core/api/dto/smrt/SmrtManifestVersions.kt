package hivens.core.api.dto.smrt

import hivens.core.update.PackBuild
import kotlinx.serialization.Serializable

/**
 * Wire shape of `GET /v1/packs/{id}/manifest/versions`: the per-build listing
 * the mirror retains for a pack, newest first by publish date. The server order
 * is canonical -- release and SNAPSHOT builds interleave, and version-tuple
 * sorting misorders them (only the timestamps rank across channels). [latest]
 * names the build the bare manifest endpoint serves.
 */
@Serializable
data class SmrtManifestVersions(
    val latest: String? = null,
    val builds: List<PackBuild> = emptyList(),
)

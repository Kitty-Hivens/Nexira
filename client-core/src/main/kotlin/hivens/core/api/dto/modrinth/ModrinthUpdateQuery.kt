package hivens.core.api.dto.modrinth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Body of `POST /v2/version_files/update_many`: "for each of these files, what
 * is the newest version that still fits this instance".
 *
 * One request answers for a whole folder, which is the only reason checking a
 * hundred mods for updates is a thing a screen can do while someone waits. The
 * per-file alternative is a hundred round trips.
 *
 * No field carries a default. The Modrinth JSON is encoded with
 * `encodeDefaults = false`, so a defaulted [algorithm] would be dropped from
 * the body and the server would read the hashes as sha512 and match nothing.
 */
@Serializable
data class ModrinthUpdateQuery(
    val algorithm: String,
    val hashes: List<String>,
    /** Loader ids the instance runs, e.g. `neoforge`. */
    val loaders: List<String>,
    @SerialName("game_versions") val gameVersions: List<String>,
    /**
     * Which release channels may answer: `release`, `beta`, `alpha`. Asked one
     * channel at a time by the caller, widest last, so a mod with a release
     * build is never offered an alpha just because the alpha is newer.
     */
    @SerialName("version_types") val versionTypes: List<String>,
)

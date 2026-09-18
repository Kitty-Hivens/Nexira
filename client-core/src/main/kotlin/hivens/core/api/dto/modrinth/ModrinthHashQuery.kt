package hivens.core.api.dto.modrinth

import kotlinx.serialization.Serializable

/**
 * Body of `POST /v2/version_files`: "which versions are these files".
 *
 * Same no-defaults rule as [ModrinthUpdateQuery] -- the Json drops defaulted
 * fields, and an absent `algorithm` makes the server read sha1 hashes as
 * sha512 and match nothing.
 */
@Serializable
data class ModrinthHashQuery(
    val algorithm: String,
    val hashes: List<String>,
)

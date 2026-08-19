package hivens.core.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One version of a pack, whatever published it.
 *
 * The field names are Modrinth's version-object names, which is not a
 * coincidence: the mirror adopted them deliberately, so a build from either
 * source lands in this shape without a translation step. The mirror's listing
 * deserializes straight into it; a Modrinth version maps across field for field.
 *
 * [fingerprint] hashes the shipped content set, so two builds with equal NON-NULL
 * fingerprints carry identical files (a label-only rebuild). Absent on builds
 * that predate it and on anything not from the mirror, so equality means
 * something only when both sides are non-null.
 *
 * [modsCount] and [assetsCount] are nullable because a source can genuinely not
 * know them: Modrinth publishes no counts, and reading them would mean
 * downloading the pack archive. Null is "not known" and zero is "none", and a
 * screen that cannot tell those apart shows a wrong number instead of no number.
 */
@Serializable
data class PackBuild(
    @SerialName("version_number") val versionNumber: String,
    @SerialName("version_type") val versionType: String? = null,
    @SerialName("date_published") val datePublished: String? = null,
    val fingerprint: String? = null,
    /** Curator-authored release notes for this build (CommonMark); absent when none were given. */
    val changelog: String? = null,
    @SerialName("mods_count") val modsCount: Int? = null,
    @SerialName("assets_count") val assetsCount: Int? = null,
    /**
     * What this build runs on, when the source says so without being asked for
     * the archive. Drives the compatibility grade and the row's own label; null
     * where the source publishes no such metadata.
     */
    val minecraftVersion: String? = null,
    val loaderName: String? = null,
) {
    /** Channel of this build, derived from the version string when the field is absent or unknown. */
    val channel: VersionChannel get() = VersionChannel.of(versionType, versionNumber)
}

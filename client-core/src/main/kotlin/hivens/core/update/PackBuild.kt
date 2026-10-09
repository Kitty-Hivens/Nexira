package hivens.core.update

import hivens.core.api.dto.smrt.inLanguage
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.nullable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

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
    /**
     * [changelog] by language tag. Only the mirror publishes it, and
     * [forLanguage] folds it into [changelog] where the listing becomes something
     * a screen reads, so nothing downstream has to know it exists.
     */
    @SerialName("changelog_i18n") val changelogI18n: Map<String, String>? = null,
    @SerialName("mods_count") val modsCount: Int? = null,
    @SerialName("assets_count") val assetsCount: Int? = null,
    /**
     * What this build runs on, when the source says so without being asked for
     * the archive. Drives the compatibility grade and the row's own label; null
     * where the source publishes no such metadata.
     *
     * Named on the wire as the mirror's listing names them. Unnamed, the decoder
     * looked for `minecraftVersion` and `loaderName`, found neither, and every
     * mirror build read as running on nothing in particular.
     */
    @SerialName("minecraft_version") val minecraftVersion: String? = null,
    /** The listing carries `{name, version}`; only the name is read. */
    @SerialName("loader")
    @Serializable(with = LoaderNameSerializer::class)
    val loaderName: String? = null,
    /** What the build costs to download, every mod and asset it lists. Null where the source does not say. */
    @SerialName("size_bytes") val sizeBytes: Long? = null,
    /**
     * What identifies this build when its label does not.
     *
     * A mirror pack's version number is unique within it, so the label is the
     * identity and this is null. Modrinth publishes one version object per
     * loader, and those routinely share a version_number -- a pack shipping both
     * a Fabric and a NeoForge build of 2.8.0 has two versions wearing that name.
     * Anything that must tell two builds apart uses [key], never the label.
     */
    val id: String? = null,
) {
    /** Channel of this build, derived from the version string when the field is absent or unknown. */
    val channel: VersionChannel get() = VersionChannel.of(versionType, versionNumber)

    /** Stable identity: the source's own id where it has one, the label otherwise. */
    val key: String get() = id ?: versionNumber

    /** This build with its release notes in the reader's language [tag], where the source wrote them. */
    fun forLanguage(tag: String): PackBuild =
        if (changelogI18n == null) this else copy(changelog = inLanguage(changelog, changelogI18n, tag))
}

/**
 * A build's loader as its name, from either shape it comes in.
 *
 * The mirror's listing writes `{"name": "neoforge", "version": "21.1.200"}`, and
 * the copy this client keeps on disk writes back the bare name. Both decode to the
 * name, and anything else to null rather than failing the listing over one field.
 */
@OptIn(ExperimentalSerializationApi::class)
internal object LoaderNameSerializer : KSerializer<String?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("PackBuildLoader", PrimitiveKind.STRING).nullable

    override fun deserialize(decoder: Decoder): String? {
        val json = decoder as? JsonDecoder ?: return decoder.decodeString()
        return when (val element = json.decodeJsonElement()) {
            is JsonObject -> (element["name"] as? JsonPrimitive)?.contentOrNull
            is JsonPrimitive -> element.contentOrNull
            else -> null
        }?.takeIf { it.isNotBlank() }
    }

    override fun serialize(encoder: Encoder, value: String?) {
        if (value == null) encoder.encodeNull() else encoder.encodeString(value)
    }
}

package hivens.core.api.dto.smrt

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire shape of a v2 smrt mirror pack manifest. Mirrors the spec in
 * `docs/src/content/docs/dev/smrt-api-spec.md`. Unknown fields are
 * tolerated (Json is configured with `ignoreUnknownKeys = true`) so a
 * future server-side `display` extension or fresh source variant does
 * not crash this client.
 */
@Serializable
data class SmrtPackManifest(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("pack_id") val packId: String,
    @SerialName("pack_version") val packVersion: String,
    @SerialName("generated_at") val generatedAt: String,
    val minecraft: SmrtMinecraft,
    val loader: SmrtLoader,
    val java: SmrtJava,
    val mods: List<SmrtModEntry> = emptyList(),
    val assets: List<SmrtAssetEntry> = emptyList(),
)

@Serializable
data class SmrtMinecraft(val version: String)

@Serializable
data class SmrtLoader(val name: String, val version: String)

@Serializable
data class SmrtJava(val major: Int)

@Serializable
data class SmrtModEntry(
    val filename: String,
    val sha1: String,
    @SerialName("size_bytes") val sizeBytes: Long,
    val required: Boolean = true,
    val source: SmrtSource,
    val display: SmrtDisplay? = null,
)

@Serializable
data class SmrtAssetEntry(
    val dest: String,
    val sha1: String,
    @SerialName("size_bytes") val sizeBytes: Long,
    val required: Boolean = true,
    val source: SmrtSource,
    val display: SmrtDisplay? = null,
)

@Serializable
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@kotlinx.serialization.json.JsonClassDiscriminator("type")
sealed class SmrtSource {
    @Serializable
    @SerialName("modrinth")
    data class Modrinth(
        @SerialName("project_id") val projectId: String,
        @SerialName("version_id") val versionId: String,
    ) : SmrtSource()

    @Serializable
    @SerialName("smrt_cache")
    data class SmrtCache(val url: String) : SmrtSource()

    @Serializable
    @SerialName("smrt_static")
    data class SmrtStatic(val url: String) : SmrtSource()
}

/**
 * Advisory display metadata. All fields optional; a launcher renders
 * sensible defaults derived from filename / dest when absent.
 */
@Serializable
data class SmrtDisplay(
    val name: String? = null,
    val description: String? = null,
    val category: String? = null,
    @SerialName("incompatible_with") val incompatibleWith: List<String> = emptyList(),
    val license: String? = null,
    val url: String? = null,
)

@Serializable
data class SmrtPackSummary(
    @SerialName("pack_id") val packId: String,
    @SerialName("display_name") val displayName: String,
    val tagline: String,
    @SerialName("minecraft_version") val minecraftVersion: String,
    @SerialName("latest_pack_version") val latestPackVersion: String,
    val tags: List<String> = emptyList(),
    val featured: Boolean = false,
)

@Serializable
data class SmrtPackListing(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("generated_at") val generatedAt: String,
    val packs: List<SmrtPackSummary>,
)

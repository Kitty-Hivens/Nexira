package hivens.core.api.dto.smrt

import hivens.core.data.PackAuthRequirement
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

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
    /**
     * Auth provider the pack requires the user to be signed in with
     * before launch. Absent for vanilla / offline-only packs; present
     * (today only `smartycraft`) for packs bound to a specific game
     * server. The launcher uses this to (a) gate Play on the right
     * sign-in and (b) refresh the session right before spawn so a
     * cold mod-load does not age the token out. A future
     * `kind` value the client does not recognise decodes to null
     * (see [SmrtAuthLenientSerializer]) -- the launcher then treats
     * the pack as unrestricted rather than failing the whole
     * manifest parse.
     */
    @Serializable(with = SmrtAuthLenientSerializer::class)
    val auth: SmrtAuth? = null,
    val mods: List<SmrtModEntry> = emptyList(),
    val assets: List<SmrtAssetEntry> = emptyList(),
)

/**
 * Wire shape of the optional `auth` block on a pack manifest.
 * Discriminated by `kind` so the mirror can add provider variants
 * (mojang / elyby / one_of) without breaking older clients --
 * `ignoreUnknownKeys` on the decoder lets a future field land
 * additively.
 */
@Serializable
@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("kind")
sealed class SmrtAuth {
    @Serializable
    @SerialName("smartycraft")
    data class Smartycraft(
        /** SC server id the join + auth bind to (e.g. `Industrial`). */
        @SerialName("server_id") val serverId: String,
    ) : SmrtAuth()
}

/**
 * Bridge the wire-shape [SmrtAuth] to the domain
 * [PackAuthRequirement] the launcher consumes. New provider variants
 * land here as the mirror grows additional `kind` values.
 */
fun SmrtAuth.toDomain(): PackAuthRequirement = when (this) {
    is SmrtAuth.Smartycraft -> PackAuthRequirement.SmartyCraft(serverId)
}

/**
 * Wire decoder that accepts the known [SmrtAuth] variants and silently
 * folds any other `kind` value (or a malformed payload) to null --
 * forward-compat for mirror manifests that gain `mojang` / `elyby` /
 * other providers before the client learns them. Without this the
 * default sealed-class serializer would throw on unknown discriminator
 * and abort the entire [SmrtPackManifest] decode, breaking browse +
 * install for older clients.
 *
 * Encoding stays on the standard sealed path so writing a known
 * requirement round-trips byte-identically.
 */
object SmrtAuthLenientSerializer : KSerializer<SmrtAuth?> {
    private val delegate = SmrtAuth.serializer().nullable
    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun deserialize(decoder: Decoder): SmrtAuth? {
        val jsonDecoder = decoder as? JsonDecoder ?: return delegate.deserialize(decoder)
        val element = jsonDecoder.decodeJsonElement()
        if (element is JsonNull) return null
        val obj = element as? JsonObject ?: return null
        val kind = obj["kind"]?.jsonPrimitive?.contentOrNull
        return when (kind) {
            "smartycraft" -> jsonDecoder.json.decodeFromJsonElement(SmrtAuth.Smartycraft.serializer(), obj)
            else          -> null
        }
    }

    override fun serialize(encoder: Encoder, value: SmrtAuth?) {
        delegate.serialize(encoder, value)
    }
}

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
    /**
     * Install-time default for an OPTIONAL entry (`required = false`): whether
     * it is enabled before the user touches it. Absent defaults to true, so an
     * optional mod installs unless the curator explicitly opts it out (e.g. a
     * mod that conflicts with a default-on one). Ignored for required entries,
     * which are always installed.
     */
    @SerialName("default_enabled") val defaultEnabled: Boolean = true,
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
@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
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
 *
 * [iconUrl], [role], and [requires] enable richer browse/library UX:
 * per-item icons, role-grouped pickers ("Recipe viewer" with a JEI ⇄
 * REI ⇄ EMI dropdown), and a dependency DAG the launcher can render
 * as a tree under each mod row. All three additive -- a manifest
 * without them parses cleanly and the launcher falls back to its
 * current rendering.
 */
@Serializable
data class SmrtDisplay(
    val name: String? = null,
    val description: String? = null,
    val category: String? = null,
    @SerialName("incompatible_with") val incompatibleWith: List<String> = emptyList(),
    val license: String? = null,
    val url: String? = null,
    /**
     * Per-item icon. Mirror serves directly for smrt_cache / smrt_static
     * entries; Modrinth-sourced entries leave this null and the launcher
     * resolves the project icon via the source's `project_id` on first
     * render (Coil caches the result).
     */
    @SerialName("icon_url") val iconUrl: String? = null,
    /**
     * Short tag for grouping interchangeable mods. Launcher renders all
     * mods with the same role as a single dropdown ("Recipe viewer: JEI
     * [v]" lets the user swap to REI / JER / EMI). Canonical values are
     * mirror-curated; the launcher does not enumerate them.
     */
    val role: String? = null,
    /**
     * DAG of same-manifest dependencies. Resolver validates every entry's
     * `filename` references an actual mods[] entry; missing references
     * surface as broken-manifest warnings at install time.
     */
    val requires: List<SmrtRequirement> = emptyList(),
)

/**
 * Single edge in a mod's dependency DAG. [filename] points at another
 * entry in the same manifest's mods[] list. [versionRange] follows
 * Maven-style range syntax (`>=4.0`, `[1.0,2.0)`); null means "any
 * version present is acceptable". [optional] = true means the consumer
 * works without the dep but works better with it -- the launcher shows
 * it greyed-out in the dep tree.
 */
@Serializable
data class SmrtRequirement(
    val filename: String,
    @SerialName("version_range") val versionRange: String? = null,
    val optional: Boolean = false,
)

/**
 * Catalogue card payload for the Browse surface.
 *
 * [iconUrl] / [bannerUrl] / [galleryUrls] / [descriptionMd] are the
 * "polish layer" -- a tagline-only catalogue reads as scaffolding even
 * when the engine underneath is solid. All four optional, additive,
 * and mirror-authored per pack release.
 */
@Serializable
data class SmrtPackSummary(
    @SerialName("pack_id") val packId: String,
    @SerialName("display_name") val displayName: String,
    val tagline: String,
    @SerialName("minecraft_version") val minecraftVersion: String,
    @SerialName("latest_pack_version") val latestPackVersion: String,
    val tags: List<String> = emptyList(),
    val featured: Boolean = false,
    /** Square pack icon. Renders in BrowsePackCard avatar slot + BrowsePackDetail hero. */
    @SerialName("icon_url") val iconUrl: String? = null,
    /** Wide hero image. Renders behind BrowsePackDetail hero text; falls back to the mirror gradient when absent. */
    @SerialName("banner_url") val bannerUrl: String? = null,
    /** Optional marketing screenshots. Rendered in a horizontal scroller on BrowsePackDetail when non-empty. */
    @SerialName("gallery_urls") val galleryUrls: List<String> = emptyList(),
    /** Long-form CommonMark description for the BrowsePackDetail About section. HTML is not parsed. */
    @SerialName("description_md") val descriptionMd: String? = null,
)

@Serializable
data class SmrtPackListing(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("generated_at") val generatedAt: String,
    val packs: List<SmrtPackSummary>,
)

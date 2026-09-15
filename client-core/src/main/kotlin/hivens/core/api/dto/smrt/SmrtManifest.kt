package hivens.core.api.dto.smrt

import hivens.core.data.PackAuthRequirement
import hivens.core.update.VersionChannel
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
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

/**
 * Wire shape of a v2 smrt mirror pack manifest. Mirrors the
 * API spec in the smrt mirror repo (`Kitty-Hivens/smrt`,
 * `docs/api.md`). Unknown fields are
 * tolerated (Json is configured with `ignoreUnknownKeys = true`) so a
 * future server-side `display` extension or fresh source variant does
 * not crash this client.
 */
@Serializable
data class SmrtPackManifest(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("pack_id") val packId: String,
    @SerialName("pack_version") val packVersion: String,
    /** Release channel of this build; absent on builds predating the field (see [versionChannel]). */
    val channel: String? = null,
    /** Curator-authored release notes for this build (CommonMark); absent when none were given. */
    val changelog: String? = null,
    @SerialName("generated_at") val generatedAt: String,
    /**
     * Hash of the shipped content set. Two builds with equal non-null
     * fingerprints carry identical files -- a label-only rebuild.
     */
    val fingerprint: String? = null,
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
) {
    /** Channel of this build, derived from the version string when [channel] is absent or unknown. */
    val versionChannel: VersionChannel get() = VersionChannel.of(channel, packVersion)
}

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
    private val log = LoggerFactory.getLogger(SmrtAuthLenientSerializer::class.java)
    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun deserialize(decoder: Decoder): SmrtAuth? {
        val jsonDecoder = decoder as? JsonDecoder ?: return delegate.deserialize(decoder)
        val element = jsonDecoder.decodeJsonElement()
        if (element is JsonNull) return null
        val obj = element as? JsonObject ?: return null
        val kind = obj["kind"]?.jsonPrimitive?.contentOrNull
        return when (kind) {
            // Same reading as the unknown kind, and for the same reason as in
            // [SmrtSourceLenientSerializer]: a block this client cannot make sense
            // of leaves the pack unrestricted rather than unopenable. The launcher
            // then asks for no sign-in where the mirror wanted one, so it is logged
            // at a level somebody reads.
            "smartycraft" -> runCatching {
                jsonDecoder.json.decodeFromJsonElement(SmrtAuth.Smartycraft.serializer(), obj)
            }.onFailure { log.warn("smrt manifest: a {} auth block did not decode, the pack is read as unrestricted", kind, it) }
                .getOrNull()
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
    /**
     * Curator-assigned stable identity for an optional entry. Used as the
     * [hivens.core.data.ContentToggle] key (via [stableKey]) so a user's
     * on/off choice survives a pack-version bump -- the [filename] carries the
     * mod version and changes on every update. Optional and additive: absent
     * means [stableKey] falls back to the source's own project id, then the
     * filename. The mirror should author this for an optional whose source
     * carries no project id of its own.
     */
    val slug: String? = null,
    @Serializable(with = SmrtSourceLenientSerializer::class)
    val source: SmrtSource,
    val display: SmrtDisplay? = null,
) {
    /**
     * Version-stable key for persisting optional-content toggles. Prefers the
     * curator [slug], then the project id of whichever host publishes the file,
     * then the [filename] as a last resort (which DOES change across versions --
     * it keeps pre-slug packs keyed on something, at the cost of orphaning on a
     * bump). Computed, so it is not serialized. See issue #339.
     *
     * A CurseForge project id is as stable as a Modrinth one and identifies the
     * same thing, so an optional mod pinned there keeps the player's choice across
     * a version bump instead of falling back to a filename that carries the mod
     * version in it.
     *
     * A GitHub source is keyed by the repository alone. The tag is the version by
     * definition, and the asset name carries it just as often (`mymod-1.2.3.jar`),
     * so either one re-keys the entry at every release, which is the exact failure
     * this key exists to prevent. The repository is what stays put. It cannot tell
     * apart two assets of one repository in one pack, and that is what [slug] is
     * for -- the curator names the rare case rather than every entry paying for it.
     */
    val stableKey: String
        get() = slug
            ?: (source as? SmrtSource.Modrinth)?.let { "modrinth:${it.projectId}" }
            ?: (source as? SmrtSource.CurseForge)?.let { "curseforge:${it.projectId}" }
            ?: (source as? SmrtSource.Github)?.let { "github:${it.repo}" }
            ?: filename
}

@Serializable
data class SmrtAssetEntry(
    val dest: String,
    val sha1: String,
    @SerialName("size_bytes") val sizeBytes: Long,
    val required: Boolean = true,
    @Serializable(with = SmrtSourceLenientSerializer::class)
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

    /**
     * A file published on CurseForge. [url] is the download the mirror
     * resolved at build time and is what the launcher fetches: turning a file
     * id into a link needs the mirror's API key, so a launcher never talks to
     * CurseForge, the same way it never needs a key of its own.
     *
     * [url] is absent exactly when the project's author has disabled
     * third-party distribution. The pair is still worth carrying there and
     * everywhere else: it says whose file this is in a form that outlives the
     * link, which is signed and expires.
     */
    @Serializable
    @SerialName("curseforge")
    data class CurseForge(
        @SerialName("project_id") val projectId: Long,
        @SerialName("file_id") val fileId: Long,
        val url: String? = null,
    ) : SmrtSource()

    /**
     * A file published as a GitHub release asset. [url] is where the launcher
     * fetches it, and unlike a CurseForge link it is public, derivable and
     * never expires, so it is always present.
     *
     * The three fields are carried beside it because they are what the pack
     * actually named. A release can be retagged or deleted, at which point the
     * link is the only thing that stops working and these still say what was
     * meant.
     */
    @Serializable
    @SerialName("github")
    data class Github(
        val repo: String,
        val tag: String,
        val asset: String,
        val url: String,
    ) : SmrtSource()

    /**
     * A `type` this client does not understand -- a mirror that gained another
     * provider before the launcher learned it. The
     * entry is kept so the rest of the manifest still decodes; the install
     * path skips it rather than failing the whole pack. Never emitted by us,
     * so the sentinel discriminator only appears on a cache round-trip.
     */
    @Serializable
    @SerialName("__unknown__")
    data object Unknown : SmrtSource()
}

/**
 * Wire decoder that accepts the known [SmrtSource] variants and folds any
 * other `type` value (or a malformed payload) to [SmrtSource.Unknown] --
 * forward-compat for manifests that gain new source providers. Without this
 * the default sealed-class serializer throws on an unknown discriminator and
 * aborts the entire [SmrtPackManifest] decode, breaking browse + install for
 * every entry, not just the new one.
 *
 * Encoding stays on the standard sealed path so a known source round-trips
 * byte-identically.
 */
object SmrtSourceLenientSerializer : KSerializer<SmrtSource> {
    private val delegate = SmrtSource.serializer()
    private val log = LoggerFactory.getLogger(SmrtSourceLenientSerializer::class.java)
    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun deserialize(decoder: Decoder): SmrtSource {
        val jsonDecoder = decoder as? JsonDecoder ?: return delegate.deserialize(decoder)
        val obj = jsonDecoder.decodeJsonElement() as? JsonObject ?: return SmrtSource.Unknown
        val type = obj["type"]?.jsonPrimitive?.contentOrNull
        val serializer: KSerializer<out SmrtSource> = when (type) {
            "modrinth"    -> SmrtSource.Modrinth.serializer()
            "smrt_cache"  -> SmrtSource.SmrtCache.serializer()
            "smrt_static" -> SmrtSource.SmrtStatic.serializer()
            "curseforge"  -> SmrtSource.CurseForge.serializer()
            "github"      -> SmrtSource.Github.serializer()
            else          -> return SmrtSource.Unknown
        }
        // A type this client knows, carrying a payload it does not: a field renamed,
        // a number where a string used to be, an id that outgrew what parses here.
        // Left to throw it aborts the whole manifest, so ONE entry the mirror and
        // the launcher disagree about costs the pack every other entry as well --
        // which is the exact failure the unknown-type branch exists to avoid, and
        // there is no reason for the two to end differently.
        return runCatching { jsonDecoder.json.decodeFromJsonElement(serializer, obj) }
            .onFailure { log.warn("smrt manifest: a {} source did not decode, the entry is treated as unsupported", type, it) }
            .getOrDefault(SmrtSource.Unknown)
    }

    override fun serialize(encoder: Encoder, value: SmrtSource) {
        // Go through the Json instance, not delegate.serialize(encoder, value):
        // a direct call emits the generic `["type", {...}]` array form and
        // skips the @JsonClassDiscriminator flattening, which would then decode
        // back as Unknown. encodeToJsonElement applies the discriminator.
        val jsonEncoder = encoder as? JsonEncoder ?: return delegate.serialize(encoder, value)
        jsonEncoder.encodeJsonElement(jsonEncoder.json.encodeToJsonElement(delegate, value))
    }
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
    /**
     * Advisory side classification (`required` / `optional_client` /
     * `optional_server` / `optional_both` / `coremod`). Display only --
     * [SmrtModEntry.required] stays the enforcing flag. See [presenceClass]
     * for the typed view.
     */
    val presence: String? = null,
) {
    /** Typed [presence]; null when the field is absent or carries an unknown value. */
    val presenceClass: SmrtPresence? get() = SmrtPresence.fromWire(presence)
}

/** Advisory side classification of a manifest entry, mirroring the mirror's `domain/side.rs`. */
enum class SmrtPresence(val wire: String) {
    Required("required"),
    OptionalClient("optional_client"),
    OptionalServer("optional_server"),
    OptionalBoth("optional_both"),
    Coremod("coremod");

    companion object {
        fun fromWire(wire: String?): SmrtPresence? =
            wire?.let { w -> entries.firstOrNull { it.wire.equals(w, ignoreCase = true) } }
    }
}

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
    /** When the latest build was published (RFC 3339); read-time derived by the mirror. */
    @SerialName("latest_built_at") val latestBuiltAt: String? = null,
    /** Channel of the latest build (`release` / `beta` / `alpha`); derived by the mirror. */
    @SerialName("latest_channel") val latestChannel: String? = null,
    /** Curation tier (`official` / `community`). */
    val tier: String? = null,
)

@Serializable
data class SmrtPackListing(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("generated_at") val generatedAt: String,
    val packs: List<SmrtPackSummary>,
)

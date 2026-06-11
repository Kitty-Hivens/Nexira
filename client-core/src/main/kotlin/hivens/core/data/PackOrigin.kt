package hivens.core.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

/**
 * Where a [Pack] comes from. Drives downstream choices that depend
 * on the source: which manifest format to parse, which adapter to
 * use for fetching files, which auth (if any) is required to play
 * on the bound server, which UI badges to show.
 *
 * Four shipped values plus [Unknown]. Adding a new origin is additive
 * (existing serialized packs continue to decode); removing one is
 * a wire break -- don't.
 */
@Serializable
enum class PackOrigin {
    /**
     * SmartyCraft launcher API. Pack identity is the SC `assetDir`
     * string. Requires SC auth at play time. SC's manifest format
     * is its own thing -- see SmartyCraftServerListService and the
     * legacy manifest-processor.
     */
    Smartycraft,

    /**
     * Hivens mirror at `smrt.hivens.dev`. Pack identity is the
     * mirror's `pack_id`. Manifest is the v2 spec in
     * `docs/src/content/docs/dev/smrt-api-spec.md`. Public read,
     * no auth required to browse / install.
     */
    Mirror,

    /**
     * Modrinth modpack. Pack identity is the Modrinth project id.
     * Public read via Modrinth's documented API. License-friendly
     * (Modrinth requires open redistribution by default).
     */
    Modrinth,

    /**
     * User-local pack. Created by the user (custom mod selection)
     * or forked from another origin. Identity is a generated id; no
     * remote manifest. Lives only on the user's machine until they
     * choose to publish.
     */
    Local,

    /**
     * An origin a newer build wrote that this build does not know.
     * Never emitted intentionally -- [PackOriginSerializer] folds an
     * unrecognised wire value here so an older build does not silently
     * misread it as [Smartycraft] (which would pick the wrong auth /
     * sync path). Callers treat it as "source unknown": no SC auth
     * redirect, no install path.
     */
    Unknown,
}

/** Persistence codec that folds an unknown wire origin to [PackOrigin.Unknown]. */
object PackOriginSerializer : KSerializer<PackOrigin> by LenientEnumSerializer(
    PackOrigin.entries.toTypedArray(),
    PackOrigin.Unknown,
    PackOrigin.serializer(),
)

/**
 * Cross-origin pack identifier. Used wherever a piece of state has
 * to point at a pack regardless of which source it came from --
 * notably [PackInstance.forkedFrom] for fork-tracking, and any
 * future cross-source operation (import-from-X, share-instance).
 */
@Serializable
data class PackReference(
    @Serializable(with = PackOriginSerializer::class)
    val origin: PackOrigin,
    /** Origin-specific identifier, same shape as [Pack.id]. */
    val id: String,
    /**
     * Specific pack version this reference points at, in the
     * origin's own version format. Null when the reference is
     * intentionally floating ("latest" semantics).
     */
    val version: String? = null,
)

package hivens.core.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Where a [Pack] comes from: the id of the source that lists it, as every pack record
 * stores it.
 *
 * Open, because a source is a module and a module can bring one this build has never
 * heard of. The id is kept exactly as the record wrote it, so a pack from a source that
 * is not loaded keeps its source, is written back unchanged and comes back to it when
 * the module does. Every record written while the origins were a closed list decodes to
 * what it was: `Mirror`, `Modrinth`, `Local`, `Smartycraft`, and `Unknown` where an
 * older build folded an origin it did not know. Two ids are one source only when they
 * are the same string.
 *
 * Two meanings belong to the core: [Local] and [Smartycraft]. Every other id belongs to
 * the source that declares it, and nothing here names one.
 */
@JvmInline
@Serializable(with = PackOriginSerializer::class)
value class PackOrigin(val id: String) {
    override fun toString(): String = id

    companion object {
        /**
         * A pack of the user's own: made in the launcher, imported, or detached from its
         * source. Its id is generated and it has no remote manifest.
         */
        val Local: PackOrigin = PackOrigin("Local")

        /**
         * The SmartyCraft launcher API. Nothing produces it since the server-list path was
         * retired, because SmartyCraft packs arrive from the mirror and declare their
         * server in the manifest. It stays so a record saved before then still decodes,
         * and such a record launches as any pack with no server binding.
         */
        val Smartycraft: PackOrigin = PackOrigin("Smartycraft")

        /**
         * The ids no source may declare: the core's two, and `Unknown`, which an older build
         * wrote where it read an origin it did not know.
         */
        val Reserved: Set<PackOrigin> = setOf(Local, Smartycraft, PackOrigin("Unknown"))
    }
}

/** Persistence codec for [PackOrigin]: the id as a string, read and written as it stands. */
object PackOriginSerializer : KSerializer<PackOrigin> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("hivens.core.data.PackOrigin", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): PackOrigin = PackOrigin(decoder.decodeString())

    override fun serialize(encoder: Encoder, value: PackOrigin) {
        encoder.encodeString(value.id)
    }
}

/**
 * Cross-origin pack identifier. Used wherever a piece of state has
 * to point at a pack regardless of which source it came from:
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

package hivens.core.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Lenient codec for a persisted enum: any wire string this build does not
 * recognise folds to [unknown] instead of throwing or -- under the shared
 * Json's `coerceInputValues` -- silently coercing to a wrong real constant.
 * Apply it at a persisted field (`@Serializable(with = ...)`) for an enum whose
 * value crosses a launcher version boundary, so an older build that meets a
 * value a newer build wrote lands on an honest sentinel callers can branch on
 * rather than on a plausible-but-wrong neighbour.
 *
 * Wire names come from [delegate]'s descriptor, so a constant carrying a
 * `@SerialName` maps by its serial name and a plain constant by its own name --
 * the codec stays correct if the enum later pins serial names.
 *
 * A value decoded as [unknown] re-serialises as [unknown]'s own wire name; the
 * original unknown string is NOT preserved (same limit as `SmrtSource.Unknown`).
 * This buys read honesty, not round-trip fidelity -- keeping an older build from
 * overwriting a newer file is the store-level schema_version write-gate's job.
 */
class LenientEnumSerializer<E : Enum<E>>(
    values: Array<E>,
    private val unknown: E,
    delegate: KSerializer<E>,
) : KSerializer<E> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor(delegate.descriptor.serialName, PrimitiveKind.STRING)

    private val byWire: Map<String, E> =
        values.associateBy { delegate.descriptor.getElementName(it.ordinal) }
    private val wireOf: Map<E, String> =
        values.associateWith { delegate.descriptor.getElementName(it.ordinal) }

    override fun deserialize(decoder: Decoder): E =
        byWire[decoder.decodeString()] ?: unknown

    override fun serialize(encoder: Encoder, value: E) {
        encoder.encodeString(wireOf.getValue(value))
    }
}

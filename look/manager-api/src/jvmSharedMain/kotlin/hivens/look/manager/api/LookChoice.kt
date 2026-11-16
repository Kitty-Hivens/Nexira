package hivens.look.manager.api

import kotlinx.serialization.Serializable

/**
 * The person's choice of look: a pack, and the provider family for any axis they want otherwise. Every
 * setting of a provider, a dark and a light variant or a blur say, is the provider's own and not here.
 *
 * Each axis is answered by the provider of the first of these that names one which is there: the
 * family in [overrides] for the axis, the family [pack] names for it, the family the default pack
 * names for it, and the axis's base answers where none does. A pack or a family that is not there,
 * a module away say, stays chosen and is passed over until it is back.
 *
 * Made with [build] or changed with the `with` functions, never through a constructor, so a field a
 * later launcher adds leaves a module built against this one working.
 */
@Serializable
class LookChoice private constructor(
    /** The pack, by id. Null where none was chosen, which is the default pack. */
    val pack: String? = null,
    /** The provider family per axis the person chose apart from the pack, by axis id. */
    val overrides: Map<String, String> = emptyMap(),
) {
    /** This choice with [pack], null for the default pack. */
    fun withPack(pack: String?): LookChoice = LookChoice(pack, overrides)

    /** This choice with [family] for [axis], or without a family of the person's own for it where [family] is null. */
    fun withOverride(axis: String, family: String?): LookChoice =
        LookChoice(pack, if (family == null) overrides - axis else overrides + (axis to family))

    /**
     * What choosing [other] on top of this one gives: its pack where it names one, and its families over
     * these axis by axis. A subtree's look is the person's choice with the subtree's laid over it this way.
     */
    fun overlaidWith(other: LookChoice): LookChoice = LookChoice(other.pack ?: pack, overrides + other.overrides)

    override fun equals(other: Any?): Boolean = other is LookChoice && other.pack == pack && other.overrides == overrides

    override fun hashCode(): Int = listOf(pack, overrides).hashCode()

    override fun toString(): String = "LookChoice(pack=$pack, overrides=$overrides)"

    /** Where [build] gathers a choice. */
    class Builder internal constructor() {
        /** See [LookChoice.pack]. */
        var pack: String? = null

        private val overrides = LinkedHashMap<String, String>()

        /** [family] for [axis], apart from the pack. */
        fun override(axis: String, family: String) {
            overrides[axis] = family
        }

        internal fun choice() = LookChoice(pack, overrides.toMap())
    }

    companion object {
        /** Nothing chosen: the default pack, no axis apart. */
        val DEFAULT: LookChoice = LookChoice()

        /** The choice [block] makes. */
        fun build(block: Builder.() -> Unit): LookChoice = Builder().apply(block).choice()
    }
}

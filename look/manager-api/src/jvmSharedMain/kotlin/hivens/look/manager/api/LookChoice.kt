package hivens.look.manager.api

import kotlinx.serialization.Serializable

/** Which of a provider's two variants the person wants, where the provider has both ([LookCapability.VARIANTS]). */
@Serializable
enum class LookVariant { Dark, Light }

/**
 * The person's choice of look: a pack, the provider family for any axis they want otherwise, and the
 * optional features the providers in use may offer.
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
    /** The variant, handed to the providers that declare [LookCapability.VARIANTS]. Null leaves it to each. */
    val variant: LookVariant? = null,
    /** Whether to blur, handed to the providers that declare [LookCapability.BLUR]. Null leaves it to each. */
    val blur: Boolean? = null,
) {
    /** This choice with [pack], null for the default pack. */
    fun withPack(pack: String?): LookChoice = LookChoice(pack, overrides, variant, blur)

    /** This choice with [family] for [axis], or without a family of the person's own for it where [family] is null. */
    fun withOverride(axis: String, family: String?): LookChoice =
        LookChoice(pack, if (family == null) overrides - axis else overrides + (axis to family), variant, blur)

    /** This choice with [variant], null to leave it to the providers. */
    fun withVariant(variant: LookVariant?): LookChoice = LookChoice(pack, overrides, variant, blur)

    /** This choice with [blur], null to leave it to the providers. */
    fun withBlur(blur: Boolean?): LookChoice = LookChoice(pack, overrides, variant, blur)

    /**
     * What choosing [other] on top of this one gives: its pack where it names one, its families over
     * these axis by axis, and its features where it sets them. A subtree's look is the person's choice
     * with the subtree's laid over it this way.
     */
    fun overlaidWith(other: LookChoice): LookChoice =
        LookChoice(other.pack ?: pack, overrides + other.overrides, other.variant ?: variant, other.blur ?: blur)

    override fun equals(other: Any?): Boolean =
        other is LookChoice && other.pack == pack && other.overrides == overrides && other.variant == variant && other.blur == blur

    override fun hashCode(): Int = listOf(pack, overrides, variant, blur).hashCode()

    override fun toString(): String = "LookChoice(pack=$pack, overrides=$overrides, variant=$variant, blur=$blur)"

    /** Where [build] gathers a choice. */
    class Builder internal constructor() {
        /** See [LookChoice.pack]. */
        var pack: String? = null

        /** See [LookChoice.variant]. */
        var variant: LookVariant? = null

        /** See [LookChoice.blur]. */
        var blur: Boolean? = null

        private val overrides = LinkedHashMap<String, String>()

        /** [family] for [axis], apart from the pack. */
        fun override(axis: String, family: String) {
            overrides[axis] = family
        }

        internal fun choice() = LookChoice(pack, overrides.toMap(), variant, blur)
    }

    companion object {
        /** Nothing chosen: the default pack, no axis apart, every feature left to the providers. */
        val DEFAULT: LookChoice = LookChoice()

        /** The choice [block] makes. */
        fun build(block: Builder.() -> Unit): LookChoice = Builder().apply(block).choice()
    }
}

package hivens.look.manager.api

import hivens.widget.model.ApiVersion

/** Where the provider answering an axis came from in the choice. */
enum class LookSource {
    /** The person chose its family for this axis apart from the pack. */
    Override,

    /** The chosen pack names it. */
    Pack,

    /** The chosen pack names none that is there, and the default pack names it. */
    DefaultPack,

    /** Nothing named one that is there, and the axis's base answers hold. */
    Base,
}

// The classes below are the manager's answers, which modules read and never make. They are not data
// classes, so a field a later launcher adds is no change to the members a module built now calls.

/**
 * One axis as the choice resolves now: the [family] answering it, null where its base answers hold,
 * where that came from, and the optional features of the provider in use.
 */
class LookAxisState(
    val id: String,
    val contract: ApiVersion,
    val family: String?,
    val source: LookSource,
    val capabilities: Set<String>,
) {
    override fun equals(other: Any?): Boolean = other is LookAxisState && other.id == id && other.contract == contract &&
        other.family == family && other.source == source && other.capabilities == capabilities

    override fun hashCode(): Int = listOf(id, contract, family, source, capabilities).hashCode()

    override fun toString(): String = "LookAxisState(id=$id, contract=$contract, family=$family, source=$source, capabilities=$capabilities)"
}

/** A provider on offer: the module that brought it, its axis and family, its label in that module's words, and its optional features. */
class LookProviderEntry(
    val moduleId: String,
    val axis: String,
    val family: String,
    val label: String,
    val capabilities: Set<String>,
) {
    override fun equals(other: Any?): Boolean = other is LookProviderEntry && other.moduleId == moduleId && other.axis == axis &&
        other.family == family && other.label == label && other.capabilities == capabilities

    override fun hashCode(): Int = listOf(moduleId, axis, family, label, capabilities).hashCode()

    override fun toString(): String = "LookProviderEntry(moduleId=$moduleId, axis=$axis, family=$family, label=$label, capabilities=$capabilities)"
}

/** A pack on offer: the module that brought it, its id, its label in that module's words, and the family it names per axis. */
class LookPackEntry(
    val moduleId: String,
    val id: String,
    val label: String,
    val providers: Map<String, String>,
) {
    override fun equals(other: Any?): Boolean =
        other is LookPackEntry && other.moduleId == moduleId && other.id == id && other.label == label && other.providers == providers

    override fun hashCode(): Int = listOf(moduleId, id, label, providers).hashCode()

    override fun toString(): String = "LookPackEntry(moduleId=$moduleId, id=$id, label=$label, providers=$providers)"
}

/** The look as the manager holds it now: the [choice], every axis as it resolves, and what is on offer. */
class LookState(
    val choice: LookChoice,
    val axes: List<LookAxisState>,
    val providers: List<LookProviderEntry>,
    val packs: List<LookPackEntry>,
) {
    /** The axis of [id], or null where no module brings it. */
    fun axis(id: String): LookAxisState? = axes.firstOrNull { it.id == id }

    /**
     * Whether the provider answering the axis [axis] declares [capability], so a setting for it is offered.
     * Never where the axis's base answers hold or no module brings the axis.
     */
    fun offers(axis: String, capability: String): Boolean = axis(axis)?.capabilities?.contains(capability) == true

    /**
     * Whether a provider in use on any axis declares [capability]: whether the manager offers the setting
     * for it at all, the variant ([LookCapability.VARIANTS]) or the blur ([LookCapability.BLUR]) say.
     */
    fun offers(capability: String): Boolean = axes.any { capability in it.capabilities }

    override fun equals(other: Any?): Boolean =
        other is LookState && other.choice == choice && other.axes == axes && other.providers == providers && other.packs == packs

    override fun hashCode(): Int = listOf(choice, axes, providers, packs).hashCode()

    override fun toString(): String = "LookState(choice=$choice, axes=$axes, providers=$providers, packs=$packs)"

    companion object {
        /** No axis, no provider and no pack. */
        val EMPTY: LookState = LookState(LookChoice.DEFAULT, emptyList(), emptyList(), emptyList())
    }
}

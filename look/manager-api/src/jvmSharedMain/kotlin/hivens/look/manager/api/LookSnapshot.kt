package hivens.look.manager.api

import kotlinx.coroutines.flow.StateFlow

/**
 * The answers of every axis at one moment, for code outside composition: window chrome, system bars, a
 * tray icon, a service that times what it shows. The same answers [LookKeys.Provide] provides at that
 * moment, held as one value so two axes read from it agree with each other.
 *
 * The manager makes it and every module reads it, the way it reads [LookState]. A question one axis asks
 * in another axis's terms is answered from the same snapshot, `snapshot.of(Chrome).captionHover(snapshot.of(Colour))`.
 */
class LookSnapshot(answers: Map<String, Any>) {
    private val answers: Map<String, Any> = answers.toMap()

    /** [axis]'s answers in this snapshot, its base answers where no module brings it or they are not of its type. */
    @Suppress("UNCHECKED_CAST")
    // The answers are held to the axis's own type just above, so the cast cannot fail.
    fun <A : Any> of(axis: LookAxis<A>): A = answers[axis.id]?.takeIf { axis.type.isInstance(it) } as A? ?: axis.base

    override fun equals(other: Any?): Boolean = other is LookSnapshot && other.answers == answers

    override fun hashCode(): Int = answers.hashCode()

    override fun toString(): String = "LookSnapshot($answers)"

    companion object {
        /** No axis answered: every axis reads its base answers. */
        val EMPTY: LookSnapshot = LookSnapshot(emptyMap())
    }
}

/**
 * The look for code outside composition, as an app service every module may read: its answers and its
 * state, each as it changes. Where no manager is brought in, nothing declares it.
 */
interface LookView {
    /** Every axis's answers now, see [LookSnapshot]. */
    val answers: StateFlow<LookSnapshot>

    /** Which provider answers each axis and why, and what is on offer, as [LookKeys.State] answers it. */
    val state: StateFlow<LookState>
}

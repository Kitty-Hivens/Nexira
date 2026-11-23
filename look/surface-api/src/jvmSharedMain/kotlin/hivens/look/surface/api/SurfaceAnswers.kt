package hivens.look.surface.api

import androidx.compose.runtime.Immutable

/**
 * Every answer of the surface axis. A widget reads them through [Surface]. A provider fills them in
 * with [build].
 *
 * A plane is asked by its kind, a category every interface has, with the plane that holds it and the
 * top step of the ladder ([plane]), since depth is relative to what holds it and a floating plane goes
 * to the top. A provider answers every kind from one rule of its own.
 *
 * A role a provider leaves unfilled keeps its base answer: the planes Compose draws where nobody
 * chose, flat and opaque in the colour of what holds them, with no edge and no blur. [BASE] is every
 * role at its base answer: a look with no planes of its own.
 *
 * Answers are never changed: a provider hands new ones whenever one of its roles changes.
 */
@Immutable
class SurfaceAnswers private constructor(filled: Builder) {

    private val planeRule = filled.plane

    /** The plane of [kind] held by [holder], with [floating] the top step the look's colours reach. */
    fun plane(kind: SurfaceKind, holder: Plane, floating: Int): PlaneAnswer =
        planeRule?.invoke(kind, holder, floating) ?: Neutral.plane(kind, holder)

    /** What a widget's own plate takes for a field its node leaves empty. Base the kind's opacity, no blur, no edge. */
    val plate: PlateAnswer = filled.plate ?: Neutral.PLATE

    /**
     * Whether this look draws a backdrop blur at all. False drops every blur, one a widget sets on its
     * own plane included, the way a platform that cannot blur drops it: the plane keeps its body, its
     * opacity and its shape and only stops filtering what is behind it. Base true.
     */
    val blurs: Boolean = filled.blurs ?: true

    /**
     * Where a provider fills in the roles it answers, every one empty at first, which keeps its base
     * answer. Each is named as the role it fills on [SurfaceAnswers].
     */
    class Builder internal constructor() {
        /** See [SurfaceAnswers.plane]. */
        var plane: ((kind: SurfaceKind, holder: Plane, floating: Int) -> PlaneAnswer)? = null

        /** See [SurfaceAnswers.plate]. */
        var plate: PlateAnswer? = null

        /** See [SurfaceAnswers.blurs]. */
        var blurs: Boolean? = null
    }

    companion object {
        /** Every role at its base answer: the surface axis where no provider answers it. */
        val BASE: SurfaceAnswers = SurfaceAnswers(Builder())

        /** The answers [block] fills in, every role it leaves alone at its base answer. */
        fun build(block: Builder.() -> Unit): SurfaceAnswers = SurfaceAnswers(Builder().apply(block))
    }
}

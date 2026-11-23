package hivens.look.type.api

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.font.FontFamily

/**
 * Every answer of the type axis. A widget reads them through [Type]. A provider fills them in with
 * [build].
 *
 * The look answers which face text is drawn in, and nothing that moves a layout: the size, the line
 * height and the tracking of every style are the component's.
 *
 * A role a provider leaves unfilled keeps its base answer, Compose's own families: the platform's
 * default for every role, its monospace for code, and no face of its own for a script the default
 * lacks. [BASE] is every role at its base answer, the type axis when no provider is chosen for it.
 *
 * Answers are never changed: a provider hands new ones whenever one of its roles changes.
 */
@Immutable
class TypeAnswers private constructor(filled: Builder) {

    private val faceRule = filled.face
    private val forTextRule = filled.forText

    /** The face text of [role] is drawn in. */
    fun face(role: TextRole): TypeFace = faceRule?.invoke(role) ?: Neutral.DEFAULT

    /** The face code, hexadecimal values and console output are drawn in. */
    val mono: TypeFace = filled.mono ?: Neutral.MONO

    /**
     * The face for [text] where it holds a script the role's face cannot draw, null where the role's
     * face covers it. Asked per string rather than per language, since a title in one script turns up
     * in an interface in another all the time.
     */
    fun forText(text: String): TypeFace? = forTextRule?.invoke(text)

    /**
     * Where a provider fills in the roles it answers, every one empty at first, which keeps its base
     * answer. Each is named as the role it fills on [TypeAnswers].
     */
    class Builder internal constructor() {
        /** See [TypeAnswers.face]. */
        var face: ((role: TextRole) -> TypeFace)? = null

        /** See [TypeAnswers.mono]. */
        var mono: TypeFace? = null

        /** See [TypeAnswers.forText]. */
        var forText: ((text: String) -> TypeFace?)? = null
    }

    companion object {
        /** Every role at its base answer: the type axis when no provider is chosen for it. */
        val BASE: TypeAnswers = TypeAnswers(Builder())

        /** The answers [block] fills in, every role it leaves alone at its base answer. */
        fun build(block: Builder.() -> Unit): TypeAnswers = TypeAnswers(Builder().apply(block))
    }
}

/** The faces of the base answers: Compose's own families. */
internal object Neutral {
    val DEFAULT: TypeFace = TypeFace.of(FontFamily.Default)
    val MONO: TypeFace = TypeFace.of(FontFamily.Monospace)
}

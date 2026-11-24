package hivens.look.cursor.api

import androidx.compose.runtime.Immutable
import androidx.compose.ui.input.pointer.PointerIcon

/**
 * Every answer of the cursor axis. A widget reads them through [Cursor]. A provider fills them in with
 * [build].
 *
 * A pointer is Compose's own type, so a provider answers with the platform's pointers or with any it
 * makes. Null leaves a role alone: the widget asks for no pointer, and the one around it stays.
 *
 * A rule a provider leaves unfilled keeps that of the answers it is built over, the base ones unless it
 * says others. [BASE] is the cursor axis when no provider is chosen for it: the arrow over what is
 * pressed, usable or not, the text bar over text and the hand over a link, as Compose has them, the
 * platform's arrows over an edge, without which nobody finds one, and nothing for dragging.
 *
 * Answers are never changed: a provider hands new ones whenever one of its rules changes.
 */
@Immutable
class CursorAnswers private constructor(filled: Builder, over: CursorAnswers?) {

    private val pointerRule: (CursorRole) -> PointerIcon? = filled.pointer ?: over?.pointerRule ?: Neutral::pointer
    private val resizeRule: (CursorEdge) -> PointerIcon? = filled.resize ?: over?.resizeRule ?: ::platformResize

    /** The pointer over what does [role], null where the look leaves it alone. */
    fun pointer(role: CursorRole): PointerIcon? = pointerRule(role)

    /** The pointer over an [edge] that is pulled to resize, null where the look leaves it alone. */
    fun resize(edge: CursorEdge): PointerIcon? = resizeRule(edge)

    /**
     * Where a provider fills in the rules it answers, every one empty at first. Each is asked while
     * drawing, so it looks nothing up slowly, and answers one pointer, the same instance, for one ask.
     */
    class Builder internal constructor() {
        /** See [CursorAnswers.pointer]. */
        var pointer: ((role: CursorRole) -> PointerIcon?)? = null

        /** See [CursorAnswers.resize]. */
        var resize: ((edge: CursorEdge) -> PointerIcon?)? = null
    }

    companion object {
        /** The cursor axis when no provider is chosen for it. */
        val BASE: CursorAnswers = CursorAnswers(Builder(), null)

        /** The answers [block] fills in, every rule it leaves alone as in [over]. */
        fun build(over: CursorAnswers = BASE, block: Builder.() -> Unit): CursorAnswers = CursorAnswers(Builder().apply(block), over)
    }
}

internal object Neutral {
    fun pointer(role: CursorRole): PointerIcon? = when (role) {
        CursorRole.Pressable, CursorRole.Disabled -> PointerIcon.Default
        CursorRole.Link -> PointerIcon.Hand
        CursorRole.Text -> PointerIcon.Text
        CursorRole.Draggable, CursorRole.Dragging, CursorRole.Refused -> null
    }
}

/** The platform's own arrow for pulling [edge], one instance per edge. */
internal expect fun platformResize(edge: CursorEdge): PointerIcon

package hivens.look.icon.api

import androidx.compose.runtime.Immutable

/**
 * Every answer of the icon axis. A widget reads them through [Icon]. A provider fills them in with
 * [build].
 *
 * A provider is an icon library and answers one thing: what draws an icon of a name. The size, the
 * colour, the filling and the weight are the component's.
 *
 * [BASE] is the icon axis when no provider is chosen for it: the few icons a control cannot be found or
 * worked without, closing, going back, a check, more, the chevrons and adding, as plain strokes, and
 * every other icon blank.
 *
 * Answers are never changed: a provider hands new ones whenever its library does.
 */
@Immutable
class IconAnswers private constructor(filled: Builder) {

    private val library = filled.library

    /**
     * What draws [key]: the library's drawing of its name, the component's own where the library has
     * none, the contract's where neither is there, and null where nothing draws it.
     */
    fun drawing(key: IconKey): IconDrawing? {
        val name = key.name ?: return key.own
        return library?.invoke(name) ?: key.own ?: Essentials.of(name)
    }

    /**
     * Where a provider fills in what it answers, empty at first, which keeps the base answer.
     */
    class Builder internal constructor() {
        /**
         * What draws the icon of a Material Symbols name, null where the library has no such icon. The
         * same drawing on every call for one name, and asked while drawing, so it looks nothing up slowly.
         */
        var library: ((name: String) -> IconDrawing?)? = null
    }

    companion object {
        /** The icon axis when no provider is chosen for it. */
        val BASE: IconAnswers = IconAnswers(Builder())

        /** The answers [block] fills in. */
        fun build(block: Builder.() -> Unit): IconAnswers = IconAnswers(Builder().apply(block))
    }
}

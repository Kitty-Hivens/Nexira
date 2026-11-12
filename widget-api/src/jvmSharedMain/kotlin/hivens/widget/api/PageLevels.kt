package hivens.widget.api

import androidx.compose.runtime.RememberObserver
import hivens.widget.model.ScopeLevel

/**
 * The page levels under one level, one per history entry, keyed by the entry's
 * identity. Got from [WidgetScope.pages], which makes one per level and closes the
 * page levels with it.
 *
 * A page level outlives the content drawn for it. The content goes when the reader
 * steps to another page, and the level stays while its entry is anywhere in the
 * history, back or forward, so coming back finds what the page had made. It goes
 * once the entry has left the history and nothing holds it any more: an entry a
 * switch dropped is still fading out on screen, and its content keeps reading its
 * level until it is gone.
 *
 * The key is what the owner says makes two entries the same place. The launcher's
 * router uses the screen's retention key, so the two entries of a history that
 * went A, B, A share one level, the way they share their saved state. Values handed
 * over per entry would collide there. Keying by entry instead needs the router to
 * swap by entry too, and then both copies of A can be on screen during a fade,
 * which their shared saved state does not allow, so that change belongs with the
 * outlets that replace the router.
 *
 * Not thread safe. The owner calls it from its composition and the effects there.
 */
class PageLevels internal constructor(private val parent: WidgetScope) {

    private val levels = HashMap<String, WidgetScope>()

    private val held = HashMap<String, Int>()

    // Made since the last keep(), so the history the owner passed then could not
    // name them yet. Spared by the sweep until the next keep() has had its say.
    private val fresh = HashSet<String>()

    private var kept: Set<String> = emptySet()

    /** The keys that have a live level, for whoever has to check the rule above. */
    val alive: Set<String> get() = levels.keys.toSet()

    /**
     * The level of the entry [key], made the first time it is asked for. [install]
     * runs on a level just made, before anything reads it, for the constants the
     * owner hands an entry when it opens it (see [WidgetScope.provide]).
     */
    fun levelFor(key: String, install: WidgetScope.() -> Unit = {}): WidgetScope {
        levels[key]?.takeUnless { it.closed }?.let { return it }
        val level = parent.child(ScopeLevel.Page)
        levels[key] = level
        fresh += key
        level.install()
        return level
    }

    /** The entries in the history now. A level whose entry is not among them goes once nothing holds it. */
    fun keep(keys: Set<String>) {
        kept = keys
        fresh.clear()
        sweep()
    }

    /**
     * Holds the level of [key] from now until the returned hold is released.
     *
     * Counted from the moment it is made, not from an effect: a frame applies
     * every disposal before any new effect, so a page leaving in the frame another
     * one appears would otherwise sweep the newcomer before it was counted. For
     * the composition it is a [RememberObserver] released when forgotten or
     * abandoned, and anything else closes it.
     */
    fun hold(key: String): Hold {
        held[key] = (held[key] ?: 0) + 1
        return Hold(key)
    }

    /** One hold on a page level. See [hold]. */
    inner class Hold internal constructor(private val key: String) : RememberObserver, AutoCloseable {
        private var released = false

        override fun onRemembered() = Unit
        override fun onForgotten() = close()
        override fun onAbandoned() = close()

        override fun close() {
            if (released) return
            released = true
            val left = (held[key] ?: 1) - 1
            if (left > 0) held[key] = left else held.remove(key)
            sweep()
        }
    }

    private fun sweep() {
        val gone = levels.keys.filter { it !in kept && it !in held && it !in fresh }
        gone.forEach { key -> levels.remove(key)?.closeLogged("the page level of '$key'") }
    }
}

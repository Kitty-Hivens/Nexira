package hivens.catalogue

import hivens.catalogue.api.OpenProject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The one place in a window that says which project or pack is open, and the only
 * channel between a page and the rail beside it.
 *
 * The page publishes, the rail's widgets read. Neither reaches for the other: the rail
 * is a surface that outlives any screen. Cleared on the way out, because a rail still
 * describing a project the person has left is worse than an empty one.
 *
 * One visit owns the rail at a time, the one that [claim]ed it last. A screen fades
 * from one page to the next, so for a moment two are shown, and the one leaving still
 * has loads in flight. Only the owner's [publish] reaches the rail. Every visit that
 * claimed is remembered with what it last published until it releases, so a Back taken
 * inside the fade hands the rail back to the page it returns to rather than emptying it.
 *
 * What a page was called goes to [names], which the app keeps for every window.
 */
class OpenProjects(private val names: PageNames = PageNames()) {
    private val _open = MutableStateFlow<OpenProject?>(null)
    val open: StateFlow<OpenProject?> = _open.asStateFlow()

    /** The visits shown, oldest first, each with what it last published. The last one owns the rail. */
    private val visits = LinkedHashMap<Any, OpenProject?>()

    private val owner: Any? get() = visits.keys.lastOrNull()

    /** Makes [visit] the one the rail describes. */
    fun claim(visit: Any) = synchronized(this) {
        val published = visits.remove(visit)
        visits[visit] = published
    }

    /** Keeps [project] as [visit]'s, and shows it when [visit] owns the rail. */
    fun publish(visit: Any, project: OpenProject?) = synchronized(this) {
        if (visit !in visits) return@synchronized
        visits[visit] = project
        if (owner == visit) _open.value = project
    }

    /** Forgets [visit]. The rail goes to the visit still shown under it when [visit] owned it, and comes down when none is. */
    fun release(visit: Any) = synchronized(this) {
        val owned = owner == visit
        visits.remove(visit)
        if (owned) _open.value = owner?.let { visits[it] }
    }

    /** Records [title] as what [targetKey] is called, once the page knows it. */
    fun name(targetKey: String, title: String) = names.name(targetKey, title)
}

/**
 * What the pages visited lately were called, by target key, kept for the app. The rail
 * forgets a page the moment the person leaves it, which is wrong for a trail: a project
 * page left behind under a search opened from one of its tags is still a crumb. Kept
 * above every window, so a crumb in a second window names a page the first one read.
 */
class PageNames {
    private val _names = MutableStateFlow<Map<String, String>>(emptyMap())

    val names: StateFlow<Map<String, String>> = _names.asStateFlow()

    /** Records [title] as what [targetKey] is called, once the page knows it. */
    @Synchronized
    fun name(targetKey: String, title: String) {
        val current = _names.value
        if (current[targetKey] == title) return
        // The newest last, and the oldest dropped past a bound: a long session reads
        // hundreds of projects and a trail never holds more than a few.
        _names.value = (current - targetKey + (targetKey to title)).entries
            .toList()
            .takeLast(MAX_NAMES)
            .associate { it.key to it.value }
    }

    private companion object {
        const val MAX_NAMES = 64
    }
}

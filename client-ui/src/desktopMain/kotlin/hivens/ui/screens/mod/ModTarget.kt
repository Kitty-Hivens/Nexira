package hivens.ui.screens.mod

import hivens.launcher.instance.ContentKind

/**
 * What a project page was opened on.
 *
 * Two cases rather than one nullable id, because they are genuinely different
 * questions and only one of them can always be answered. A catalogue entry is
 * addressed by its id and exists whether or not anything is installed. A file in
 * an instance is addressed by the file, and the catalogue may never have indexed
 * it: a jar built by hand, pulled from a forum, or shipped inside a pack from
 * somewhere else. Addressing that one by a project id would mean no page at all
 * for the files a reader is least able to look up elsewhere.
 *
 * Small and value-like on purpose, because it rides in the back stack. The page
 * resolves everything else at render time, the way [hivens.ui.Screen.PackDetail]
 * resolves its instance from a UUID.
 */
sealed class ModTarget {

    /**
     * A catalogue entry, installed or not.
     *
     * [intoInstanceId] is the pack the reader was browsing from, and it is the
     * whole reason the page can offer to install: a project id says what to
     * install and nothing about where. Null when the page was reached from
     * somewhere with no pack behind it, and the page then offers no install
     * rather than guessing a destination.
     */
    data class Catalogue(val projectId: String, val intoInstanceId: String? = null) : ModTarget()

    /** A file inside an instance, which the catalogue may or may not know. */
    data class Installed(
        val instanceId: String,
        val kind: ContentKind,
        val fileName: String,
    ) : ModTarget()

    /** Identity for the back stack and for per-visit retained state. */
    val key: String
        get() = when (this) {
            is Catalogue -> "catalogue:$projectId:${intoInstanceId.orEmpty()}"
            is Installed -> "installed:$instanceId:${kind.name}:$fileName"
        }
}

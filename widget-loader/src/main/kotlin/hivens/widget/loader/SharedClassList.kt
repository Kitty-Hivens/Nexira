package hivens.widget.loader

/**
 * The classes an artifact of the launcher shares with widget modules class by class,
 * as its build lists them.
 *
 * Such an artifact writes every class it holds, one binary name per line, to
 * [pathOf] its name. A class is shared because it came out of that artifact, not
 * because of the package it is in, which is what lets the design system share
 * `hivens.ui.theme` with modules while the launcher's own classes in that package stay
 * the launcher's. The list is read off the application at run time, so it holds in a
 * merged jar as it does on a class path of separate ones.
 */
object SharedClassList {

    /** Where the artifact named [artifact] lists its classes. */
    fun pathOf(artifact: String): String = "META-INF/nexira/shared/$artifact.classes"

    /** The classes [artifact] lists through [host], or null when the application carries no list for it. */
    fun read(host: ClassLoader, artifact: String): Set<String>? {
        val stream = host.getResourceAsStream(pathOf(artifact)) ?: return null
        return stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toSet()
        }
    }
}

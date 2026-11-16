package hivens.widget.api

import java.nio.file.Path

/**
 * The launcher's data directory and how the host writes into it, for a module compiled into the
 * launcher that keeps files of its own there beside its settings domains: a file a build from before
 * the module still reads, a folder a person puts files into.
 *
 * One of the host's own services, which it hands to the modules it trusts and to no jar.
 */
@HostApi
interface HostFiles {
    /** The launcher's data directory. */
    val dataDir: Path

    /**
     * Writes [content] to [file] whole or leaves the file as it was, and makes the folders it lacks.
     * Throws where it could not, and for a [file] outside [dataDir], a link that leads out of it
     * included.
     */
    fun publish(file: Path, content: String)

    /** Runs [block], a save, where the host runs its own: off the event thread and one after another. [what] names it in the log. */
    fun write(what: String, block: () -> Unit)

    /**
     * Tells the host that [file] was written by a newer build and is read and never written this
     * session, so the person can be told that what they change in it is not kept. [name] is what the
     * person is told the file as: a key into the words of [module], the module that keeps it.
     */
    fun newerBuild(file: Path, module: String, name: String)
}

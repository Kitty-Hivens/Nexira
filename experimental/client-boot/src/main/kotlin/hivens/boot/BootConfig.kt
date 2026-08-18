package hivens.boot

/**
 * Which modules the loader brings up, and in what order.
 *
 * Deliberately flat and dumb. This is read before anything is loaded, by the
 * only parser that exists at that point, so it carries no schema version, no
 * migration ladder and no nested shapes -- the thing that decides whether the
 * configuration system loads cannot itself be the configuration system.
 * Everything richer (namespaces, declared schemas, generated pages, migrations)
 * belongs to the user configuration, which a module owns and which nothing on
 * this path touches.
 *
 * [bootstrap] names what comes up before the rest. It is listed rather than
 * hardcoded so the set is inspectable and replaceable: a launcher whose
 * configuration store or recovery surface is swapped for another says so here.
 *
 * [modules] is everything else, in no significant order -- dependencies decide
 * that, and dependency resolution runs only after the bootstrap set is up.
 */
data class BootConfig(
    val bootstrap: List<String>,
    val modules: List<ModuleEntry>,
) {
    /** The ordinary modules that are wanted. The bootstrap set is [bootstrap] and
     *  is deliberately not merged in: those come up before the configuration
     *  module exists, while these go through dependency resolution afterwards.
     *  One flat list would invite a caller to treat the two as one sequence. */
    fun wantedModules(): List<String> = modules.filter { it.enabled }.map { it.id }

}

/**
 * One module and whether it is wanted.
 *
 * Absence of an entry means the module is not loaded at all; an entry with
 * [enabled] false means it is known and deliberately off, which is what the
 * recovery surface writes and what a later build must not quietly re-enable.
 */
data class ModuleEntry(
    val id: String,
    val enabled: Boolean = true,
)

/**
 * What the boot file turned out to be, which the loader needs to tell apart.
 *
 * A first run and a corrupt file both produce "no usable configuration", and
 * treating them the same is how a fresh install lands in an error state or a
 * corrupt one silently reverts to defaults, overwriting whatever the user had.
 */
sealed interface BootState {
    /** The file parsed. */
    data class Loaded(val config: BootConfig) : BootState

    /** No file yet: a first run, seeded from the bundled default and written. */
    data object Absent : BootState

    /**
     * A file exists and could not be read -- a truncated write, a power cut.
     *
     * The loader falls back to the bundled default, which is not a guess: it
     * ships with the build and therefore names modules this installation
     * actually has. It does NOT overwrite the damaged file, which stays on disk
     * for inspection; a launcher that repairs itself by deleting the evidence
     * has taken the user's only lead.
     */
    data class Unreadable(val reason: String) : BootState
}

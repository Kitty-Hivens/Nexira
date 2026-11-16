package hivens.widget.api

import hivens.widget.model.ScopeLevel
import hivens.widget.model.SourceKey
import java.nio.file.Path

/**
 * What a module compiled into the launcher answers of its own app sources before the host is up.
 *
 * The window the launcher boots in is drawn and speaks before the host's services, the document or
 * any module's keys exist, so it cannot read a source the way a widget does. It asks every module
 * compiled into the build for what it can work out from [BootContext] instead: the files under the
 * data directory and the settings the host keeps, read and not kept, with nothing written. A value the
 * window needs that no module answers is drawn with the host's own fallback.
 *
 * What a module answers here has to be what its keys answer once they are up over the same files, or
 * the window changes under the person as the boot ends. So a module works both out with one reading of
 * those files, and holds the two equal in its tests.
 *
 * Asked once, off the event thread, of every module compiled in that the document names, as the shell
 * brings in only those once it is up. A module answers only the app sources its keys declare and reads
 * only the settings domains it declares, the rules its keys keep at runtime, and an answer that strays
 * from them is refused whole. A jar is not loaded yet and is never asked. What a module's words, keys or
 * answer throw costs only that module's values.
 */
fun interface BootAnswers {
    /** The values of this module's app sources that [context] holds enough to work out. */
    fun answer(context: BootContext): BootValues
}

/** What the host hands a module asked for its [BootAnswers]. */
interface BootContext {
    /** The launcher's data directory, which the module's own files are named against. */
    val dataDir: Path

    /**
     * What [domain] holds as the host opens it: its own file, or what the launcher's former settings
     * file holds of it while it has none (see [SettingsDomain.imported]). Read and not kept, and nothing
     * is written, so a value taken back from the former file is taken again when the host opens it.
     * Throws for a domain the asking module does not declare.
     */
    fun <T : Any> settings(domain: SettingsDomain<T>): T

    /**
     * What the host can hand over before it is up: [TakenWords], holding what the host and every
     * module compiled in ship, and nothing of a jar's.
     */
    val services: LevelServices
}

/**
 * The values some app sources have, by key: what a module answers in [BootAnswers.answer], and what the
 * host gathers from every module that answers.
 */
class BootValues private constructor(private val values: Map<String, Any?>) {

    /** The value of [key], or null where none was given. A key whose value may be null is told apart by [contains]. */
    @Suppress("UNCHECKED_CAST")
    // The same seam as WidgetDataRegistry.get: a value only ever goes in through put with the key's own type.
    operator fun <T> get(key: SourceKey<T>): T? = values[key.id] as T?

    /** Whether a value was given for [key]. */
    operator fun contains(key: SourceKey<*>): Boolean = key.id in values

    /** The ids of the keys a value was given for. */
    val ids: Set<String> get() = values.keys

    /** These, and [other]'s values for the keys these give none for. */
    infix fun over(other: BootValues): BootValues = BootValues(other.values + values)

    /** Where a module puts the values it answers. */
    class Builder internal constructor() {
        internal val values = LinkedHashMap<String, Any?>()

        /** [value] for [key], which lives at the app level, the only level there is before the host is up. */
        fun <T> put(key: SourceKey<T>, value: T) {
            require(key.level == ScopeLevel.App) { "'${key.id}' lives at ${key.level}, and only an app source is answered before the host is up" }
            values[key.id] = value
        }
    }

    companion object {
        /** No values at all. */
        val NONE: BootValues = BootValues(emptyMap())

        /** The values [build] puts. */
        fun of(build: Builder.() -> Unit): BootValues = BootValues(Builder().apply(build).values.toMap())
    }
}

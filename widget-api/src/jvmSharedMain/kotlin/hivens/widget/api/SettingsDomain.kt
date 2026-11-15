package hivens.widget.api

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * A module's own settings: one typed value with its defaults, kept by the host in a file of its own.
 *
 * A module declares one through [ModuleKeys.settings], which also names the app source that reads
 * the value and the command that changes it, both in the module's own API. The host keeps the file:
 * it reads it once, hands out the defaults where it is absent or cannot be read, writes every change
 * whole, and leaves a file stamped by a newer [schema] alone for the session. Nothing of the value's
 * shape is the host's business past [serializer].
 *
 * A class rather than an interface so it can grow: a later launcher may read more from a domain, the
 * fields a generic settings screen draws for instance, as open members with a default, and a module
 * built before that keeps working unchanged.
 */
abstract class SettingsDomain<T : Any>(
    /** Stable across versions, since the file is named by it. Lower case letters, digits, dots, dashes and underscores. */
    val id: String,
    /** How the value is written to its file and read back. */
    val serializer: KSerializer<T>,
) {
    /** The value where nothing is stored, or what is stored cannot be read. */
    abstract fun defaults(): T

    /**
     * The version of the value's shape this module writes. A file stamped with a higher one was written
     * by a newer build of the module, so the host reads what it can of it and writes nothing back.
     */
    open val schema: Int get() = 1

    /**
     * The value as the launcher's former single settings file held it, [legacy] being that file, or null
     * where it holds nothing of this domain.
     *
     * Asked at every start while the domain has no file of its own, and while its file cannot be read,
     * so a person's choice made before the move is kept until a change of the domain is saved. Asked
     * again whenever the fields [mirrored] wrote to that file were changed there since, which is a build
     * from before the move changing the choice, so going back to an older build and returning loses
     * nothing either.
     */
    open fun imported(legacy: JsonObject): T? = null

    /**
     * The fields of the former settings file that hold [value] for a build from before the move, by
     * their name in that file. The host writes these, and only these, to that file with every change
     * of the domain, and remembers what it wrote. None by default, and a domain that was never in the
     * former file has none.
     */
    open fun mirrored(value: T): Map<String, JsonElement> = emptyMap()

    companion object {
        private val ID = Regex("[a-z0-9][a-z0-9._-]*")

        /** Why [id] cannot name a domain, or null when it can. */
        fun faultIn(id: String): String? = when {
            !ID.matches(id) -> "settings domain id '$id' is not lower case letters, digits, dots, dashes and underscores"
            id.contains("..") -> "settings domain id '$id' has two dots in a row"
            else -> null
        }
    }
}

/** A change to a domain's value: the value it becomes, made from the value it is. */
fun interface SettingsChange<T> {
    fun apply(current: T): T
}

/** One domain's value as the host keeps it. */
interface SettingsStore<T : Any> {
    /** The value, and every value it takes. */
    val value: StateFlow<T>

    /**
     * Whether the file was written by a newer build of its module. Changes are still taken and seen for
     * the session, and none of them is written.
     */
    val readOnly: Boolean

    /**
     * Makes [change] of the value. Changes are applied one at a time in the order they were asked for,
     * each to the value the one before left, and the file is replaced whole with the result.
     */
    fun update(change: SettingsChange<T>)
}

/**
 * Where a module reads its domain's store from, for a service or a factory that needs the value beside
 * the source [ModuleKeys.settings] already makes. Found in `services` of the context a module's factory
 * or service maker is handed, and there it answers only the domains that module declared: asked for
 * another module's, it throws. A widget holding a level finds none.
 */
interface SettingsStores {
    /** The store of [domain]. The same one for as long as the module that declared it is the same load of it. */
    fun <T : Any> of(domain: SettingsDomain<T>): SettingsStore<T>
}

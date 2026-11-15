package hivens.ui.settings

import hivens.core.data.NewerBuildData
import hivens.core.data.ReadOnlyStore
import hivens.core.io.AtomicFiles
import hivens.core.io.readUtf8
import hivens.widget.api.SettingsChange
import hivens.widget.api.SettingsDomain
import hivens.widget.api.SettingsStore
import hivens.widget.api.SettingsStores
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

private val log = LoggerFactory.getLogger("SettingsDomains")

/**
 * Where the modules' settings domains are kept: one file per domain, `<id>.json` in [dir], holding
 * the domain's schema, its value, and what of the value was last written to the former settings
 * file, `{"schema": 1, "value": {...}, "mirrored": {...}}`.
 *
 * The host keeps the files and nothing of what is in them: each domain brings its own value type,
 * defaults and serializer (see [SettingsDomain]).
 *
 * Where a domain's file is absent, and where it cannot be read, the domain gets what [legacy], the
 * launcher's former single settings file, holds of it (see [SettingsDomain.imported]), else its
 * defaults. Nothing is written for that: the file of a domain is first written by its first change,
 * and a file that could not be read is left as it is until then. So until a change is saved, every
 * start asks the former file again.
 *
 * Every change also writes the fields the domain mirrors into the former file, for a build from
 * before the domain existed, and records what it wrote. A start that finds the former file holding
 * something else in those fields knows an older build changed them since, and takes that.
 *
 * A file stamped with a schema newer than the domain's is read as far as it can be and never
 * written, for that domain alone, and the former file is left alone for it too.
 *
 * Every change is applied to the value at once, in the order the changes were asked for, and the
 * file is then replaced whole through [write], which runs a save where the host wants it, off the
 * event thread say. A save writes the value as it is when the save runs, so the last one to land
 * always holds the last value.
 */
class SettingsDomainFiles(
    private val dir: Path,
    private val legacy: LegacySettings,
    private val write: (what: String, block: () -> Unit) -> Unit = { _, block -> block() },
) : SettingsStores {

    /** Over the former settings file at [legacyFile], read and written directly. */
    constructor(dir: Path, legacyFile: Path, write: (what: String, block: () -> Unit) -> Unit = { _, block -> block() }) :
        this(dir, LegacySettingsFile(legacyFile), write)

    private val stores = HashMap<String, Store<*>>()

    /**
     * The store of [domain]. One per domain id while the same declaration of it is asked for. A
     * module loaded again declares its domain afresh, with classes of its new loader, and gets a
     * store of its own.
     */
    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> of(domain: SettingsDomain<T>): SettingsStore<T> = synchronized(stores) {
        val before = stores[domain.id]
        if (before != null && before.domain === domain) return before as SettingsStore<T>
        // A store being replaced may still have a save queued, so the file can be behind it: the
        // value is carried over through its serialized form rather than read back from the disk.
        val loaded = before?.let { carried(it, domain) } ?: read(domain)
        Store(domain, loaded).also { stores[domain.id] = it }
    }

    /**
     * [from]'s value as [to] declares it. Whether the file is a newer build's is decided again for
     * [to], as a start would, since the module loaded again may declare another schema.
     */
    private fun <A : Any, T : Any> carried(from: Store<A>, to: SettingsDomain<T>): Loaded<T>? = try {
        val value = json.decodeFromJsonElement(to.serializer, json.encodeToJsonElement(from.domain.serializer, from.value.value))
        Loaded(value, readOnly = newerThan(to, stampOf(fileOf(to))))
    } catch (e: Exception) {
        log.warn("Settings of '{}' could not be carried to the module loaded again ({}), so they are read from the file", to.id, e.toString())
        null
    }

    /** What [domain] holds now, read without opening a store: for a reader that runs before the host is up. */
    fun <T : Any> peek(domain: SettingsDomain<T>): T = read(domain).value

    /** The file [domain] is kept in. */
    fun fileOf(domain: SettingsDomain<*>): Path = dir.resolve("${domain.id}.json")

    /** A value as read, whether it may be written, and whether it was taken back from the former file. */
    private class Loaded<T>(val value: T, val readOnly: Boolean, val retaken: Boolean = false)

    private fun <T : Any> read(domain: SettingsDomain<T>): Loaded<T> {
        val file = fileOf(domain)
        val former by lazy { legacy.read() }
        val fallback = { former?.let { imported(domain, it) } ?: domain.defaults() }
        if (!Files.exists(file)) return Loaded(fallback(), readOnly = false)
        val root = try {
            json.parseToJsonElement(readUtf8(file)).jsonObject
        } catch (e: Exception) {
            log.warn("Settings of '{}' at {} could not be read ({}), so they are taken from the former settings file or the defaults, and the file is left as it is until a change", domain.id, file, e.toString())
            return Loaded(fallback(), readOnly = false)
        }
        val schema = (root[SCHEMA] as? JsonPrimitive)?.intOrNull ?: 1
        val newer = newerThan(domain, schema)
        val value = try {
            json.decodeFromJsonElement(domain.serializer, root[VALUE] ?: JsonObject(emptyMap()))
        } catch (e: Exception) {
            log.warn("Settings of '{}' at {} hold a value that could not be read ({}), so they are taken from the former settings file or the defaults", domain.id, file, e.toString())
            return Loaded(fallback(), readOnly = newer)
        }
        if (!newer && changedSince(root[MIRRORED] as? JsonObject, former)) {
            val taken = former?.let { imported(domain, it) }
            if (taken != null) {
                log.info("Settings of '{}' were changed in the former settings file by an older build since they were last written, and are taken from there", domain.id)
                return Loaded(taken, readOnly = false, retaken = true)
            }
        }
        return Loaded(value, readOnly = newer)
    }

    /** Whether [former] holds, in a field [recorded] names, something else than what was written there. */
    private fun changedSince(recorded: JsonObject?, former: JsonObject?): Boolean {
        if (recorded == null || former == null) return false
        return recorded.any { (field, written) -> former[field].let { it != null && it != written } }
    }

    private fun newerThan(domain: SettingsDomain<*>, stamp: Int?): Boolean {
        val newer = stamp != null && stamp > domain.schema
        if (newer) {
            log.warn("Settings of '{}' are schema {} > supported {}: written by a newer build, read only", domain.id, stamp, domain.schema)
            NewerBuildData.record(ReadOnlyStore.Settings)
        }
        return newer
    }

    /** The schema [file] is stamped with, or null where there is no file or it cannot be read. */
    private fun stampOf(file: Path): Int? = runCatching {
        if (!Files.exists(file)) return null
        (json.parseToJsonElement(readUtf8(file)).jsonObject[SCHEMA] as? JsonPrimitive)?.intOrNull ?: 1
    }.getOrNull()

    private fun <T : Any> imported(domain: SettingsDomain<T>, former: JsonObject): T? = try {
        domain.imported(former)
    } catch (e: Exception) {
        log.warn("Could not take the settings of '{}' from the former settings file: {}", domain.id, e.toString())
        null
    }

    private inner class Store<T : Any>(val domain: SettingsDomain<T>, loaded: Loaded<T>) : SettingsStore<T> {
        private val flow = MutableStateFlow(loaded.value)
        private val changing = Any()

        // Held while the file is written, so a save reads the value and lands it before the next one starts.
        private val saving = Any()

        override val value: StateFlow<T> = flow.asStateFlow()
        override val readOnly: Boolean = loaded.readOnly

        init {
            // Taken back from the former file: written, so the record of what is there is current
            // and the next start does not take it again.
            if (loaded.retaken && !readOnly) write("the settings of '${domain.id}'") { save() }
        }

        override fun update(change: SettingsChange<T>) {
            synchronized(changing) {
                val next = change.apply(flow.value)
                if (next == flow.value) return
                flow.value = next
            }
            if (readOnly) {
                log.warn("Not writing the settings of '{}': they belong to a newer build and this session is read only", domain.id)
                return
            }
            write("the settings of '${domain.id}'") { save() }
        }

        private fun save() = synchronized(saving) {
            val now = flow.value
            val fields = domain.mirrored(now)
            val mirrored: Map<String, JsonElement>? = if (fields.isEmpty()) null else try {
                legacy.write(fields)
                fields
            } catch (e: Exception) {
                log.warn("Could not write the settings of '{}' to the former settings file: {}", domain.id, e.toString())
                null
            }
            val body = buildMap {
                put(SCHEMA, JsonPrimitive(domain.schema))
                put(VALUE, json.encodeToJsonElement(domain.serializer, now))
                mirrored?.let { put(MIRRORED, JsonObject(it)) }
            }
            AtomicFiles.writeString(fileOf(domain), json.encodeToString(JsonObject.serializer(), JsonObject(body)))
        }
    }

    companion object {
        private const val SCHEMA = "schema"
        private const val VALUE = "value"
        private const val MIRRORED = "mirrored"

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
            coerceInputValues = true
        }
    }
}

package hivens.ui.identity

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Local, on-disk skin library: user-imported skin PNGs plus a small index.
 * Skins live at `<dir>/<id>.png`; `<dir>/library.json` holds the non-pixel
 * metadata. Provider-agnostic -- the wardrobe applies a chosen skin to whichever
 * account is targeted (SmartyCraft or Mojang upload) from the stored file, so the
 * library never talks to a server itself.
 *
 * Tolerant of a missing or corrupt index (treated as empty), and of a deleted png
 * behind a live index entry ([bytes] returns null). The caller supplies the
 * timestamp to [add] so the ordering is testable.
 */
class SkinLibrary(private val dir: Path, private val json: Json) {
    private val log = LoggerFactory.getLogger(SkinLibrary::class.java)

    /** A library entry is either a player skin or a cape (cloak). */
    enum class Kind { Skin, Cape }

    @Serializable
    data class Entry(
        val id: String,
        val name: String,
        val slim: Boolean = false,
        val addedAt: Long = 0L,
        // When this entry was last applied to a provider -- the library doubles as
        // the history, so the most-recently-applied entry (per kind) is the active one.
        val lastAppliedAt: Long? = null,
        // Skin vs cape; pre-existing entries (no field) default to Skin.
        val kind: Kind = Kind.Skin,
    )

    @Serializable
    private data class Index(val skins: List<Entry> = emptyList())

    private val indexFile: Path get() = dir.resolve("library.json")

    /** Newest first; [kind] null lists everything, else just that kind. */
    fun list(kind: Kind? = null): List<Entry> = readIndex().skins
        .filter { kind == null || it.kind == kind }
        .sortedByDescending { it.addedAt }

    fun file(id: String): Path = dir.resolve("$id.png")

    fun bytes(id: String): ByteArray? =
        file(id).takeIf { Files.exists(it) }?.let { runCatching { Files.readAllBytes(it) }.getOrNull() }

    /** Imports [png] under [name] as a skin or cape; returns the new entry. */
    fun add(png: ByteArray, name: String, slim: Boolean, now: Long, kind: Kind = Kind.Skin): Entry {
        Files.createDirectories(dir)
        val id = UUID.randomUUID().toString().take(12)
        Files.write(file(id), png)
        val entry = Entry(id, name.ifBlank { kind.name.lowercase() }, slim, now, kind = kind)
        writeIndex(Index(readIndex().skins + entry))
        log.info("Imported {} {} ({} bytes)", kind.name.lowercase(), id, png.size)
        return entry
    }

    fun rename(id: String, name: String) {
        writeIndex(Index(readIndex().skins.map { if (it.id == id) it.copy(name = name.ifBlank { it.name }) else it }))
    }

    /** Records that [id]'s skin was applied (to a provider) at [now]. */
    fun markApplied(id: String, now: Long) {
        writeIndex(Index(readIndex().skins.map { if (it.id == id) it.copy(lastAppliedAt = now) else it }))
    }

    /** The id of the most-recently-applied entry of [kind] -- the active one. */
    fun activeId(kind: Kind = Kind.Skin): String? = readIndex().skins
        .filter { it.kind == kind && it.lastAppliedAt != null }
        .maxByOrNull { it.lastAppliedAt!! }?.id

    fun delete(id: String) {
        runCatching { Files.deleteIfExists(file(id)) }
        writeIndex(Index(readIndex().skins.filterNot { it.id == id }))
    }

    private fun readIndex(): Index {
        if (!Files.exists(indexFile)) return Index()
        return runCatching { json.decodeFromString(Index.serializer(), Files.readString(indexFile)) }
            .getOrElse {
                log.warn("skin library index unreadable -- treating as empty")
                Index()
            }
    }

    private fun writeIndex(index: Index) {
        Files.createDirectories(dir)
        Files.writeString(indexFile, json.encodeToString(Index.serializer(), index))
    }
}

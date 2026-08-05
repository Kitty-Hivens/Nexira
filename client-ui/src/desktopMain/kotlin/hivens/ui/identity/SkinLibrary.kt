package hivens.ui.identity

import hivens.core.io.AtomicFiles
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Local, on-disk skin library: user-imported skin PNGs plus a small index.
 * Skins live at `<dir>/<id>.png`; `<dir>/library.json` holds the non-pixel
 * metadata. Provider-agnostic -- the wardrobe applies a chosen skin to whichever
 * account is targeted (SmartyCraft or Mojang upload) from the stored file, so the
 * library never talks to a server itself.
 *
 * Tolerant of a deleted png behind a live index entry ([bytes] returns null).
 * The caller supplies the timestamp to [add] so the ordering is testable.
 *
 * A missing index is an empty library. An UNREADABLE one is not: every mutator
 * here is a read-modify-write, so reading a truncated index as empty and then
 * saving would persist that emptiness over the real thing and orphan every png.
 * [readIndex] therefore quarantines a corrupt index and rebuilds what it can from
 * the files on disk -- the pixels are the durable part; only the names, kinds and
 * timestamps are lost. Writes go through [AtomicFiles], so the truncation that
 * starts this story cannot happen from our own side in the first place.
 *
 * Mutations are serialised on [lock]: the wardrobe re-imports the server skin on
 * every open, which can overlap a user import, and two read-modify-writes racing
 * would drop one of the entries.
 */
class SkinLibrary(private val dir: Path, private val json: Json) {
    private val log = LoggerFactory.getLogger(SkinLibrary::class.java)
    private val lock = Any()

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
        // Content fingerprint (pixel hash) for dedup; null on pre-existing entries.
        val sha: String? = null,
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
    fun add(png: ByteArray, name: String, slim: Boolean, now: Long, kind: Kind = Kind.Skin, sha: String? = null): Entry =
        synchronized(lock) {
            Files.createDirectories(dir)
            val id = UUID.randomUUID().toString().take(12)
            AtomicFiles.writeBytes(file(id), png)
            val entry = Entry(id, name.ifBlank { kind.name.lowercase() }, slim, now, kind = kind, sha = sha)
            writeIndex(Index(readIndex().skins + entry))
            log.info("Imported {} {} ({} bytes)", kind.name.lowercase(), id, png.size)
            entry
        }

    /**
     * Adds [png] unless an entry of [kind] already carries the same content [sha]
     * (a pixel hash, stable across PNG re-encodings), so re-importing the same
     * texture -- e.g. the current server skin on every wardrobe open -- does not
     * pile up duplicates. Returns the existing match, else the freshly-added entry.
     * A null [sha] (undecodable) skips the dedup and always adds.
     */
    fun addUnique(png: ByteArray, name: String, slim: Boolean, now: Long, sha: String?, kind: Kind = Kind.Skin): Entry {
        if (sha != null) readIndex().skins.firstOrNull { it.kind == kind && it.sha == sha }?.let { return it }
        return add(png, name, slim, now, kind, sha)
    }

    /** Rewrites the index under [lock]; [transform] sees the entries as they are on disk. */
    private fun mutate(transform: (List<Entry>) -> List<Entry>) = synchronized(lock) {
        writeIndex(Index(transform(readIndex().skins)))
    }

    fun rename(id: String, name: String) = mutate { skins ->
        skins.map { if (it.id == id) it.copy(name = name.ifBlank { it.name }) else it }
    }

    /** Records that [id]'s skin was applied (to a provider) at [now]. */
    fun markApplied(id: String, now: Long) = mutate { skins ->
        skins.map { if (it.id == id) it.copy(lastAppliedAt = now) else it }
    }

    /** The id of the most-recently-applied entry of [kind] -- the active one. */
    fun activeId(kind: Kind = Kind.Skin): String? = readIndex().skins
        .filter { it.kind == kind && it.lastAppliedAt != null }
        .maxByOrNull { it.lastAppliedAt!! }?.id

    fun delete(id: String) = synchronized(lock) {
        runCatching { Files.deleteIfExists(file(id)) }
        writeIndex(Index(readIndex().skins.filterNot { it.id == id }))
    }

    private fun readIndex(): Index {
        if (!Files.exists(indexFile)) return Index()
        return runCatching { json.decodeFromString(Index.serializer(), Files.readString(indexFile)) }
            .getOrElse { recoverIndex() }
    }

    /**
     * Salvages a library whose index will not parse. Reading it as empty would be
     * fine on its own, but the next mutation writes that emptiness back and the
     * pngs become unreachable forever. Move the bad file aside so it can still be
     * inspected, and readmit every png on disk under its own id -- the names,
     * kinds and timestamps are gone, the pictures are not.
     */
    private fun recoverIndex(): Index = synchronized(lock) {
        val quarantined = indexFile.resolveSibling("${indexFile.fileName}.corrupt")
        runCatching { Files.move(indexFile, quarantined, StandardCopyOption.REPLACE_EXISTING) }
            .onFailure { log.warn("could not set the unreadable skin index aside", it) }
        val recovered = runCatching {
            Files.list(dir).use { stream ->
                stream.filter { it.fileName.toString().endsWith(".png") }
                    .map { Entry(id = it.fileName.toString().removeSuffix(".png"), name = "") }
                    .toList()
            }
        }.getOrElse { emptyList() }
        log.warn(
            "skin library index unreadable -- kept it at {} and rebuilt {} entries from the files on disk",
            quarantined, recovered.size,
        )
        if (recovered.isNotEmpty()) writeIndex(Index(recovered))
        Index(recovered)
    }

    private fun writeIndex(index: Index) {
        AtomicFiles.writeString(indexFile, json.encodeToString(Index.serializer(), index))
    }
}

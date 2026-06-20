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

    @Serializable
    data class Entry(
        val id: String,
        val name: String,
        val slim: Boolean = false,
        val addedAt: Long = 0L,
    )

    @Serializable
    private data class Index(val skins: List<Entry> = emptyList())

    private val indexFile: Path get() = dir.resolve("library.json")

    /** Newest first. */
    fun list(): List<Entry> = readIndex().skins.sortedByDescending { it.addedAt }

    fun file(id: String): Path = dir.resolve("$id.png")

    fun bytes(id: String): ByteArray? =
        file(id).takeIf { Files.exists(it) }?.let { runCatching { Files.readAllBytes(it) }.getOrNull() }

    /** Imports [png] under [name]; returns the new entry. */
    fun add(png: ByteArray, name: String, slim: Boolean, now: Long): Entry {
        Files.createDirectories(dir)
        val id = UUID.randomUUID().toString().take(12)
        Files.write(file(id), png)
        val entry = Entry(id, name.ifBlank { "skin" }, slim, now)
        writeIndex(Index(readIndex().skins + entry))
        log.info("Imported skin {} ({} bytes)", id, png.size)
        return entry
    }

    fun rename(id: String, name: String) {
        writeIndex(Index(readIndex().skins.map { if (it.id == id) it.copy(name = name.ifBlank { it.name }) else it }))
    }

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

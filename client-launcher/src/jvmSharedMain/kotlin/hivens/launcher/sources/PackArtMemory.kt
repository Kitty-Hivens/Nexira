package hivens.launcher.sources

import hivens.core.io.AtomicFiles
import hivens.core.io.readUtf8
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * What sources answered about packs' covers on earlier runs, by the key [PackArtResolver]
 * asks under, so a cover is drawn on the first frame of a run rather than after the
 * source has been asked again.
 */
interface PackArtMemory {
    fun load(): Map<String, PackArt>

    fun save(art: Map<String, PackArt>)

    companion object {
        /** Keeps nothing from one run to the next. */
        val None: PackArtMemory = object : PackArtMemory {
            override fun load(): Map<String, PackArt> = emptyMap()
            override fun save(art: Map<String, PackArt>) = Unit
        }
    }
}

/**
 * [PackArtMemory] in one small JSON file. It is a cache: a file that cannot be read is
 * taken as empty and written over, and one that cannot be written costs the next start
 * its first frame.
 */
class FilePackArtMemory(private val file: Path, private val json: Json) : PackArtMemory {
    private val log = LoggerFactory.getLogger(FilePackArtMemory::class.java)

    override fun load(): Map<String, PackArt> {
        if (!Files.isRegularFile(file)) return emptyMap()
        return runCatching { json.decodeFromString<Map<String, PackArt>>(readUtf8(file)) }
            .onFailure { log.warn("Remembered pack covers at {} are unreadable and are asked for again", file, it) }
            .getOrDefault(emptyMap())
    }

    override fun save(art: Map<String, PackArt>) {
        runCatching { AtomicFiles.writeString(file, json.encodeToString(art)) }
            .onFailure { log.warn("Pack covers could not be remembered at {}", file, it) }
    }
}

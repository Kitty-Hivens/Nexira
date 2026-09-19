package hivens.ui.layout

import hivens.core.io.AtomicFiles
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Which bundled widgets a graph has been offered, on disk beside it.
 *
 * A sibling file rather than a field in the layout envelope, and the reason is the
 * downgrade. The envelope refuses to be written by a build older than the schema it
 * carries, so putting this there would mean bumping the schema, and an older build
 * would then hold the whole layout read-only: every edit a person made would stop
 * persisting, to protect an array of bookkeeping ids. A file an older build has
 * never heard of costs it nothing instead, and the worst a downgrade does is offer
 * a widget again on the next upgrade.
 *
 * Written only when the set actually grows, which after the launch that adopts a
 * release is never. No shutdown flush and no debounce for the same reason: this is
 * touched once at load, not per keystroke like the widget state or per drag frame
 * like the graph.
 */
class SeededWidgets(
    private val file: Path,
    private val json: Json,
) {

    private val log = LoggerFactory.getLogger(SeededWidgets::class.java)

    /**
     * The ids on disk, or null when the file is not there.
     *
     * Null and empty are different answers and the difference decides behaviour: an
     * absent file is a graph from before this record existed, whose set has to be
     * derived from what it holds, while an empty one is a deliberate record that
     * nothing has been offered yet. An unreadable file reads as absent, which
     * re-derives rather than re-offering everything.
     */
    fun load(): Set<String>? {
        if (!Files.exists(file)) return null
        return try {
            json.decodeFromString<Envelope>(Files.readString(file)).offered
        } catch (e: Exception) {
            log.warn("Seeded-widget record at {} is unreadable; deriving it from the graph instead", file, e)
            null
        }
    }

    fun save(offered: Set<String>) {
        try {
            AtomicFiles.writeString(file, json.encodeToString(Envelope(offered = offered)))
        } catch (e: Exception) {
            // A failed write costs one repeated offer on the next launch, which the
            // user answers by removing the widget again. It is not worth failing a
            // load over.
            log.warn("Failed to record the seeded widgets at {}", file, e)
        }
    }

    @Serializable
    private data class Envelope(
        val version: Int = VERSION,
        val offered: Set<String> = emptySet(),
    )

    private companion object {
        const val VERSION = 1
    }
}

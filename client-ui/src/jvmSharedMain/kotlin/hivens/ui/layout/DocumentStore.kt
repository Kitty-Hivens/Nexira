package hivens.ui.layout

import hivens.core.data.NewerBuildData
import hivens.core.data.ReadOnlyReason
import hivens.core.data.ReadOnlyStore
import hivens.core.io.AtomicFiles
import hivens.core.io.readUtf8
import hivens.widget.model.Document
import hivens.widget.model.DocumentCodec
import hivens.widget.model.DocumentRead
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

private val log = LoggerFactory.getLogger("hivens.ui.layout.DocumentStore")

/**
 * The document the launcher draws: the person's own, kept in [file], or the standard one the launcher ships where
 * they have none.
 *
 * Having a file of one's own is expert mode, and nothing else marks it: the standard document is replaced by every
 * update, a document of one's own never is. The first edit makes one ([takeOwn]), a copy of the standard document with
 * its modules, so an update does not change the modules of a person who has edited either.
 *
 * A file this build cannot take as it is, written by a newer build or not a document at all, is left exactly as it
 * is: the standard document is drawn meanwhile, nothing is written, and the session says so. [save] is where a write
 * runs, off the caller's thread in the launcher.
 */
class DocumentStore(
    private val file: Path,
    private val standard: () -> Document,
    private val save: (what: String, block: () -> Unit) -> Unit = { _, block -> block() },
) {
    private val read: Loaded = load()

    private val state = MutableStateFlow(read.document)

    /** The document drawn now. */
    val document: StateFlow<Document> = state.asStateFlow()

    private val ownState = MutableStateFlow(read.own)

    /** Whether the person keeps a document of their own. */
    val own: StateFlow<Boolean> = ownState.asStateFlow()

    /** False for a file this build left as it was, which nothing here writes over for the whole session. */
    val writable: Boolean get() = read.reason == null

    /**
     * Makes the document the person's own, a copy of the standard one with its modules, unless it is already. False
     * where the file there is one this build does not write.
     */
    @Synchronized
    fun takeOwn(): Boolean {
        if (!writable) return false
        if (ownState.value) return true
        val copy = state.value
        persist(copy)
        ownState.value = true
        return true
    }

    /**
     * Changes the person's own document by [transform] and saves it. False, with nothing changed, where the document
     * is not their own yet ([takeOwn] first) or not one this build writes.
     */
    @Synchronized
    fun update(transform: (Document) -> Document): Boolean {
        if (!writable || !ownState.value) return false
        val next = transform(state.value)
        if (next == state.value) return true
        state.value = next
        persist(next)
        return true
    }

    private fun persist(document: Document) {
        val text = DocumentCodec.write(document)
        save("the interface") { AtomicFiles.writeString(file, text) }
    }

    private class Loaded(val document: Document, val own: Boolean, val reason: ReadOnlyReason?)

    private fun load(): Loaded {
        if (!Files.isRegularFile(file)) return Loaded(standard(), own = false, reason = null)
        val text = try {
            readUtf8(file)
        } catch (e: Exception) {
            log.warn("The interface in {} could not be read, the standard one is drawn and the file left as it is", file, e)
            return leftAsItIs(ReadOnlyReason.Damaged)
        }
        return when (val read = DocumentCodec.read(text)) {
            is DocumentRead.Whole -> Loaded(read.document, own = true, reason = null)
            is DocumentRead.Newer -> {
                log.warn("The interface in {} was written by a newer build (schema {}), the standard one is drawn and the file left as it is", file, read.schema)
                leftAsItIs(ReadOnlyReason.NewerBuild)
            }
            is DocumentRead.Broken -> {
                log.warn("The interface in {} is not a document ({}), the standard one is drawn and the file left as it is", file, read.reason)
                leftAsItIs(ReadOnlyReason.Damaged)
            }
        }
    }

    private fun leftAsItIs(reason: ReadOnlyReason): Loaded {
        NewerBuildData.record(ReadOnlyStore.Layout, reason)
        return Loaded(standard(), own = true, reason = reason)
    }
}

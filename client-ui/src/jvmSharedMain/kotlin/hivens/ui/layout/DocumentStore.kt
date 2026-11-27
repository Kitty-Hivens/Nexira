package hivens.ui.layout

import hivens.core.data.NewerBuildData
import hivens.core.data.ReadOnlyReason
import hivens.core.data.ReadOnlyStore
import hivens.core.io.AtomicFiles
import hivens.core.io.readUtf8
import hivens.ui.bootstrap.RecoveryIo
import hivens.widget.model.Document
import hivens.widget.model.DocumentCodec
import hivens.widget.model.DocumentRead
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

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
 * is: the standard document is drawn meanwhile, nothing is written, and the session says so. A document of a shape this
 * build no longer reads is deleted instead and the standard one drawn, which [takeOutdatedNotice] says once. [save] is where a write
 * runs, off the caller's thread in the launcher, one at a time and in order.
 *
 * An edit is drawn at once and written later. A drag edits on every frame, so while one write waits its turn the
 * edits after it ride on it, and when it runs it writes what the person meant last: the document as it stands, or no
 * file at all once they have gone back to the standard one.
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

    private val outdatedUnsaid = AtomicBoolean(read.outdated)

    /**
     * True once, the first time it is asked after a document of a shape this build no longer reads was deleted, so the
     * interface says so once however often it is drawn again.
     */
    fun takeOutdatedNotice(): Boolean = outdatedUnsaid.getAndSet(false)

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
        ownState.value = true
        sync()
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
        sync()
        return true
    }

    /**
     * Gives up the person's own document: the standard one is drawn again, and each update replaces it again. The file
     * goes. False where the file there is one this build does not write.
     */
    @Synchronized
    fun dropOwn(): Boolean {
        if (!writable) return false
        if (!ownState.value) return true
        ownState.value = false
        state.value = standard()
        sync()
        return true
    }

    /** Whether the document drawn is the standard one as it stands, the person's own included. */
    val matchesStandard: Boolean get() = state.value == standard()

    // Whether a write is queued and has not begun. The edits made meanwhile ride on it.
    private val queued = AtomicBoolean(false)

    private fun sync() {
        if (!queued.compareAndSet(false, true)) return
        save("the interface") {
            queued.set(false)
            // The recovery surface deleted the file under the running launcher: a write queued before that would bring
            // back the interface the person just reset.
            if (RecoveryIo.stateWasReset) return@save
            synchronized(this) {
                if (ownState.value) AtomicFiles.writeString(file, DocumentCodec.write(state.value)) else Files.deleteIfExists(file)
            }
        }
    }

    private class Loaded(val document: Document, val own: Boolean, val reason: ReadOnlyReason?, val outdated: Boolean = false)

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
            is DocumentRead.Outdated -> discarded(read.schema)
        }
    }

    // Nothing of an older shape is carried over: the interface it described was laid out for screens that are not
    // configs any more, and a migration that guessed would draw something nobody arranged.
    private fun discarded(schema: Int): Loaded {
        try {
            Files.deleteIfExists(file)
        } catch (e: Exception) {
            log.warn("The outdated interface in {} could not be deleted, the standard one is drawn and the file left as it is", file, e)
            return leftAsItIs(ReadOnlyReason.UnreadableFormat)
        }
        log.info("The interface in {} was of schema {}, which this build no longer reads: deleted, the standard one is drawn", file, schema)
        return Loaded(standard(), own = false, reason = null, outdated = true)
    }

    private fun leftAsItIs(reason: ReadOnlyReason): Loaded {
        NewerBuildData.record(ReadOnlyStore.Layout, reason)
        return Loaded(standard(), own = true, reason = reason)
    }

    companion object {
        /**
         * The document a store over [file] would draw, read with nothing written, recorded or logged: for what has to
         * know it before the store exists, the modules the boot window asks before the shell is up.
         */
        fun peek(file: Path, standard: () -> Document): Document {
            if (!Files.isRegularFile(file)) return standard()
            val text = runCatching { readUtf8(file) }.getOrNull() ?: return standard()
            return (DocumentCodec.read(text) as? DocumentRead.Whole)?.document ?: standard()
        }
    }
}

package hivens.ui.screens.library.content

import java.util.concurrent.ConcurrentHashMap

/**
 * Icons already resolved for installed files, kept for the life of the process.
 *
 * The tab's own map dies with its composition, so leaving a pack and coming back
 * built a fresh one and every row started again from a tinted square. That is not
 * a flicker: resolving a row means HASHING the jar, because the catalogue is
 * asked which project owns a file by its sha1, and a folder of a hundred and
 * fifty mods is a hundred and fifty full file reads. The placeholder sat there
 * for as long as that took, every single visit.
 *
 * Keyed by the file rather than by the row, and by its size as well as its path:
 * a jar replaced by an update is a different file at the same address, and the
 * answer for the old one must not be handed to the new.
 *
 * Deliberately NOT the [hivens.core.smrt.ModIconResolver]'s own cache. That one
 * is keyed by hash and answers "what does this hash look like"; this one answers
 * "what does this FILE look like", which is the question that lets the hash go
 * unasked.
 */
internal class ContentIconStore {
    private val icons = ConcurrentHashMap<String, ContentIconState>()

    fun get(key: String): ContentIconState? = icons[key]

    fun put(key: String, state: ContentIconState) {
        icons[key] = state
    }

    /**
     * Forgets one file's answer, for a row whose icon should be looked for again.
     *
     * Not a general clear: everything in here is keyed to a file that either
     * still exists or has been replaced under a key carrying its new size, so
     * nothing goes stale on its own.
     */
    fun forget(key: String) {
        icons.remove(key)
    }
}

/**
 * The key a file's icon is filed under.
 *
 * The instance is in it because two packs can hold the same jar at the same
 * relative path, and the size is in it because an update writes a different file
 * over the same name.
 */
internal fun iconKey(instanceDir: String, selectionKey: String, sizeBytes: Long): String =
    "$instanceDir|$selectionKey|$sizeBytes"

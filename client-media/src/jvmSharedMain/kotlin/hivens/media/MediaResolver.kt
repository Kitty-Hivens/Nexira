package hivens.media

import kotlinx.coroutines.flow.StateFlow
import java.nio.file.Path

/**
 * Turns a remote media URL into a local file the player can open, and says what
 * it is doing while it does. Skinema plays local files only, so this step is
 * unavoidable and can run for minutes -- which is why reporting and cancelling
 * are part of the contract rather than something a viewer adds on top.
 *
 * Both implementations run the work in an application-lifetime scope, not the
 * caller's, so a viewer leaving does not abandon a download the next visit will
 * want. [cancel] is how the work actually stops.
 */
interface MediaResolver {

    /** The local file for [url], fetching it first if absent. Throws on failure. */
    suspend fun resolve(url: String): Path

    /** What the fetch for [url] is doing; [MediaFetch.Idle] when nothing is. */
    fun fetchState(url: String): StateFlow<MediaFetch>

    /** Stops the fetch for [url]. An awaiting [resolve] sees a cancellation. */
    fun cancel(url: String)
}

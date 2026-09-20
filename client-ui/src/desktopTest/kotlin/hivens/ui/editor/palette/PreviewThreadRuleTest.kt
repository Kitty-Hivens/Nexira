package hivens.ui.editor.palette

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An off-screen scene in a shipping build is composed on the thread the window
 * is composed on, and there is no second thread to move it to.
 *
 * Compose's snapshot apply-observers are process wide and run on whichever
 * thread advanced the snapshot, not on the thread that owns the scene. A preview
 * scene composed on a worker therefore reaches into the live window's own
 * observer from that worker. Nothing fails at that moment. The next pointer
 * event on the interface thread finds the observer carrying another thread's id
 * and the window dies:
 *
 *     IllegalArgumentException: Detected multithreaded access to
 *     SnapshotStateObserver: previousThreadId=174, currentThread=AWT-EventQueue-0
 *
 * The gallery was moved onto a dedicated thread to stop it freezing the editor
 * and it bought a crash on the next drag. The freeze was a separate problem with
 * a separate answer, which is [ScenePool]: most of what a preview cost was
 * building the scene, not composing the widget.
 *
 * Structural rather than a comment, because the offending code reads perfectly
 * well on its own. What is wrong with `withContext(painter)` is nowhere near it.
 */
class PreviewThreadRuleTest {

    private val sourceRoot = File("src/desktopMain/kotlin")

    /**
     * Anything that can put composition on another thread.
     *
     * Not a search for the exact call that was wrong, because the next one will
     * be spelled differently. A shipping file that builds a scene has no honest
     * use for any of these.
     */
    private val hops = listOf(
        "newSingleThreadContext",
        "newFixedThreadPoolContext",
        "Dispatchers.IO",
        "Dispatchers.Default",
        "Executors.",
        "kotlin.concurrent.thread",
        "Thread(",
        "withContext(",
    )

    private fun scenesInShippingCode(): List<Pair<String, String>> =
        sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(sourceRoot).invariantSeparatorsPath to it.readText() }
            .filter { (_, text) -> "ImageComposeScene(" in text }
            .toList()

    @Test
    fun `the sources are where this test reads them`() {
        assertTrue(sourceRoot.isDirectory, "expected the desktop sources at ${sourceRoot.absolutePath}")
        assertTrue(
            scenesInShippingCode().isNotEmpty(),
            "nothing builds an off-screen scene any more, so either the gallery changed shape " +
                "or this test is looking in the wrong place and guards nothing",
        )
    }

    @Test
    fun `nothing that builds an off-screen scene moves the work off the composition thread`() {
        val offenders = scenesInShippingCode().flatMap { (path, text) ->
            hops.filter { hop -> hop in text }.map { hop -> "$path uses $hop" }
        }

        assertEquals(
            emptyList(),
            offenders,
            "an off-screen scene composed away from the window's thread corrupts the window's " +
                "SnapshotStateObserver and kills it on the next pointer event: keep the render on " +
                "the caller's thread and take the cost out of it instead",
        )
    }
}

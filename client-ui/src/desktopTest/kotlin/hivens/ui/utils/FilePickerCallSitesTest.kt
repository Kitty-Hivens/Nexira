package hivens.ui.utils

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the single entry point into the native file dialogs.
 *
 * A picker opened straight off FileKit gets no owner window, and an ownerless
 * dialog on Windows can open behind a maximized launcher -- a button that looks
 * dead, with nothing in the log to say the dialog ever ran. The failure is
 * invisible in review because the call reads perfectly fine on its own; what is
 * wrong is the settings it does not pass. So the rule is structural: every call
 * site goes through [hivens.ui.utils.pickFile] / [hivens.ui.utils.pickFiles],
 * which supply the owner and turn a failed dialog into a logged null.
 */
class FilePickerCallSitesTest {

    private val sourceRoots = listOf("desktopMain", "jvmSharedMain", "androidMain").map { File("src/$it/kotlin") }
    private val sharedRoot = File("src/jvmSharedMain/kotlin")
    private val helper = "hivens/ui/utils/FilePickers.kt"

    /** The helper is one declaration with a platform implementation per host, so the sanctioned sites are all three files. */
    private val sanctioned = setOf(
        helper,
        "hivens/ui/utils/FilePickers.desktop.kt",
        "hivens/ui/utils/FilePickers.android.kt",
    )

    /** Paths are compared with `/` so the helper is excluded on Windows too, where [File.path] separates with `\`. */
    private fun sourcesOutsideHelper(): Sequence<Pair<String, File>> =
        sourceRoots.asSequence().filter { it.isDirectory }.flatMap { root ->
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .map { it.relativeTo(root).invariantSeparatorsPath to it }
        }.filterNot { (path, _) -> path in sanctioned }

    @Test
    fun `the picker helper is where the sources live`() {
        assertTrue(
            sharedRoot.isDirectory,
            "expected the shared sources at ${sharedRoot.absolutePath} -- this test reads them as text",
        )
        assertTrue(File(sharedRoot, helper).isFile, "$helper is the only sanctioned FileKit call site")
    }

    @Test
    fun `no screen opens a file picker without the helper`() {
        val offenders = sourcesOutsideHelper()
            .filter { (_, file) -> "FileKit.openFilePicker" in file.readText() }
            .map { (path, _) -> path }
            .toList()

        assertEquals(
            emptyList(),
            offenders,
            "these call FileKit.openFilePicker directly, so their dialog has no owner window " +
                "and no failure log: use pickFile / pickFiles from hivens.ui.utils instead",
        )
    }

    @Test
    fun `dialog settings are not hand-built outside the helper`() {
        val offenders = sourcesOutsideHelper()
            .filter { (_, file) -> "FileKitDialogSettings(" in file.readText() }
            .map { (path, _) -> path }
            .toList()

        assertEquals(
            emptyList(),
            offenders,
            "a hand-built FileKitDialogSettings leaves parentWindow null: build it with " +
                "rememberFileDialogSettings so the dialog is owned by the launcher window",
        )
    }
}

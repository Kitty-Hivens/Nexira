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

    private val sourceRoot = File("src/desktopMain/kotlin")
    private val helper = "hivens/ui/utils/FilePickers.kt"

    @Test
    fun `the picker helper is where the sources live`() {
        assertTrue(
            sourceRoot.isDirectory,
            "expected the desktop sources at ${sourceRoot.absolutePath} -- this test reads them as text",
        )
        assertTrue(File(sourceRoot, helper).isFile, "$helper is the only sanctioned FileKit call site")
    }

    @Test
    fun `no screen opens a file picker without the helper`() {
        val offenders = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.path.endsWith(helper) }
            .filter { "FileKit.openFilePicker" in it.readText() }
            .map { it.relativeTo(sourceRoot).path }
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
        val offenders = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.path.endsWith(helper) }
            .filter { "FileKitDialogSettings(" in it.readText() }
            .map { it.relativeTo(sourceRoot).path }
            .toList()

        assertEquals(
            emptyList(),
            offenders,
            "a hand-built FileKitDialogSettings leaves parentWindow null: build it with " +
                "rememberFileDialogSettings so the dialog is owned by the launcher window",
        )
    }
}

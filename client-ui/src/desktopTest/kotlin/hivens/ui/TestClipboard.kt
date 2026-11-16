package hivens.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.Clipboard as AwtClipboard

/**
 * A clipboard of the test's own, in place of the system's: what a field copies lands here and
 * nowhere else, and a paste reads only what the test put here.
 */
@OptIn(ExperimentalComposeUiApi::class)
internal class TestClipboard : Clipboard {
    private val held = AwtClipboard("test")

    /** The text on the clipboard, or null when it holds none. */
    var text: String?
        get() = held.getContents(null)
            ?.takeIf { it.isDataFlavorSupported(DataFlavor.stringFlavor) }
            ?.getTransferData(DataFlavor.stringFlavor) as String?
        set(value) = held.setContents(value?.let(::StringSelection), null)

    override suspend fun getClipEntry(): ClipEntry? = held.getContents(null)?.let(::ClipEntry)

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        held.setContents(clipEntry?.nativeClipEntry as Transferable?, null)
    }
}

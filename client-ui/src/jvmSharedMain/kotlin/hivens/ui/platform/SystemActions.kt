package hivens.ui.platform

import java.io.File

/**
 * Hands a folder, a file or a link to the host, and puts text on its clipboard.
 *
 * Fire-and-forget: failures are silent, since there is no surface that could say
 * anything more useful than the resource not opening.
 */
internal object SystemActions {
    /** Open a directory in the host's file manager, creating it first when it is missing. */
    fun openFolder(path: String) = hostOpenFolder(path)

    /** Open a specific file through the host's associated handler. */
    fun openFile(file: File) = hostOpenFile(file)

    /** Open a URL in the user's browser. */
    fun openUrl(url: String) = hostOpenUrl(url)

    /** Copy [text] to the system clipboard. */
    fun copyToClipboard(text: String) = hostCopyToClipboard(text)
}

internal expect fun hostOpenFolder(path: String)

internal expect fun hostOpenFile(file: File)

internal expect fun hostOpenUrl(url: String)

internal expect fun hostCopyToClipboard(text: String)

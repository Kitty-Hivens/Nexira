package hivens.ui.utils

import androidx.compose.runtime.Composable
import hivens.ui.host.HostWindow
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.openDirectoryPicker
import io.github.vinceglb.filekit.dialogs.openFilePicker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

// One way into the native file dialogs. Every picker in the launcher goes
// through here so the owner window and the failure handling are decided once
// instead of per call site.

private val log = LoggerFactory.getLogger("FilePicker")

/**
 * What a picker is opened with: FileKit's own settings, which name the owner
 * window, and the window whose composition opened it, which the host takes the
 * dialog back through when that composition goes (see [HostWindow.holdDialog]).
 */
class FileDialogSettings internal constructor(
    internal val fileKit: FileKitDialogSettings,
    internal val window: HostWindow?,
)

/**
 * Dialog settings for this host: the desktop names the launcher window as the native
 * dialog's owner, a phone has no owner window to name.
 */
@Composable
expect fun rememberFileDialogSettings(title: String? = null): FileDialogSettings

/** One file, or null when the user cancelled or the dialog could not run. */
suspend fun pickFile(
    type: FileKitType,
    settings: FileDialogSettings,
    directory: PlatformFile? = null,
): PlatformFile? = guarded {
    settings.held { FileKit.openFilePicker(type = type, directory = directory, dialogSettings = settings.fileKit) }
}

/** Several files, or null when the user cancelled or the dialog could not run. */
suspend fun pickFiles(
    type: FileKitType,
    settings: FileDialogSettings,
    directory: PlatformFile? = null,
): List<PlatformFile>? = guarded {
    settings.held {
        FileKit.openFilePicker(
            type           = type,
            mode           = FileKitMode.Multiple(),
            directory      = directory,
            dialogSettings = settings.fileKit,
        )
    }
}

/**
 * A directory, or null when the user cancelled or the window that opened it went.
 * Unlike [pickFile] a failure to open is thrown, for a caller that tells the person
 * what went wrong.
 */
suspend fun pickDirectory(settings: FileDialogSettings, directory: PlatformFile? = null): PlatformFile? =
    settings.held { FileKit.openDirectoryPicker(directory = directory, dialogSettings = settings.fileKit) }

/**
 * Runs [open] under the host's hold, so the dialog goes with the composition that
 * opened it, and reads a dialog taken back that way as a cancel.
 *
 * FileKit offers no call that closes a dialog it opened, so taking one back is a
 * cancellation of the wait, and what that does depends on the path FileKit took
 * (read from FileKit 0.17's classes):
 * - XDG portal: the request runs on a D-Bus connection of its own, closed when the
 *   wait is cancelled. Whether the portal then closes its dialog is the portal's
 *   business and has not been checked.
 * - Windows: IFileDialog.Show blocks inside a withContext on FileKit's one STA
 *   thread, so the wait ends only once the person answers, and a dialog asked for
 *   meanwhile queues behind it on that thread.
 * - AWT fallback: the wait returns at once and the FileDialog is disposed.
 * - macOS: not looked at.
 * A dialog taken back answers null.
 */
private suspend fun <T> FileDialogSettings.held(open: suspend () -> T?): T? {
    val host = window ?: return open()
    val revoked = AtomicBoolean(false)
    return try {
        coroutineScope {
            val job = coroutineContext.job
            host.holdDialog {
                revoked.set(true)
                job.cancel(CancellationException("the window that opened the dialog is gone"))
            }.use { open() }
        }
    } catch (e: CancellationException) {
        // The caller's own cancellation goes on up, even when the host took the
        // dialog back in the same moment.
        if (!revoked.get() || !currentCoroutineContext().isActive) throw e
        log.info("File picker taken back: the window that opened it is gone")
        null
    }
}

/**
 * Runs a picker and turns a failed dialog into a logged null.
 *
 * The callers all live in a `rememberCoroutineScope`, where a throw leaves the
 * composition's scope unhandled -- and a picker has several ways to throw that
 * are invisible from the UI: a COM apartment that refuses to initialise, a
 * portal that is not running, an unresolved file, or a runtime image built
 * without the module the platform backend dlopens. [LinkageError] is caught
 * alongside the exceptions because that last one arrives as NoClassDefFound.
 *
 * The log line is the point as much as the recovery is: a picker that opens and
 * is cancelled and a picker that never opened look identical to the user, and
 * until now neither left anything in a diagnostic bundle.
 */
private suspend fun <T> guarded(open: suspend () -> T?): T? =
    try {
        val picked = open()
        log.info("File picker closed: {}", if (picked == null) "cancelled" else "selected")
        picked
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.error("File picker failed to open", e)
        null
    } catch (e: LinkageError) {
        log.error("File picker failed to open", e)
        null
    }

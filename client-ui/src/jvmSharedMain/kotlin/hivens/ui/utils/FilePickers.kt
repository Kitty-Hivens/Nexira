package hivens.ui.utils

import androidx.compose.runtime.Composable
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.openFilePicker
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

// One way into the native file dialogs. Every picker in the launcher goes
// through here so the owner window and the failure handling are decided once
// instead of per call site.

private val log = LoggerFactory.getLogger("FilePicker")

/**
 * Dialog settings for this host: the desktop names the launcher window as the native
 * dialog's owner, a phone has no owner window to name.
 */
@Composable
expect fun rememberFileDialogSettings(title: String? = null): FileKitDialogSettings

/** One file, or null when the user cancelled or the dialog could not run. */
suspend fun pickFile(
    type: FileKitType,
    settings: FileKitDialogSettings,
    directory: PlatformFile? = null,
): PlatformFile? = guarded {
    FileKit.openFilePicker(type = type, directory = directory, dialogSettings = settings)
}

/** Several files, or null when the user cancelled or the dialog could not run. */
suspend fun pickFiles(
    type: FileKitType,
    settings: FileKitDialogSettings,
    directory: PlatformFile? = null,
): List<PlatformFile>? = guarded {
    FileKit.openFilePicker(
        type           = type,
        mode           = FileKitMode.Multiple(),
        directory      = directory,
        dialogSettings = settings,
    )
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

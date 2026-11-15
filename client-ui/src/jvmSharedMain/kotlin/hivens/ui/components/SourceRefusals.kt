package hivens.ui.components

import hivens.i18n.api.Strings
import hivens.launcher.PackOperationPhase
import hivens.launcher.sources.SourceOperation

/**
 * What an operation the pack's source refused says, in the person's language: that the
 * source is not loaded, or which of its operations it cannot do. Never the engine's own
 * message, which is English and meant for the log.
 */
internal fun PackOperationPhase.SourceRefused.text(s: Strings): String = when (operation) {
    null -> s.text("packSourceRefusedMissing", origin)
    SourceOperation.Install -> s.text("packSourceRefusedInstall", origin)
    SourceOperation.Update -> s.text("packSourceRefusedUpdate", origin)
    SourceOperation.Reattach -> s.text("packSourceRefusedReattach", origin)
    SourceOperation.Repair -> s.text("packSourceRefusedRepair", origin)
}

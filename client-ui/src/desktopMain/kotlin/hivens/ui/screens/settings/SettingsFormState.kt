package hivens.ui.screens.settings

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import hivens.core.data.ReleaseChannel
import hivens.core.data.SettingsData

/**
 * Mutable form-state holder for the editable surface of the Settings
 * screen. One field per editable setting; each field is its own
 * `mutableStateOf` so Compose recomposition stays granular (flipping a
 * single toggle only invalidates the rows that read that one field).
 * The class is a thin namespace -- it does not centralize behavior, only
 * the ten or so `var x by mutableStateOf(initial.x)` declarations that
 * otherwise sit inline in the composable.
 *
 * Internal so the section composables in this package can read / write
 * fields directly without passing each one as a callback.
 *
 * [mimicOverrideEnabled] / [mimicVersionText] are UI-only state, not
 * 1:1-mapped to [SettingsData]:
 *   - `mimicOverrideEnabled` is derived from whether the persisted
 *     override is non-blank at composition time, then maintained
 *     independently so a user can toggle off, edit the text, toggle on
 *     without losing what they typed.
 *   - `mimicVersionText` is the live edit value; [mergeInto] normalises
 *     it (trim + blank-to-null) before writing to [SettingsData].
 */
@Stable
internal class SettingsFormState(initial: SettingsData) {
    var closeAfterStart        by mutableStateOf(initial.closeAfterStart)
    var useCustomChrome        by mutableStateOf(initial.useCustomChrome)
    var isOfflineMode          by mutableStateOf(initial.isOfflineMode)
    var mandatoryUpdates       by mutableStateOf(initial.mandatoryUpdatesEnabled)
    var autoSyncAllPacks       by mutableStateOf(initial.autoSyncAllPacks)
    var autoUpdatePacks        by mutableStateOf(initial.autoUpdatePacks)
    var amberUpdatePolicy      by mutableStateOf(initial.amberUpdatePolicy)
    var jvmBuilderEnabled      by mutableStateOf(initial.jvmBuilderEnabled)
    var adaptiveMemoryEnabled  by mutableStateOf(initial.adaptiveMemoryEnabled)
    var mimicOverrideEnabled   by mutableStateOf(!initial.mimicVersionOverride.isNullOrBlank())
    var mimicVersionText       by mutableStateOf(initial.mimicVersionOverride ?: "")
    var useOpenSmrtHelper      by mutableStateOf(initial.useOpenSmrtHelper)
    var strictModVerification  by mutableStateOf(initial.strictModVerification)
    var useNetworkAgent        by mutableStateOf(initial.useNetworkAgent)
    var useSmartycraftAuthLib  by mutableStateOf(initial.useSmartycraftAuthLib)
    // Simple pre-releases toggle: ON maps updateChannel to Beta (previews + betas),
    // OFF to Release. The old 5-channel picker is gone; nightly is a separate config
    // flag (SettingsData.nightlyChannel), never surfaced here.
    var preReleasesEnabled     by mutableStateOf(initial.updateChannel != ReleaseChannel.Release)

    /**
     * Build a [SettingsData] suitable for persistence by overlaying this
     * form's editable fields onto [current] (a freshly-read snapshot, so
     * non-screen fields like server-specific knobs are not clobbered).
     *
     * The mimic-version override is normalized here: empty toggle OR
     * blank text both collapse to null, which is the contract
     * [SettingsData.mimicVersionOverride] expects for "use the shipped
     * default" semantics. Storing a stale non-null value with the toggle
     * off would silently re-arm on next launch via the SettingsRestoreHook.
     */
    fun mergeInto(current: SettingsData): SettingsData {
        val normalisedMimic = if (mimicOverrideEnabled) mimicVersionText.trim().ifBlank { null } else null
        return current.copy(
            closeAfterStart             = closeAfterStart,
            useCustomChrome             = useCustomChrome,
            isOfflineMode               = isOfflineMode,
            mandatoryUpdatesEnabled     = mandatoryUpdates,
            autoSyncAllPacks            = autoSyncAllPacks,
            autoUpdatePacks             = autoUpdatePacks,
            amberUpdatePolicy           = amberUpdatePolicy,
            jvmBuilderEnabled           = jvmBuilderEnabled,
            adaptiveMemoryEnabled       = adaptiveMemoryEnabled,
            mimicVersionOverride        = normalisedMimic,
            useOpenSmrtHelper           = useOpenSmrtHelper,
            strictModVerification       = strictModVerification,
            useNetworkAgent             = useNetworkAgent,
            useSmartycraftAuthLib       = useSmartycraftAuthLib,
            updateChannel               = if (preReleasesEnabled) ReleaseChannel.Beta else ReleaseChannel.Release,
        )
    }
}

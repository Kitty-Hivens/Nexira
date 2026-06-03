package hivens.ui.screens.settings

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
    var isOfflineMode          by mutableStateOf(initial.isOfflineMode)
    var experimentalEnabled    by mutableStateOf(initial.experimentalFeaturesEnabled)
    var mandatoryUpdates       by mutableStateOf(initial.mandatoryUpdatesEnabled)
    var prereleaseChannel      by mutableStateOf(initial.prereleaseChannelEnabled)
    var autoSyncAllPacks       by mutableStateOf(initial.autoSyncAllPacks)
    var jvmBuilderEnabled      by mutableStateOf(initial.jvmBuilderEnabled)
    var adaptiveMemoryEnabled  by mutableStateOf(initial.adaptiveMemoryEnabled)
    var forceProxyMode         by mutableStateOf(initial.forceProxyMode)
    var mimicOverrideEnabled   by mutableStateOf(!initial.mimicVersionOverride.isNullOrBlank())
    var mimicVersionText       by mutableStateOf(initial.mimicVersionOverride ?: "")
    var useOpenSmrtHelper      by mutableStateOf(initial.useOpenSmrtHelper)
    var strictModVerification  by mutableStateOf(initial.strictModVerification)

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
            isOfflineMode               = isOfflineMode,
            experimentalFeaturesEnabled = experimentalEnabled,
            mandatoryUpdatesEnabled     = mandatoryUpdates,
            prereleaseChannelEnabled    = prereleaseChannel,
            autoSyncAllPacks            = autoSyncAllPacks,
            jvmBuilderEnabled           = jvmBuilderEnabled,
            adaptiveMemoryEnabled       = adaptiveMemoryEnabled,
            forceProxyMode              = forceProxyMode,
            mimicVersionOverride        = normalisedMimic,
            useOpenSmrtHelper           = useOpenSmrtHelper,
            strictModVerification       = strictModVerification,
        )
    }
}

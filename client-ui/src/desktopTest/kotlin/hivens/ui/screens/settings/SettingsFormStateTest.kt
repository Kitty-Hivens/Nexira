package hivens.ui.screens.settings

import hivens.core.data.SettingsData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsFormStateTest {

    @Test
    fun `Smarty toggles seed from settings and round-trip through mergeInto`() {
        val form = SettingsFormState(
            SettingsData(useOpenSmrtHelper = false, strictModVerification = true),
        )
        assertFalse(form.useOpenSmrtHelper, "seeded from SettingsData")
        assertTrue(form.strictModVerification)

        form.useOpenSmrtHelper = true
        form.strictModVerification = false

        val merged = form.mergeInto(SettingsData())
        assertTrue(merged.useOpenSmrtHelper)
        assertFalse(merged.strictModVerification)
    }

    @Test
    fun `defaults are both on`() {
        val form = SettingsFormState(SettingsData())
        assertTrue(form.useOpenSmrtHelper)
        assertTrue(form.strictModVerification)
    }

    @Test
    fun `auth-mechanism toggles seed and round-trip with their distinct defaults`() {
        // Network agent defaults ON, SC authlib swap defaults OFF.
        val form = SettingsFormState(SettingsData())
        assertTrue(form.useNetworkAgent, "network agent seeds ON")
        assertFalse(form.useSmartycraftAuthLib, "SC authlib swap seeds OFF")

        form.useNetworkAgent = false
        form.useSmartycraftAuthLib = true

        val merged = form.mergeInto(SettingsData())
        assertFalse(merged.useNetworkAgent)
        assertTrue(merged.useSmartycraftAuthLib)
    }
}

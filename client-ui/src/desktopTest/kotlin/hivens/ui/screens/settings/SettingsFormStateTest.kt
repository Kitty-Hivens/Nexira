package hivens.ui.screens.settings

import hivens.core.data.AmberUpdatePolicy
import hivens.core.data.SettingsData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsFormStateTest {

    @Test
    fun `amber policy seeds from settings and round-trips through mergeInto`() {
        val form = SettingsFormState(SettingsData())
        assertEquals(AmberUpdatePolicy.Ask, form.amberUpdatePolicy, "the cautious default seeds")

        form.amberUpdatePolicy = AmberUpdatePolicy.Hold
        assertEquals(AmberUpdatePolicy.Hold, form.mergeInto(SettingsData()).amberUpdatePolicy)

        form.amberUpdatePolicy = AmberUpdatePolicy.SnapshotThenApply
        assertEquals(
            AmberUpdatePolicy.SnapshotThenApply,
            form.mergeInto(SettingsData(amberUpdatePolicy = AmberUpdatePolicy.Hold)).amberUpdatePolicy,
        )
    }

    @Test
    fun `custom chrome seeds from settings and round-trips through mergeInto`() {
        val form = SettingsFormState(SettingsData())
        assertTrue(form.useCustomChrome, "custom chrome seeds ON")

        form.useCustomChrome = false
        assertFalse(form.mergeInto(SettingsData()).useCustomChrome)

        form.useCustomChrome = true
        assertTrue(form.mergeInto(SettingsData(useCustomChrome = false)).useCustomChrome)
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

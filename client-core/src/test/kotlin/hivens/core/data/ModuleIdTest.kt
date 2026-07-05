package hivens.core.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ModuleIdTest {

    // The ids are persisted in settings.json (SettingsData.disabledModules), so a
    // rename here would orphan a user's already-disabled module. Pin them.
    @Test
    fun `ids are the stable persisted strings`() {
        assertEquals("tray", ModuleId.Tray.id)
        assertEquals("notify", ModuleId.Notify.id)
        assertEquals("skinema", ModuleId.Skinema.id)
        assertEquals("keyring", ModuleId.Keyring.id)
    }

    @Test
    fun `fromId maps known ids and ignores unknown`() {
        assertEquals(ModuleId.Skinema, ModuleId.fromId("skinema"))
        assertNull(ModuleId.fromId("nope"))
        assertNull(ModuleId.fromId(""))
    }
}

package hivens.core.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The face choice is only reachable while two accounts are signed in, so a
 * choice left behind by an account that is gone is a setting the user can
 * neither see nor change -- and it decides the shell's face again the moment
 * that provider comes back. Signing out releases it.
 */
class PreferredFaceTest {

    private val sc = PackAuthRequirement.SmartyCraft.PROVIDER_KEY
    private val ms = PackAuthRequirement.Microsoft.PROVIDER_KEY

    @Test
    fun `signing out of the chosen provider releases the choice`() {
        val settings = SettingsData(preferredFaceProvider = sc)
        assertNull(settings.releasingFace(sc).preferredFaceProvider)
    }

    @Test
    fun `signing out of another provider leaves the choice alone`() {
        val settings = SettingsData(preferredFaceProvider = sc)
        assertEquals(sc, settings.releasingFace(ms).preferredFaceProvider)
    }

    @Test
    fun `automatic stays automatic`() {
        assertNull(SettingsData().releasingFace(sc).preferredFaceProvider)
    }

    @Test
    fun `nothing else in the settings moves`() {
        val settings = SettingsData(preferredFaceProvider = sc, memoryMB = 4096, disabledModules = setOf("tray"))
        val released = settings.releasingFace(sc)
        assertEquals(settings.copy(preferredFaceProvider = null), released)
    }
}

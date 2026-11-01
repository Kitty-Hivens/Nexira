package hivens.auth

import hivens.core.data.OfflineIdentity
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OfflineAuthProviderTest {

    private val provider = OfflineAuthProvider()

    @Test
    fun `login mints an offline session with the vanilla UUID and a blank token`() = runTest {
        val session = provider.login("Steve", "ignored", "ignored")
        assertEquals("Steve", session.playerName)
        assertEquals(OfflineIdentity.dashlessUuidFor("Steve"), session.uuid)
        assertTrue(session.offline)
        assertEquals("", session.accessToken)
    }

    @Test
    fun `login trims the name`() = runTest {
        assertEquals("Steve", provider.login("  Steve  ", "", "").playerName)
    }

    @Test
    fun `login rejects a blank name`() = runTest {
        assertFailsWith<IllegalArgumentException> { provider.login("   ", "", "") }
    }

    @Test
    fun `completeTwoFactor is unsupported`() = runTest {
        assertFailsWith<UnsupportedOperationException> {
            provider.completeTwoFactor("Steve", "", "", "uid", "000000")
        }
    }

    @Test
    fun `capabilities report no second factor`() {
        assertEquals(false, provider.capabilities.supports2FA)
        assertEquals("offline", provider.id)
    }
}

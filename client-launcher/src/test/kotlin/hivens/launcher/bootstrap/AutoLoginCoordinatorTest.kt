package hivens.launcher.bootstrap

import hivens.auth.AuthProvider
import hivens.core.data.OfflineIdentity
import hivens.core.data.SessionData
import hivens.core.data.SettingsData
import hivens.launcher.network.ServerProtocolConfig
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutoLoginCoordinatorTest {

    // The offline branch returns before touching the network deps, so strict
    // mocks here also assert they are never called.
    private val authService: AuthProvider = mockk()
    private val insecure: AuthProvider = mockk()
    private val protocolConfig: ServerProtocolConfig = mockk(relaxed = true)

    private suspend fun resolve(settings: SettingsData, saved: SessionData?) =
        AutoLoginCoordinator.resolveSession(
            settings = settings,
            saved = saved,
            lastServerId = null,
            authService = authService,
            insecureAuthService = insecure,
            protocolConfig = protocolConfig,
        )

    @Test
    fun `offline mode uses the chosen offline name with a real offline UUID`() = runTest {
        val session = resolve(SettingsData(isOfflineMode = true, offlinePlayerName = "Steve"), saved = null)
        assertTrue(session != null && session.offline)
        assertEquals("Steve", session.playerName)
        assertEquals(OfflineIdentity.dashlessUuidFor("Steve"), session.uuid)
        assertEquals("", session.accessToken)
    }

    @Test
    fun `offline mode falls back to the last signed-in name`() = runTest {
        val session = resolve(
            SettingsData(isOfflineMode = true),
            saved = SessionData(playerName = "OldName", accessToken = "tok"),
        )
        assertTrue(session != null && session.offline)
        assertEquals("OldName", session.playerName)
        assertEquals(OfflineIdentity.dashlessUuidFor("OldName"), session.uuid)
    }

    @Test
    fun `offline mode with no chosen and no saved name returns null`() = runTest {
        assertNull(resolve(SettingsData(isOfflineMode = true), saved = null))
    }
}

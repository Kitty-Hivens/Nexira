package hivens.launcher.bootstrap

import hivens.auth.AuthProvider
import hivens.auth.microsoft.MsaAuthProvider
import hivens.core.data.OfflineIdentity
import hivens.core.data.SessionData
import hivens.core.data.SettingsData
import hivens.launcher.network.ServerProtocolConfig
import io.mockk.coEvery
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

    private suspend fun resolve(settings: SettingsData, saved: SessionData?, msa: MsaAuthProvider? = null) =
        AutoLoginCoordinator.resolveSession(
            settings = settings,
            saved = saved,
            lastServerId = null,
            authService = authService,
            insecureAuthService = insecure,
            protocolConfig = protocolConfig,
            msaProvider = msa,
        )

    // A Microsoft account is the only session shape carrying a refresh token.
    private val msSaved = SessionData(
        playerName = "MsUser",
        uuid = "0123456789abcdef0123456789abcdef",
        accessToken = "old-mc-token",
        refreshToken = "rt-old",
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

    @Test
    fun `active Microsoft account silent-refreshes to a fresh token`() = runTest {
        val msa: MsaAuthProvider = mockk()
        coEvery { msa.refresh("rt-old") } returns msSaved.copy(accessToken = "new-mc-token", refreshToken = "rt-new")

        val session = resolve(SettingsData(), saved = msSaved, msa = msa)

        assertEquals("new-mc-token", session?.accessToken)
        assertEquals("rt-new", session?.refreshToken)
    }

    @Test
    fun `Microsoft refresh failure falls back to the cached token`() = runTest {
        val msa: MsaAuthProvider = mockk()
        coEvery { msa.refresh(any()) } throws RuntimeException("network down")

        val session = resolve(SettingsData(), saved = msSaved, msa = msa)

        assertEquals("old-mc-token", session?.accessToken)
        assertEquals("rt-old", session?.refreshToken)
    }

    @Test
    fun `Microsoft account with no configured provider uses the cached token`() = runTest {
        val session = resolve(SettingsData(), saved = msSaved, msa = null)

        assertEquals("old-mc-token", session?.accessToken)
        assertEquals("rt-old", session?.refreshToken)
    }
}

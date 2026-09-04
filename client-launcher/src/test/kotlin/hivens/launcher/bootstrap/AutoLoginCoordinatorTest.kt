package hivens.launcher.bootstrap

import hivens.auth.AuthProvider
import hivens.auth.microsoft.MsaAuthProvider
import hivens.core.api.AuthException
import hivens.core.api.TwoFactorRequiredException
import hivens.core.data.AuthStatus
import hivens.core.data.OfflineIdentity
import hivens.core.data.SessionData
import hivens.core.data.SettingsData
import hivens.launcher.bootstrap.AutoLoginCoordinator.Resolution
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AutoLoginCoordinatorTest {

    // The offline branch returns before touching the network deps, so a strict
    // mock here also asserts it is never called.
    private val authService: AuthProvider = mockk()

    private suspend fun resolve(settings: SettingsData, saved: SessionData?, msa: MsaAuthProvider? = null) =
        AutoLoginCoordinator.resolveSession(
            settings = settings,
            saved = saved,
            lastServerId = null,
            authService = authService,
            msaProvider = msa,
        )

    private fun session(resolution: Resolution): SessionData {
        assertIs<Resolution.Success>(resolution)
        return resolution.session
    }

    // A Microsoft account is the only session shape carrying a refresh token.
    private val msSaved = SessionData(
        playerName = "MsUser",
        uuid = "0123456789abcdef0123456789abcdef",
        accessToken = "old-mc-token",
        refreshToken = "rt-old",
    )

    // An SC-shaped account: cached password, no refresh token.
    private val scSaved = SessionData(
        playerName = "ScUser",
        uuid = "fedcba9876543210fedcba9876543210",
        accessToken = "sc-token",
        cachedPassword = "hunter2",
    )

    @Test
    fun `offline mode uses the chosen offline name with a real offline UUID`() = runTest {
        val session = session(resolve(SettingsData(isOfflineMode = true, offlinePlayerName = "Steve"), saved = null))
        assertTrue(session.offline)
        assertEquals("Steve", session.playerName)
        assertEquals(OfflineIdentity.dashlessUuidFor("Steve"), session.uuid)
        assertEquals("", session.accessToken)
    }

    @Test
    fun `offline mode falls back to the last signed-in name`() = runTest {
        val session = session(
            resolve(
                SettingsData(isOfflineMode = true),
                saved = SessionData(playerName = "OldName", accessToken = "tok"),
            ),
        )
        assertTrue(session.offline)
        assertEquals("OldName", session.playerName)
        assertEquals(OfflineIdentity.dashlessUuidFor("OldName"), session.uuid)
    }

    @Test
    fun `offline mode with no chosen and no saved name has no credentials`() = runTest {
        assertIs<Resolution.NoCredentials>(resolve(SettingsData(isOfflineMode = true), saved = null))
    }

    @Test
    fun `no saved session has no credentials`() = runTest {
        assertIs<Resolution.NoCredentials>(resolve(SettingsData(), saved = null))
    }

    @Test
    fun `active Microsoft account silent-refreshes to a fresh token`() = runTest {
        val msa: MsaAuthProvider = mockk()
        coEvery { msa.refresh("rt-old") } returns msSaved.copy(accessToken = "new-mc-token", refreshToken = "rt-new")

        val session = session(resolve(SettingsData(), saved = msSaved, msa = msa))

        assertEquals("new-mc-token", session.accessToken)
        assertEquals("rt-new", session.refreshToken)
    }

    @Test
    fun `Microsoft refresh failure falls back to the cached token`() = runTest {
        val msa: MsaAuthProvider = mockk()
        coEvery { msa.refresh(any()) } throws RuntimeException("network down")

        val session = session(resolve(SettingsData(), saved = msSaved, msa = msa))

        assertEquals("old-mc-token", session.accessToken)
        assertEquals("rt-old", session.refreshToken)
    }

    @Test
    fun `Microsoft account with no configured provider uses the cached token`() = runTest {
        val session = session(resolve(SettingsData(), saved = msSaved, msa = null))

        assertEquals("old-mc-token", session.accessToken)
        assertEquals("rt-old", session.refreshToken)
    }

    // ── the two-factor guard ─────────────────────────────────────────────────

    @Test
    fun `an ordinary account is still signed in`() = runTest {
        coEvery { authService.login("ScUser", "hunter2", any()) } returns scSaved.copy(accessToken = "fresh-token")
        assertEquals("fresh-token", session(resolve(SettingsData(), saved = scSaved)).accessToken)
    }

    @Test
    fun `a marked two-factor account is never signed in again`() = runTest {
        // Not "the login is harmless because it fails": SmartyCraft mints a uid per
        // login and invalidates the previous one, so the REQUEST is what revokes the
        // session the player unlocked with a code. It must not be made at all.
        coEvery { authService.login(any(), any(), any()) } throws
            AssertionError("auto-login signed a two-factor account in")

        val session = session(resolve(SettingsData(), saved = scSaved.copy(twoFactor = true)))

        assertEquals("sc-token", session.accessToken)
        assertTrue(session.twoFactor)
        coVerify(exactly = 0) { authService.login(any(), any(), any()) }
    }

    @Test
    fun `a two-factor account with no saved password still opens on its token`() = runTest {
        // The guard sits ahead of the password read on purpose: the token is what
        // this branch goes with, and declining to save a password is not a reason to
        // drop the account to the login form on every start.
        coEvery { authService.login(any(), any(), any()) } throws
            AssertionError("auto-login signed a two-factor account in")

        val session = session(
            resolve(SettingsData(), saved = scSaved.copy(twoFactor = true, cachedPassword = null)),
        )

        assertEquals("sc-token", session.accessToken)
    }

    @Test
    fun `first contact with the gate comes back marked so the caller can arm it`() = runTest {
        // An account saved before the flag existed reaches the gate here, and the
        // demand is the only evidence there is. Unmarked, the next start spends
        // another login and kills another session.
        coEvery { authService.login(any(), any(), any()) } throws
            TwoFactorRequiredException(uid = "a921e0baf5d4c445", login = "ScUser")

        val session = session(resolve(SettingsData(), saved = scSaved))

        assertTrue(session.twoFactor, "the caller persists this; without it the guard stays unarmed")
        assertEquals("sc-token", session.accessToken)
    }

    // ── failure classification ───────────────────────────────────────────────

    @Test
    fun `a network-shaped auth failure resolves to NetworkDown`() = runTest {
        // The provider funnel wraps DNS/connect failures with isNetworkError
        // (the exact shape of the reported DNS-down startup).
        coEvery { authService.login(any(), any(), any()) } throws
            AuthException(AuthStatus.INTERNAL_ERROR, "Network Error: www.smartycraft.ru", isNetworkError = true)
        assertIs<Resolution.NetworkDown>(resolve(SettingsData(), saved = scSaved))
    }

    @Test
    fun `raw IO that escaped the funnel resolves to NetworkDown`() = runTest {
        coEvery { authService.login(any(), any(), any()) } throws
            java.net.UnknownHostException("www.smartycraft.ru")
        assertIs<Resolution.NetworkDown>(resolve(SettingsData(), saved = scSaved))
    }

    @Test
    fun `a credential rejection resolves to Rejected -- never retried`() = runTest {
        coEvery { authService.login(any(), any(), any()) } throws
            AuthException(AuthStatus.PASSWORD, "Invalid password")
        assertIs<Resolution.Rejected>(resolve(SettingsData(), saved = scSaved))
    }

    @Test
    fun `an unclassifiable failure resolves to Rejected`() = runTest {
        coEvery { authService.login(any(), any(), any()) } throws IllegalStateException("boom")
        assertIs<Resolution.Rejected>(resolve(SettingsData(), saved = scSaved))
    }

    @Test
    fun `a certificate error stops auto-login instead of granting itself a bypass`() = runTest {
        coEvery { authService.login(any(), any(), any()) } throws
            AuthException(AuthStatus.INTERNAL_ERROR, "certificate expired", isSslError = true)
        // The point of the resolution: it reports the refusal and stops there.
        // Turning certificate checking off is the user's decision, taken at the
        // login panel's prompt; having saved a password once is not that
        // decision, and the attacker presenting the bad certificate is the one
        // who profits from it. The coordinator is handed no bypass store at all,
        // so granting one is not something it can reach.
        assertIs<Resolution.CertificateUntrusted>(resolve(SettingsData(), saved = scSaved))
    }

    // ── backoff policy ───────────────────────────────────────────────────────

    @Test
    fun `retry delays climb the ladder and cap at five minutes`() {
        assertEquals(15_000, AutoLoginCoordinator.retryDelayMs(0))
        assertEquals(30_000, AutoLoginCoordinator.retryDelayMs(1))
        assertEquals(60_000, AutoLoginCoordinator.retryDelayMs(2))
        assertEquals(120_000, AutoLoginCoordinator.retryDelayMs(3))
        assertEquals(300_000, AutoLoginCoordinator.retryDelayMs(4))
        assertEquals(300_000, AutoLoginCoordinator.retryDelayMs(50))
    }
}

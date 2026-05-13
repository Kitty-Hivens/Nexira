package hivens.launcher.security

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Live probe of [WindowsCredentialManagerKeyringStorage] against the
 * real Windows Credential Manager service. Tagged `live-windows-keyring`
 * so it stays out of the regular test set and is opt-in for local
 * Windows runs.
 *
 * Run locally on Windows with:
 *   ./gradlew :client-launcher:liveWindowsKeyringTest
 *
 * Skips with [Assumptions.assumeTrue] when not running on Windows or
 * when advapi32 isn't loadable — never fails the build because we're
 * on the wrong platform.
 *
 * GH Actions `windows-latest` runners DO have a working Credential
 * Manager service (DPAPI works for the runner's user account headless),
 * so this could be wired into CI via the test matrix as a follow-up.
 */
@Tag("live-windows-keyring")
class LiveWindowsKeyringProbeTest {

    private val service = "io.github.kitty_hivens.AuraLauncher.test"
    private val account = "live-probe-${System.currentTimeMillis()}"

    private val keyring = WindowsCredentialManagerKeyringStorage()

    private fun assumeWindows() {
        assumeTrue(
            System.getProperty("os.name", "").lowercase().contains("windows"),
            "LiveWindowsKeyringProbeTest is Windows-only",
        )
        assumeTrue(
            keyring.isAvailable(),
            "advapi32 not loadable / Credential Manager unreachable — skipping live probe",
        )
    }

    @Test
    fun `round-trip — CredWriteW then CredReadW returns the same secret`() {
        assumeWindows()
        val secret = "round-trip-${System.nanoTime()}"
        try {
            assertTrue(keyring.store(service, account, secret), "store should succeed")
            assertEquals(secret, keyring.retrieve(service, account))
        } finally {
            keyring.clear(service, account)
        }
    }

    @Test
    fun `clear removes the entry — subsequent retrieve returns null`() {
        assumeWindows()
        val secret = "to-be-cleared-${System.nanoTime()}"
        keyring.store(service, account, secret)
        assertTrue(keyring.clear(service, account), "clear should succeed when entry exists")
        assertNull(keyring.retrieve(service, account), "after clear, retrieve must return null")
    }

    @Test
    fun `clear of nonexistent entry returns false (CredDeleteW semantics)`() {
        assumeWindows()
        // CredDeleteW returns FALSE with GetLastError = ERROR_NOT_FOUND when
        // nothing matches. Same shape as libsecret's clear-on-no-match.
        assertFalse(
            keyring.clear(service, "definitely-does-not-exist-${System.nanoTime()}"),
            "clear on absent entry returns false per CredDeleteW spec",
        )
    }

    @Test
    fun `retrieve of unknown account returns null without throwing`() {
        assumeWindows()
        assertNull(keyring.retrieve(service, "never-stored-${System.nanoTime()}"))
    }

    @Test
    fun `unicode secret round-trips through UTF-16LE encoding`() {
        assumeWindows()
        // Verifies the UTF-16LE marshalling we do for CredentialBlob —
        // CredWriteW takes raw bytes via the blob pointer, we encode as
        // UTF-16LE before the call. If the encoding were wrong (say UTF-8
        // or platform default), unicode characters would corrupt.
        val secret = "пароль-секрет-密码-🔑"
        try {
            assertTrue(keyring.store(service, account, secret))
            assertEquals(secret, keyring.retrieve(service, account))
        } finally {
            keyring.clear(service, account)
        }
    }
}

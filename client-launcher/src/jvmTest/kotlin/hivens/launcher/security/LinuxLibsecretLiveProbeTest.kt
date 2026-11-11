package hivens.launcher.security

import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Live probe of [LinuxLibsecretKeyringStorage] against the real
 * Secret Service daemon on the developer's machine. Tagged `live-keyring`
 * so it stays out of CI (no Secret Service in headless ubuntu-latest)
 * and is opt-in for local maintenance work.
 *
 * Run locally with:
 *   ./gradlew :client-launcher:liveKeyringTest
 *
 * Skips with [Assumptions.assumeTrue] when the daemon isn't reachable
 * or when not running on Linux -- never fails the build because of a
 * missing keyring service.
 */
@Tag("live-keyring")
class LinuxLibsecretLiveProbeTest {

    private val service = "io.github.kitty_hivens.AuraLauncher.test"
    private val account = "live-probe-${System.currentTimeMillis()}"

    private val keyring = LinuxLibsecretKeyringStorage()

    private fun assumeLinux() {
        assumeTrue(
            System.getProperty("os.name", "").lowercase().contains("linux"),
            "LinuxLibsecretLiveProbeTest is Linux-only",
        )
        assumeTrue(
            keyring.isAvailable(),
            "Secret Service daemon not reachable on this host -- skipping live probe",
        )
    }

    @Test
    fun `round-trip -- store then retrieve returns the same secret`() {
        assumeLinux()
        val secret = "round-trip-${System.nanoTime()}"
        try {
            assertTrue(keyring.store(service, account, secret), "store should succeed")
            val got = keyring.retrieve(service, account)
            assertEquals(secret, got, "retrieved secret must equal what was stored")
        } finally {
            keyring.clear(service, account)
        }
    }

    @Test
    fun `clear removes the entry -- subsequent retrieve returns null`() {
        assumeLinux()
        val secret = "to-be-cleared-${System.nanoTime()}"
        keyring.store(service, account, secret)
        assertTrue(keyring.clear(service, account), "clear should succeed when entry exists")
        assertNull(keyring.retrieve(service, account), "after clear, retrieve must return null")
    }

    @Test
    fun `clear of nonexistent entry returns false (libsecret semantics)`() {
        assumeLinux()
        // libsecret's secret_password_clear_sync returns TRUE only when
        // an entry was actually removed; FALSE otherwise (including the
        // common "nothing matched" case). Pin the contract -- this is
        // why isAvailable() probes via store-then-clear, not bare clear.
        assertFalse(
            keyring.clear(service, "definitely-does-not-exist-${System.nanoTime()}"),
            "clear on absent entry returns false per libsecret spec",
        )
    }

    @Test
    fun `retrieve of unknown account returns null without throwing`() {
        assumeLinux()
        assertNull(keyring.retrieve(service, "never-stored-${System.nanoTime()}"))
    }
}

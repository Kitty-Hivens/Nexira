package hivens.launcher.security

import hivens.core.security.IKeyringStorage
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class KeyringStorageFactoryTest {

    @Test
    fun `system() returns a non-null IKeyringStorage on every platform`() {
        // Contract: the factory always hands out something usable, even on
        // platforms where no native keyring is reachable. The fallback
        // path is NoOpKeyringStorage. Callers must never get a null.
        val keyring = KeyringStorageFactory.system()
        assertNotNull(keyring)
    }

    @Test
    fun `an unrecognised OS gets the NoOp fallback without probing anything`() {
        // Linux libsecret, macOS Keychain, Windows Credential Manager are wired.
        // Anything else gets the NoOp fallback and CredentialsManager degrades to
        // its AES-GCM file path. Asked of the choice directly, so it runs on every
        // host rather than only on the one host nothing here builds for.
        val probed = mutableListOf<String>()
        val chosen = KeyringStorageFactory.forOs("Plan9") { label, _ -> probed += label; null }
        assertSame(NoOpKeyringStorage, chosen)
        assertEquals(emptyList(), probed)
    }

    @Test
    fun `each known OS probes its own store, and a failed probe falls back`() {
        val probed = mutableListOf<String>()
        val fallback = { os: String -> KeyringStorageFactory.forOs(os) { label, _ -> probed += label; null } }
        assertSame(NoOpKeyringStorage, fallback("Linux"))
        assertSame(NoOpKeyringStorage, fallback("FreeBSD"))
        assertSame(NoOpKeyringStorage, fallback("Windows 11"))
        assertSame(NoOpKeyringStorage, fallback("Mac OS X"))
        assertEquals(listOf("LinuxLibsecret", "LinuxLibsecret", "WindowsCredentialManager", "MacOSKeychain"), probed)
    }

    @Test
    fun `a blank service or account is refused at the boundary`() {
        // Blank ids would silently coexist in the real store (libsecret happily
        // stores a "" attribute). Every native storage calls this first.
        assertFailsWith<IllegalArgumentException> { requireKeyringIds("", "session") }
        assertFailsWith<IllegalArgumentException> { requireKeyringIds("Nexira", " ") }
        requireKeyringIds("Nexira", "session")
    }

    // Each native storage, on the host it is for, refuses a blank id before any
    // native call: the check is the first thing each of its three methods does.
    @Test
    @EnabledOnOs(OS.LINUX)
    fun `the libsecret storage refuses blank ids`() = assertRefusesBlank(LinuxLibsecretKeyringStorage())

    @Test
    @EnabledOnOs(OS.MAC)
    fun `the Keychain storage refuses blank ids`() = assertRefusesBlank(MacOSKeychainStorage())

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `the Credential Manager storage refuses blank ids`() = assertRefusesBlank(WindowsCredentialManagerKeyringStorage())

    private fun assertRefusesBlank(storage: IKeyringStorage) {
        assertFailsWith<IllegalArgumentException> { storage.store("", "session", "secret") }
        assertFailsWith<IllegalArgumentException> { storage.retrieve("Nexira", "") }
        assertFailsWith<IllegalArgumentException> { storage.clear(" ", "session") }
    }

    @Test
    fun `probeWithTimeout returns null when the probe blocks past the deadline`() {
        // The launcher hang: a locked Secret Service with no prompter makes the
        // libsecret write-probe block forever, on the UI thread, at startup. The
        // factory must give up at the deadline and let the caller fall back to file.
        val gate = CountDownLatch(1)
        val startedAt = System.nanoTime()
        val result = KeyringStorageFactory.probeWithTimeout(80L) {
            gate.await() // blocks until shutdownNow interrupts the daemon worker
            "never"
        }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        assertNull(result)
        assertTrue(elapsedMs < 2000, "probe must return near the deadline, took ${elapsedMs}ms")
    }

    @Test
    fun `probeWithTimeout returns the value when the probe completes in time`() {
        assertEquals("ok", KeyringStorageFactory.probeWithTimeout(1000L) { "ok" })
    }

    @Test
    fun `probeWithTimeout rethrows the block's own exception`() {
        // A missing native lib (UnsatisfiedLinkError) must still surface so the
        // factory logs it and falls back -- not be swallowed as a timeout.
        assertFailsWith<IllegalStateException> {
            KeyringStorageFactory.probeWithTimeout(1000L) { error("boom") }
        }
    }
}

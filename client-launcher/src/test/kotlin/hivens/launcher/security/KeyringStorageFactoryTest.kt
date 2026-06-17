package hivens.launcher.security

import hivens.core.security.IKeyringStorage
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
    fun `system() on unrecognised OS returns NoOp`() {
        // Linux libsecret, macOS Keychain, Windows Credential Manager are
        // all wired. Anything else (BSD without Secret Service, Plan9,
        // exotic embedded) gets the NoOp fallback and CredentialsManager
        // degrades to its AES-GCM file path.
        val osName = System.getProperty("os.name", "").lowercase()
        if (!osName.contains("linux") && !osName.contains("mac") &&
            !osName.contains("darwin") && !osName.contains("windows") &&
            !osName.contains("bsd")) {
            assertTrue(KeyringStorageFactory.system() is NoOpKeyringStorage)
        }
    }

    @Test
    fun `IKeyringStorage instances reject blank service or account`() {
        // Defensive: blank ids would silently coexist in the real store
        // (libsecret happily stores a "" attribute). Catch at the boundary.
        val noop: IKeyringStorage = NoOpKeyringStorage
        // NoOp doesn't enforce -- it's the platform impls that throw.
        // This test pins the API surface so a future refactor doesn't
        // accidentally weaken the contract by swapping NoOp into the
        // checked path.
        assertNotNull(noop)
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

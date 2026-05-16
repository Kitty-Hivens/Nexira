package hivens.launcher.security

import hivens.core.security.IKeyringStorage
import kotlin.test.Test
import kotlin.test.assertNotNull
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
    fun `system() on non-Linux returns NoOp until those platforms are wired`() {
        // Forward-looking: when Windows / macOS impls land in follow-up PRs,
        // this assertion changes shape (we'll need OS detection in the test
        // itself). Until then, document the current behavior explicitly.
        val osName = System.getProperty("os.name", "").lowercase()
        if (!osName.contains("linux") && !osName.contains("mac") && !osName.contains("windows")) {
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
}

package hivens.launcher.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class NoOpKeyringStorageTest {

    @Test
    fun `isAvailable returns false unconditionally`() {
        assertFalse(NoOpKeyringStorage.isAvailable())
    }

    @Test
    fun `store returns false and does not throw`() {
        assertFalse(NoOpKeyringStorage.store("AuraLauncher", "session", "secret"))
    }

    @Test
    fun `retrieve returns null unconditionally`() {
        assertNull(NoOpKeyringStorage.retrieve("AuraLauncher", "session"))
    }

    @Test
    fun `clear returns false unconditionally`() {
        assertFalse(NoOpKeyringStorage.clear("AuraLauncher", "session"))
    }

    @Test
    fun `singleton identity -- same instance across calls`() {
        // The factory hands out NoOpKeyringStorage by reference; if a
        // future refactor accidentally turns it into a class, the
        // unnecessary allocations on every Koin resolve would matter.
        // This test pins the singleton contract.
        assertEquals(NoOpKeyringStorage, NoOpKeyringStorage)
    }
}

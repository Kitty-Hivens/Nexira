package hivens.launcher.security

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame

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
    fun `the fallback is one shared object, handed out by reference`() {
        // A Kotlin object compiles to a class holding its one instance in a static
        // INSTANCE field. Turned into a class, that field goes and this fails.
        val instance = NoOpKeyringStorage::class.java.getField("INSTANCE").get(null)
        assertSame(NoOpKeyringStorage, instance)
        assertSame(
            KeyringStorageFactory.forOs("Plan9") { _, _ -> null },
            KeyringStorageFactory.forOs("Plan9") { _, _ -> null },
        )
    }
}

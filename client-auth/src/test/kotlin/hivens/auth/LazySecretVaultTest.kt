package hivens.auth

import dev.hivens.libvault.SecretVault
import dev.hivens.libvault.VaultTier
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LazySecretVaultTest {

    private class FakeVault : SecretVault {
        var closed = false
        private val bag = mutableMapOf<String, ByteArray>()
        override val tier = VaultTier.Memory
        override val backend = "fake"
        override fun store(key: String, value: ByteArray): Boolean { bag[key] = value; return true }
        override fun retrieve(key: String): ByteArray? = bag[key]
        override fun delete(key: String): Boolean = bag.remove(key) != null
        override fun contains(key: String): Boolean = bag.containsKey(key)
        override fun close() { closed = true }
    }

    @Test
    fun `does not open at construction`() {
        val opens = AtomicInteger(0)
        LazySecretVault { opens.incrementAndGet(); FakeVault() }
        assertEquals(0, opens.get(), "opener must not run until first use")
    }

    @Test
    fun `opens once on first use and reuses`() {
        val opens = AtomicInteger(0)
        val v = LazySecretVault { opens.incrementAndGet(); FakeVault() }
        v.store("k", byteArrayOf(1))
        v.retrieve("k")
        assertEquals(1, opens.get(), "opener runs exactly once")
        assertEquals(VaultTier.Memory, v.tier)
    }

    @Test
    fun `close is a no-op when never opened`() {
        var opened = false
        val v = LazySecretVault { opened = true; FakeVault() }
        v.close()
        assertFalse(opened, "close must not force the open")
    }

    @Test
    fun `close closes the real vault once opened`() {
        val fake = FakeVault()
        val v = LazySecretVault { fake }
        v.contains("x") // triggers the open
        v.close()
        assertTrue(fake.closed)
    }
}

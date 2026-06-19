package hivens.core.data

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class OfflineIdentityTest {

    @Test
    fun `uuidFor matches the vanilla OfflinePlayer scheme`() {
        // Independent recomputation of the spec expression -- catches a regression
        // in the helper's prefix or charset, which a self-referential check wouldn't.
        val name = "TestPlayer"
        val expected = UUID.nameUUIDFromBytes("OfflinePlayer:$name".toByteArray(Charsets.UTF_8))
        assertEquals(expected, OfflineIdentity.uuidFor(name))
    }

    @Test
    fun `uuidFor is a deterministic version-3 name UUID`() {
        val a = OfflineIdentity.uuidFor("Steve")
        assertEquals(a, OfflineIdentity.uuidFor("Steve"))
        assertEquals(3, a.version())
    }

    @Test
    fun `uuidFor is name-sensitive`() {
        assertNotEquals(OfflineIdentity.uuidFor("Steve"), OfflineIdentity.uuidFor("Alex"))
    }

    @Test
    fun `dashlessUuidFor is 32 hex chars without dashes`() {
        val dashless = OfflineIdentity.dashlessUuidFor("Steve")
        assertEquals(32, dashless.length)
        assertTrue(dashless.none { it == '-' })
        assertEquals(OfflineIdentity.uuidFor("Steve").toString().replace("-", ""), dashless)
    }
}

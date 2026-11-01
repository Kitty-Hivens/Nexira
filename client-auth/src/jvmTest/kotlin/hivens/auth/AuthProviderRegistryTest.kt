package hivens.auth

import hivens.core.data.SessionData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AuthProviderRegistryTest {

    private fun fake(providerId: String) = object : AuthProvider {
        override val id = providerId
        override val displayName = providerId
        override val capabilities = AuthCapabilities(supports2FA = false)
        override suspend fun login(username: String, password: String, serverId: String) = SessionData()
        override suspend fun completeTwoFactor(
            username: String, password: String, serverId: String, uid: String, code: String,
        ) = SessionData()
    }

    @Test
    fun `resolves registered providers by id and reports membership`() {
        val sc = fake("smartycraft")
        val offline = fake("offline")
        val registry = AuthProviderRegistry(listOf(sc, offline))

        assertSame(sc, registry["smartycraft"])
        assertSame(offline, registry["offline"])
        assertTrue(registry.contains("offline"))
        assertFalse(registry.contains("microsoft"))
        assertNull(registry["microsoft"])
        assertEquals(2, registry.all.size)
    }
}

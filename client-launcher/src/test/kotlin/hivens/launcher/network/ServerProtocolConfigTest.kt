package hivens.launcher.network

import hivens.config.ExperimentalConduitOverride
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The derived endpoints and host-key fallback decide which backend the launcher
 * talks to, so they are pinned here. A Mirror operator overriding baseUrl must
 * see every derived URL and the SSL-bypass host key follow.
 */
@OptIn(ExperimentalConduitOverride::class)
class ServerProtocolConfigTest {

    @AfterTest
    fun clearOverride() {
        System.clearProperty(ServerProtocolConfig.SYSTEM_PROP_BASE_URL)
    }

    @Test
    fun `defaults derive the production endpoints`() {
        val c = ServerProtocolConfig()
        assertEquals("https://www.smartycraft.ru/launcher2/index.php", c.authUrl)
        assertEquals("https://www.smartycraft.ru/downloads/smartycraft.jar", c.officialJarUrl)
        assertEquals("https://www.smartycraft.ru/launcher/clients", c.clientFilesBase)
    }

    @Test
    fun `a custom base url drives every derived endpoint`() {
        val c = ServerProtocolConfig(baseUrl = "https://mirror.example.com")
        assertEquals("https://mirror.example.com/launcher2/index.php", c.authUrl)
        assertEquals("https://mirror.example.com/downloads/smartycraft.jar", c.officialJarUrl)
        assertEquals("https://mirror.example.com/launcher/clients", c.clientFilesBase)
    }

    @Test
    fun `sslBypassHost extracts the authority from base url`() {
        assertEquals("mirror.example.com", ServerProtocolConfig(baseUrl = "https://mirror.example.com").sslBypassHost)
        assertEquals("www.smartycraft.ru", ServerProtocolConfig().sslBypassHost)
    }

    @Test
    fun `sslBypassHost falls back to the default host on a malformed base url`() {
        // A blank/garbage authority must never produce an empty match key.
        assertEquals("www.smartycraft.ru", ServerProtocolConfig(baseUrl = "not a url").sslBypassHost)
        assertEquals("www.smartycraft.ru", ServerProtocolConfig(baseUrl = "").sslBypassHost)
    }

    @Test
    fun `resolve without the system property returns the loaded config unchanged`() {
        val loaded = ServerProtocolConfig(baseUrl = "https://mirror.example.com")
        assertEquals(loaded, ServerProtocolConfig.resolve(loaded))
    }

    @Test
    fun `resolve applies the system property override and trims a trailing slash`() {
        System.setProperty(ServerProtocolConfig.SYSTEM_PROP_BASE_URL, "https://override.example.com/")
        val resolved = ServerProtocolConfig.resolve(ServerProtocolConfig(baseUrl = "https://www.smartycraft.ru"))
        assertEquals("https://override.example.com", resolved.baseUrl)
        // Non-overridden fields survive the copy.
        assertEquals(ServerProtocolConfig.DEFAULT_READ_TIMEOUT_MS, resolved.readTimeoutMs)
    }

    @Test
    fun `resolve ignores a blank system property`() {
        System.setProperty(ServerProtocolConfig.SYSTEM_PROP_BASE_URL, "   ")
        val loaded = ServerProtocolConfig(baseUrl = "https://www.smartycraft.ru")
        assertEquals(loaded, ServerProtocolConfig.resolve(loaded))
    }
}

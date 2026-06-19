package hivens.launcher.network

import hivens.launcher.network.MsaConfig.Companion.SYSTEM_PROP_CLIENT_ID
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MsaConfigLoaderTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private lateinit var dataDir: Path
    private lateinit var loader: MsaConfigLoader

    @BeforeTest
    fun setup() {
        dataDir = Files.createTempDirectory("nexira-msa-conf-test-")
        loader = MsaConfigLoader(json)
        System.clearProperty(SYSTEM_PROP_CLIENT_ID)
    }

    @AfterTest
    fun teardown() {
        Files.walk(dataDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        System.clearProperty(SYSTEM_PROP_CLIENT_ID)
    }

    @Test
    fun `absent file leaves Microsoft sign-in disabled`() {
        val cfg = loader.load(dataDir)
        assertEquals("", cfg.clientId)
        assertFalse(cfg.enabled)
    }

    @Test
    fun `client id from msa-config json enables sign-in`() {
        Files.writeString(
            dataDir.resolve(MsaConfigLoader.CONFIG_FILE_NAME),
            """{"clientId": "00000000-0000-0000-0000-000000000000"}""",
        )
        val cfg = loader.load(dataDir)
        assertEquals("00000000-0000-0000-0000-000000000000", cfg.clientId)
        assertTrue(cfg.enabled)
    }

    @Test
    fun `malformed file disables sign-in silently`() {
        Files.writeString(dataDir.resolve(MsaConfigLoader.CONFIG_FILE_NAME), "{not json")
        assertFalse(loader.load(dataDir).enabled)
    }

    @Test
    fun `system property overrides the file client id`() {
        Files.writeString(
            dataDir.resolve(MsaConfigLoader.CONFIG_FILE_NAME),
            """{"clientId": "from-file"}""",
        )
        System.setProperty(SYSTEM_PROP_CLIENT_ID, "from-prop")
        val cfg = loader.load(dataDir)
        assertEquals("from-prop", cfg.clientId)
        assertTrue(cfg.enabled)
    }

    @Test
    fun `unknown extra fields are tolerated`() {
        Files.writeString(
            dataDir.resolve(MsaConfigLoader.CONFIG_FILE_NAME),
            """{"clientId": "x", "futureField": "ignored"}""",
        )
        assertEquals("x", loader.load(dataDir).clientId)
    }
}

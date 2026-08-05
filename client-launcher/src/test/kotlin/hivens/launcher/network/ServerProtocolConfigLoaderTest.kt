package hivens.launcher.network

import hivens.config.ExperimentalConduitOverride
import hivens.launcher.network.ServerProtocolConfig.Companion.SYSTEM_PROP_BASE_URL
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ServerProtocolConfigLoaderTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private lateinit var dataDir: Path
    private lateinit var loader: ServerProtocolConfigLoader

    @BeforeTest
    fun setup() {
        dataDir = Files.createTempDirectory("aura-conf-test-")
        loader = ServerProtocolConfigLoader(json)
        // Defensive: clear any system property that might bleed from previous tests.
        System.clearProperty(SYSTEM_PROP_BASE_URL)
    }

    @AfterTest
    fun teardown() {
        Files.walk(dataDir).use { walk ->
            walk.sorted(Comparator.reverseOrder()).forEach { entry -> Files.deleteIfExists(entry) }
        }
        System.clearProperty(SYSTEM_PROP_BASE_URL)
    }

    @Test
    fun `load returns defaults when file is absent`() {
        val cfg = loader.load(dataDir)
        assertEquals(ServerProtocolConfig.DEFAULT_BASE_URL, cfg.baseUrl)
        assertEquals(ServerProtocolConfig.DEFAULT_CONNECT_TIMEOUT_MS, cfg.connectTimeoutMs)
    }

    @Test
    fun `load reads custom baseUrl from server-config json`() {
        Files.writeString(
            dataDir.resolve(ServerProtocolConfigLoader.CONFIG_FILE_NAME),
            """{"baseUrl": "https://mirror.example.com", "connectTimeoutMs": 5000}""",
        )
        val cfg = loader.load(dataDir)
        assertEquals("https://mirror.example.com", cfg.baseUrl)
        assertEquals(5_000L, cfg.connectTimeoutMs)
        assertEquals(ServerProtocolConfig.DEFAULT_READ_TIMEOUT_MS, cfg.readTimeoutMs)
    }

    @Test
    fun `load uses defaults silently when file is malformed`() {
        Files.writeString(
            dataDir.resolve(ServerProtocolConfigLoader.CONFIG_FILE_NAME),
            "{not valid json at all",
        )
        val cfg = loader.load(dataDir)
        assertEquals(ServerProtocolConfig.DEFAULT_BASE_URL, cfg.baseUrl)
    }

    @Test
    fun `load tolerates unknown extra fields`() {
        Files.writeString(
            dataDir.resolve(ServerProtocolConfigLoader.CONFIG_FILE_NAME),
            """{"baseUrl": "https://mirror.example.com", "futureField": "ignored"}""",
        )
        val cfg = loader.load(dataDir)
        assertEquals("https://mirror.example.com", cfg.baseUrl)
    }

    @OptIn(ExperimentalConduitOverride::class)
    @Test
    fun `system property override wins over file value`() {
        Files.writeString(
            dataDir.resolve(ServerProtocolConfigLoader.CONFIG_FILE_NAME),
            """{"baseUrl": "https://from-file.example.com"}""",
        )
        System.setProperty(SYSTEM_PROP_BASE_URL, "https://from-prop.example.com")
        val cfg = loader.load(dataDir)
        assertEquals("https://from-prop.example.com", cfg.baseUrl)
    }

    @Test
    fun `derived URLs reflect baseUrl from config file`() {
        Files.writeString(
            dataDir.resolve(ServerProtocolConfigLoader.CONFIG_FILE_NAME),
            """{"baseUrl": "https://mirror.example.com"}""",
        )
        val cfg = loader.load(dataDir)
        assertEquals("https://mirror.example.com/launcher2/index.php", cfg.authUrl)
        assertEquals("https://mirror.example.com/downloads/smartycraft.jar", cfg.officialJarUrl)
        assertEquals("https://mirror.example.com/launcher/clients", cfg.clientFilesBase)
    }
}

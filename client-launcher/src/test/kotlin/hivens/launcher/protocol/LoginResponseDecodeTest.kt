package hivens.launcher.protocol

import hivens.core.api.protocol.LoginResponse
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Regression test: decode a real-shape login response and confirm `client`
 * field (the file manifest) survives parsing. Caught a Phase 2 silent
 * regression where ClasspathProvider was getting an empty manifest because
 * LoginResponse parsing was dropping `client` somewhere.
 *
 * The fixture is a stripped-down version of the real wire response from
 * www.smartycraft.ru/launcher2/index.php captured 2026-05-14.
 */
class LoginResponseDecodeTest {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        isLenient = true
        encodeDefaults = true
    }

    private val realShapeFixture = """
        {
            "status": "OK",
            "playername": "TestPlayer",
            "uid": "a921e0baf5d4c4454774b09586a32d94",
            "uuid": "1e86dc3ad14dc24f4706915bb7d8593a",
            "session": "vRfeed1IvnNZPZFJ6c02h1qkxBru+PXd3KJA6OLWy18=",
            "money": 0,
            "hd": 1,
            "clan": null,
            "cape": null,
            "skintime": 1641472084,
            "capetime": null,
            "client": {
                "directories": {
                    "libraries-1.12.2": {
                        "files": {
                            "launchwrapper-1.12.jar": {"md5": "deadbeef", "size": 100}
                        }
                    },
                    "bin": {
                        "files": {}
                    },
                    "RPG": {
                        "files": {}
                    }
                },
                "files": {}
            },
            "testModeKey": null
        }
    """.trimIndent()

    @Test
    fun `LoginResponse decodes real wire shape with non-null client manifest`() {
        val response = json.decodeFromString<LoginResponse>(realShapeFixture)

        assertEquals("OK", response.status)
        assertEquals("TestPlayer", response.playername)
        val client = response.client
        assertNotNull(client, "client (file manifest) must NOT be null after decode")
        assertEquals(3, client.directories.size)
        assertNotNull(client.directories["libraries-1.12.2"])
        assertEquals(1, client.directories["libraries-1.12.2"]!!.files.size)
    }
}

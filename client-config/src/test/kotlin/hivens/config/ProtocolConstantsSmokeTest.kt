package hivens.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Smoke test for [Protocol] wire-protocol constants. Catches accidental
 * deletion / renaming of values that the SMARTYcraft server requires the
 * launcher to send -- a typo or "cleanup" of any of these silently breaks
 * production auth without any compile-time signal.
 *
 * Structural assertions only -- format and presence -- not literal value
 * matches. The values themselves CAN drift legitimately (upstream protocol
 * change, mimic version bump) and locking exact strings here would force a
 * coordinated test edit on every protocol drift, which adds friction without
 * catching real bugs.
 */
class ProtocolConstantsSmokeTest {

    @Test
    fun `mimic launcher version is non-blank semantic-versionish string`() {
        val v = Protocol.DEFAULT_MIMIC_LAUNCHER_VERSION
        assertTrue(v.isNotBlank(), "DEFAULT_MIMIC_LAUNCHER_VERSION is blank")
        assertTrue(
            v.matches(Regex("""\d+\.\d+(\.\d+)?(-.+)?""")),
            "DEFAULT_MIMIC_LAUNCHER_VERSION '$v' does not look like a semver-ish string",
        )
    }

    @Test
    fun `default server id is non-blank`() {
        assertTrue(Protocol.DEFAULT_SERVER_ID.isNotBlank(), "DEFAULT_SERVER_ID is blank")
    }

    @Test
    fun `default launcher hash is a lowercase 32-char hex MD5`() {
        val h = Protocol.DEFAULT_LAUNCHER_HASH
        assertEquals(32, h.length, "DEFAULT_LAUNCHER_HASH '$h' is not 32 chars long (MD5 hex)")
        assertTrue(h.all { it in '0'..'9' || it in 'a'..'f' }, "DEFAULT_LAUNCHER_HASH '$h' is not lowercase hex")
    }

    @Test
    fun `auth salt is non-blank`() {
        // Length / format is not contractual -- upstream picked whatever they
        // picked. Only structural sanity is testable here.
        assertTrue(Protocol.AUTH_SALT.isNotBlank(), "AUTH_SALT is blank")
    }

    @Test
    fun `default jar is non-blank and ends with dot-jar`() {
        val j = Protocol.DEFAULT_JAR
        assertTrue(j.isNotBlank(), "DEFAULT_JAR is blank")
        assertTrue(j.endsWith(".jar"), "DEFAULT_JAR '$j' does not end with .jar")
    }

    @Test
    fun `default checksum is the empty-string MD5`() {
        // d41d8cd98f00b204e9800998ecf8427e is a well-known constant: MD5("").
        // The Protocol KDoc explicitly notes the field is required by the
        // server but its content is ignored. Lock the value here so a future
        // contributor "fixing" this constant to a non-empty-string MD5 has
        // to think about whether they're actually changing behaviour.
        assertEquals(
            "d41d8cd98f00b204e9800998ecf8427e",
            Protocol.DEFAULT_CSUM,
            "DEFAULT_CSUM is no longer the empty-string MD5 -- intentional?",
        )
    }

    @Test
    fun `runtime override switches MIMIC_LAUNCHER_VERSION`() {
        val original = System.getProperty(Protocol.SYSTEM_PROP_MIMIC_VERSION)
        try {
            System.clearProperty(Protocol.SYSTEM_PROP_MIMIC_VERSION)
            assertEquals(
                Protocol.DEFAULT_MIMIC_LAUNCHER_VERSION,
                Protocol.MIMIC_LAUNCHER_VERSION,
                "Without override, MIMIC_LAUNCHER_VERSION should equal DEFAULT_MIMIC_LAUNCHER_VERSION",
            )
            System.setProperty(Protocol.SYSTEM_PROP_MIMIC_VERSION, "9.9.9-test")
            assertEquals(
                "9.9.9-test",
                Protocol.MIMIC_LAUNCHER_VERSION,
                "Override via system property should take precedence",
            )
        } finally {
            if (original != null) System.setProperty(Protocol.SYSTEM_PROP_MIMIC_VERSION, original)
            else System.clearProperty(Protocol.SYSTEM_PROP_MIMIC_VERSION)
        }
    }
}

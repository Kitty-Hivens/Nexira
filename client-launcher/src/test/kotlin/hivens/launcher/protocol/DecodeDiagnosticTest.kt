package hivens.launcher.protocol

import hivens.core.api.protocol.LoginResponse
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The diagnosis a failed decode is allowed to log.
 *
 * A login response carries the uid and the session token, and kotlinx.serialization
 * appends an excerpt of its input to the failure message. The excerpt is cut around
 * the error offset, so the key can fall outside it and the redactor's marker rules
 * have nothing to anchor on. What the log gets has to be the reason, not the bytes.
 */
class DecodeDiagnosticTest {

    private val uid = "a921e0baf5d4c4454774b09586a32d94"
    private val session = "vRfeed1IvnNZPZFJ6c02h1qkxBru+PXd3KJA6OLWy18="

    private fun failureOnRealShape(): Throwable {
        val body = """{"status":"OK","playername":"TestPlayer","uid":"$uid",""" +
            """"uuid":"1e86dc3ad14dc24f4706915bb7d8593a","session":"$session","money":"not-an-int"}"""
        return runCatching { Json { ignoreUnknownKeys = true }.decodeFromString(LoginResponse.serializer(), body) }
            .exceptionOrNull() ?: error("the fixture must fail to decode")
    }

    @Test
    fun `the decoder's own message carries the body, which is why this exists`() {
        // Not a claim about our code: a guard on the library behaviour the rest of
        // this test is built on. If a future version stops embedding the input, this
        // is the line that says so.
        val raw = failureOnRealShape().message.orEmpty()
        assertTrue(raw.contains("JSON input:"), "kotlinx no longer embeds the input: $raw")
    }

    @Test
    fun `the diagnostic keeps the reason and drops the body`() {
        val diagnostic = decodeDiagnostic(failureOnRealShape())

        assertFalse(diagnostic.contains(uid), "uid reached the log")
        assertFalse(diagnostic.contains("vRfeed1IvnNZ"), "session reached the log")
        assertFalse(diagnostic.contains("JSON input"), "the excerpt reached the log")
        assertTrue(diagnostic.contains("money"), "the failing path is the diagnosis and must survive")
        assertTrue(diagnostic.contains("offset"), "so is the offset")
    }

    @Test
    fun `a throwable with no message still names its type`() {
        assertTrue(decodeDiagnostic(RuntimeException()).contains("RuntimeException"))
    }
}

package hivens.launcher.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The validator is the only gate between a server-supplied identifier and a
 * filesystem path, so a regression here is a path-traversal hole. Tests cover
 * the accept list (every shipped server id shape) and the full reject taxonomy.
 */
class ServerNameValidatorTest {

    @Test
    fun `accepts every server identifier shape that has shipped`() {
        for (name in listOf("Industrial", "RPG", "SkyBlock", "MagicRPG", "Nexira.v2", "server-1", "a", "A_B-c.9")) {
            assertTrue(ServerNameValidator.isValid(name), "should accept: $name")
        }
    }

    @Test
    fun `rejects the empty string`() {
        assertFalse(ServerNameValidator.isValid(""))
    }

    @Test
    fun `rejects the dot and dot-dot path primitives`() {
        assertFalse(ServerNameValidator.isValid("."))
        assertFalse(ServerNameValidator.isValid(".."))
    }

    @Test
    fun `rejects any embedded dot-dot even with otherwise-legal characters`() {
        for (name in listOf("a..b", "Industrial..", "..evil", "x..y..z")) {
            assertFalse(ServerNameValidator.isValid(name), "should reject embedded dot-dot: $name")
        }
    }

    @Test
    fun `rejects path separators`() {
        for (name in listOf("../etc/passwd", "a/b", "a\\b", "Industrial..\\evil", "/abs", "C:\\win")) {
            assertFalse(ServerNameValidator.isValid(name), "should reject separator: $name")
        }
    }

    @Test
    fun `rejects whitespace and control characters`() {
        for (name in listOf("a b", " leading", "trailing ", "tab\tx", "new\nline", "nul x")) {
            assertFalse(ServerNameValidator.isValid(name), "should reject whitespace/control")
        }
    }

    @Test
    fun `rejects non-ascii letters`() {
        // Built from code points so this source file stays strictly ASCII (git
        // diffs it as text, not binary) while the runtime strings still carry the
        // characters the whitelist must reject.
        fun cp(vararg points: Int): String = points.fold(StringBuilder()) { b, p -> b.append(p.toChar()) }.toString()
        val industrialAcute = "Industri" + cp(0x00E1) + "l"        // Industrial, a-acute
        val cyrillicServer = cp(0x0441, 0x0435, 0x0440, 0x0432, 0x0435, 0x0440) // "server" in Cyrillic
        val nameUmlaut = "n" + cp(0x00E4) + "me"                   // name, a-umlaut

        for (name in listOf(industrialAcute, cyrillicServer, nameUmlaut)) {
            assertFalse(ServerNameValidator.isValid(name), "should reject non-ascii")
        }
    }

    @Test
    fun `require returns the name verbatim when valid`() {
        assertEquals("Nexira.v2", ServerNameValidator.require("Nexira.v2"))
    }

    @Test
    fun `require throws IllegalArgumentException on an invalid name`() {
        assertFailsWith<IllegalArgumentException> { ServerNameValidator.require("../etc/passwd") }
    }

    @Test
    fun `require error message redacts and truncates a long hostile string`() {
        val hostile = "../" + "A".repeat(200)
        val ex = assertFailsWith<IllegalArgumentException> { ServerNameValidator.require(hostile) }
        val msg = ex.message.orEmpty()
        assertTrue(msg.contains("Rejected server identifier"), "msg: $msg")
        // Truncated preview must not echo the whole 200-char payload back into logs.
        assertTrue(msg.length < hostile.length, "preview must be shorter than the input")
    }

    @Test
    fun `require error preview keeps a short name intact`() {
        val ex = assertFailsWith<IllegalArgumentException> { ServerNameValidator.require("bad/name") }
        assertTrue(ex.message!!.contains("bad/name"))
    }
}

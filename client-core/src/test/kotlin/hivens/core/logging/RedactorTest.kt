package hivens.core.logging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RedactorTest {

    @Test
    fun `accessToken assignment is redacted`() {
        val redacted = Redactor.redact("accessToken=eyJraWQ.payload.sig")
        assertFalse(redacted.contains("eyJraWQ"))
        assertTrue(redacted.contains("accessToken"))
        assertTrue(redacted.contains("<redacted>"))
    }

    @Test
    fun `accessToken in json shape is redacted`() {
        val redacted = Redactor.redact("""{"accessToken": "abc123def456ghi"}""")
        assertFalse(redacted.contains("abc123def456ghi"))
    }

    @Test
    fun `password value is redacted regardless of quoting`() {
        assertFalse(Redactor.redact("password=hunter2").contains("hunter2"))
        assertFalse(Redactor.redact("""{"password":"hunter2"}""").contains("hunter2"))
        assertFalse(Redactor.redact("password: hunter2 next=word").contains("hunter2"))
    }

    @Test
    fun `bearer token is redacted but the bearer marker stays`() {
        val redacted = Redactor.redact("Authorization: Bearer eyJraWQ.payload.sig")
        assertTrue(redacted.contains("Bearer"))
        assertFalse(redacted.contains("eyJraWQ"))
    }

    @Test
    fun `uuid is replaced with placeholder`() {
        val redacted = Redactor.redact("uuid=550e8400-e29b-41d4-a716-446655440000 launching")
        assertTrue(redacted.contains("<uuid>"))
        assertFalse(redacted.contains("550e8400-e29b-41d4-a716-446655440000"))
    }

    @Test
    fun `redactor is idempotent on already-redacted text`() {
        val once  = Redactor.redact("accessToken=xxxyyy123 uuid=550e8400-e29b-41d4-a716-446655440000")
        val twice = Redactor.redact(once)
        assertEquals(once, twice)
    }

    @Test
    fun `empty input returns empty without allocation`() {
        assertEquals("", Redactor.redact(""))
    }

    @Test
    fun `text with no sensitive patterns passes through`() {
        val text = "Loading 247 mods for Industrial pack..."
        assertEquals(text, Redactor.redact(text))
    }

    @Test
    fun `case-insensitive matching catches PassWord and AccessTOKEN`() {
        assertFalse(Redactor.redact("PassWord=secret123").contains("secret123"))
        assertFalse(Redactor.redact("AccessTOKEN=abcd1234ef").contains("abcd1234ef"))
    }

    @Test
    fun `multiple sensitive values in one line are all redacted`() {
        val redacted = Redactor.redact(
            "user=alice password=hunter2 uuid=550e8400-e29b-41d4-a716-446655440000 accessToken=longtoken123"
        )
        assertFalse(redacted.contains("hunter2"))
        assertFalse(redacted.contains("550e8400"))
        assertFalse(redacted.contains("longtoken123"))
        assertTrue(redacted.contains("user=alice"))
    }
}

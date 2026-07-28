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

    @Test
    fun `RFC 6750 bearer token covers slashes plus tildes and equals padding`() {
        // base64-shaped JWT-style token: dot-separated, with `+/=` padding.
        // Earlier `[A-Za-z0-9._\-]{8,}` regex would have masked only "eyJraWQ"
        // and left "abc+def/" raw -- partial-leak.
        val token = "eyJraWQ+abc/def~xyz=="
        val redacted = Redactor.redact("Authorization: Bearer $token next=word")

        assertFalse(redacted.contains("eyJraWQ"),  "head of token leaked")
        assertFalse(redacted.contains("abc+def"),  "middle of token leaked through `+/` chars")
        assertFalse(redacted.contains("xyz"),      "tail of token leaked before padding")
        // Padding may or may not be eaten depending on regex shape -- what matters
        // is that the value-bearing portion is gone.
        assertTrue(redacted.contains("Bearer"),    "Bearer marker still present")
        assertTrue(redacted.contains("next=word"), "trailing tokens unrelated to bearer are preserved")
    }

    @Test
    fun `accessToken with base64 slash-and-plus characters is fully redacted`() {
        // JWT-shaped payload as accessToken= value.
        val redacted = Redactor.redact("accessToken=eyJh.eyJp+xyz/abc=")
        assertFalse(redacted.contains("eyJh"))
        assertFalse(redacted.contains("xyz/abc"))
        assertTrue(redacted.contains("<redacted>"))
    }

    // --- the two shapes that reached disk unmasked ---

    @Test
    fun `authlib's unparseable-JWT complaint does not leak the token`() {
        // Shape taken from a real game.log rather than assumed: the token is
        // wrapped in an exception toString, so the marker sits mid-sentence
        // with no key in front of it.
        val token = "a3f19c7b42e08d5169bc0724fe3a81d0"
        val line = "ms server: java.lang.RuntimeException: Failed to parse into SignedJWT: $token"
        val redacted = Redactor.redact(line)
        assertFalse(redacted.contains(token), "session token leaked through the SignedJWT message")
        assertTrue(redacted.contains("<redacted>"))
    }

    @Test
    fun `undashed uuid behind its marker is masked`() {
        // The launcher emits `--uuid` without dashes, so the 8-4-4-4-12 rule
        // never saw it.
        val uuid = "5074c8d34ed2424d91e4cc37f3ba9404"
        val redacted = Redactor.redact("CMD: java --username alice --uuid $uuid --accessToken abcdef123456")
        assertFalse(redacted.contains(uuid), "undashed uuid leaked")
        assertTrue(redacted.contains("--username alice"), "unrelated args must survive")
    }

    @Test
    fun `a bare md5 is left alone`() {
        // Sync logs are full of file hashes. Masking them unanchored would
        // trade a leak for unusable sync debugging.
        val md5 = "d41d8cd98f00b204e9800998ecf8427e"
        val text = "verify mods/foo.jar md5=$md5 ok"
        assertEquals(text, Redactor.redact(text))
    }

    @Test
    fun `a registered secret is masked in any surrounding text`() {
        val token = "z9y8x7w6v5u4t3s2r1q0p9o8n7m6l5k4"
        try {
            Redactor.registerSecret(token)
            // Deliberately a shape no rule anticipates -- that is the point of
            // registering the value rather than another marker.
            val redacted = Redactor.redact("mod X says: the thing is $token, good luck")
            assertFalse(redacted.contains(token))
            assertTrue(redacted.contains("<redacted>"))
        } finally {
            Redactor.forgetSecrets()
        }
    }

    @Test
    fun `a short value is not registered`() {
        try {
            // "0" is the placeholder for a blank token. Masking it would
            // rewrite every zero in every log line.
            Redactor.registerSecret("0")
            assertEquals("loaded 0 mods in 0 ms", Redactor.redact("loaded 0 mods in 0 ms"))
        } finally {
            Redactor.forgetSecrets()
        }
    }

    @Test
    fun `redaction stays idempotent with the new rules`() {
        val token = "a3f19c7b42e08d5169bc0724fe3a81d0"
        try {
            Redactor.registerSecret(token)
            val once  = Redactor.redact("SignedJWT: $token --uuid 5074c8d34ed2424d91e4cc37f3ba9404")
            val twice = Redactor.redact(once)
            assertEquals(once, twice)
        } finally {
            Redactor.forgetSecrets()
        }
    }
}

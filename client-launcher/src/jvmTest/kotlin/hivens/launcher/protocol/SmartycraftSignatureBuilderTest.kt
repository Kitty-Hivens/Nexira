package hivens.launcher.protocol

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Pins the SmartyCraft `check=` signature wire format. These signatures gate
 * twoauth and skin upload, so a silent change to the field order, the separator,
 * or the time-bucket width breaks every signed action against the live server.
 * The independent MD5 below is the drift tripwire; the golden hex is a second,
 * fully-fixed anchor in case both impl and re-derivation drift.
 *
 * The four-field shape is exercised through [SmartycraftSignatureBuilder.forTwoAuth].
 * It is the scheme that is under test rather than any one action, and the
 * trailing field is positional: the builder joins bytes and does not know what
 * the last one means.
 */
class SmartycraftSignatureBuilderTest {

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    @Test
    fun `the four-field signature matches a fully fixed golden vector`() {
        // Recomputed independently: printf '17|42|alice|Industrial' | md5sum
        assertEquals(
            "0e11fcf50f4a9eee6a653f2110915136",
            SmartycraftSignatureBuilder.forTwoAuth("42", "alice", "Industrial", timeBucket = 17),
        )
    }

    @Test
    fun `twoauth signature is MD5 of bucket-uid-login-code`() {
        val sig = SmartycraftSignatureBuilder.forTwoAuth(uid = "42", login = "alice", code = "123456", timeBucket = 99)
        assertEquals(md5("99|42|alice|123456"), sig)
    }

    @Test
    fun `upload signature is MD5 of bucket-uid-login with no extra fields`() {
        val sig = SmartycraftSignatureBuilder.forUpload(uid = "42", login = "alice", timeBucket = 7)
        assertEquals(md5("7|42|alice"), sig)
    }

    @Test
    fun `field order is significant -- swapping uid and login changes the signature`() {
        val a = SmartycraftSignatureBuilder.forTwoAuth(uid = "alice", login = "42", code = "RPG", timeBucket = 5)
        val b = SmartycraftSignatureBuilder.forTwoAuth(uid = "42", login = "alice", code = "RPG", timeBucket = 5)
        assertNotEquals(a, b, "uid and login must not be interchangeable")
    }

    @Test
    fun `time bucket participates -- a different bucket yields a different signature`() {
        val a = SmartycraftSignatureBuilder.forTwoAuth("42", "alice", "Industrial", timeBucket = 17)
        val b = SmartycraftSignatureBuilder.forTwoAuth("42", "alice", "Industrial", timeBucket = 18)
        assertNotEquals(a, b)
    }

    @Test
    fun `the trailing field participates and nothing else stands in for it`() {
        // The builder joins bytes: a four-field signature says nothing about which
        // action it was built for, so the trailing field is the only thing carrying
        // that context and it has to reach the hash.
        val a = SmartycraftSignatureBuilder.forTwoAuth("42", "alice", "x", timeBucket = 1)
        val b = SmartycraftSignatureBuilder.forTwoAuth("42", "alice", "y", timeBucket = 1)
        assertNotEquals(a, b)
        // And a three-field upload signature is not a four-field one with a blank tail.
        assertNotEquals(
            SmartycraftSignatureBuilder.forUpload("42", "alice", timeBucket = 1),
            SmartycraftSignatureBuilder.forTwoAuth("42", "alice", "", timeBucket = 1),
        )
    }

    @Test
    fun `signature is lowercase 32-char hex`() {
        val sig = SmartycraftSignatureBuilder.forUpload("42", "alice", timeBucket = 0)
        assertTrue(sig.matches(Regex("^[0-9a-f]{32}$")), "got: $sig")
    }

    @Test
    fun `current time bucket is currentMillis over ten thousand`() {
        val before = System.currentTimeMillis() / 10_000L
        val bucket = SmartycraftSignatureBuilder.currentTimeBucket()
        val after = System.currentTimeMillis() / 10_000L
        assertTrue(bucket in before..after, "bucket $bucket not within [$before, $after]")
    }

    @Test
    fun `empty fields still produce a stable signature`() {
        assertEquals(md5("0|||"), SmartycraftSignatureBuilder.forTwoAuth("", "", "", timeBucket = 0))
    }
}

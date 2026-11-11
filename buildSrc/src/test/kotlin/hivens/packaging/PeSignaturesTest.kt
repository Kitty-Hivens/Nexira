package hivens.packaging

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Telling a signed Windows binary from the unsigned one it was made from, which is
 * what lets the runtime image take the JDK vendor's signed copies.
 */
class PeSignaturesTest {

    /** A PE32+ file: headers, [code], and with [signed] a certificate table the way a signer appends one. */
    private fun pe(code: ByteArray, signed: Boolean, checksum: Int = 0): ByteArray {
        val headerSize = 0x40 + 24 + 240
        val body = ByteBuffer.allocate(headerSize + code.size).order(ByteOrder.LITTLE_ENDIAN)
        body.put(0, 'M'.code.toByte()).put(1, 'Z'.code.toByte())
        body.putInt(0x3C, 0x40)
        body.putInt(0x40, 0x00004550)
        val optional = 0x40 + 24
        body.putShort(optional, 0x20B.toShort())
        body.putInt(optional + 64, checksum)
        body.position(headerSize)
        body.put(code)
        val unsigned = body.array()
        if (!signed) return unsigned
        val padded = (unsigned.size + 7) / 8 * 8
        val table = ByteArray(64) { (it * 7 + 3).toByte() }
        val out = ByteBuffer.allocate(padded + table.size).order(ByteOrder.LITTLE_ENDIAN)
        out.put(unsigned)
        out.position(padded)
        out.put(table)
        val securityEntry = optional + 112 + 4 * 8
        out.putInt(securityEntry, padded)
        out.putInt(securityEntry + 4, table.size)
        return out.array()
    }

    private val code = ByteArray(301) { (it % 251 + 1).toByte() }

    @Test
    fun `a signed copy is the same image as the unsigned file it was made from`() {
        val unsigned = pe(code, signed = false)
        val signed = pe(code, signed = true, checksum = 0x5A5A)
        assertFalse(PeSignatures.isSigned(unsigned))
        assertTrue(PeSignatures.isSigned(signed))
        assertTrue(PeSignatures.sameImage(unsigned, signed))
    }

    @Test
    fun `one byte of different code is a different image`() {
        val other = code.copyOf().also { it[150] = (it[150] + 1).toByte() }
        assertFalse(PeSignatures.sameImage(pe(code, signed = false), pe(other, signed = true)))
    }

    @Test
    fun `a file that is not a PE matches nothing`() {
        val text = "not a binary at all, just text long enough to have a header".repeat(4).toByteArray()
        assertFalse(PeSignatures.isSigned(text))
        assertFalse(PeSignatures.sameImage(text, text))
    }

    /**
     * Against a real Windows JDK, unpacked at `NEXIRA_PROBE_WINDOWS_JDK`: every
     * native in its jmods has a signed copy in its `bin` that is the same image.
     * Skipped without it, since no Windows JDK is around on most machines.
     */
    @Test
    fun `every native in a windows jdk's jmods matches its signed copy`() {
        val jdk = System.getenv("NEXIRA_PROBE_WINDOWS_JDK")?.let(::File) ?: return
        var compared = 0
        jdk.resolve("jmods").listFiles { f -> f.extension == "jmod" }.orEmpty().forEach { jmod ->
            // A jmod is a four-byte header in front of a zip.
            ZipInputStream(jmod.inputStream().buffered().also { it.skip(4) }).use { zip ->
                generateSequence { zip.nextEntry }.forEach { entry ->
                    val name = entry.name
                    val section = name.substringBefore('/')
                    if (section !in setOf("bin", "lib") || !name.lowercase().endsWith(".dll") && !name.lowercase().endsWith(".exe")) return@forEach
                    val built = zip.readBytes()
                    val signed = jdk.resolve("bin").resolve(name.substringAfter('/')).readBytes()
                    assertTrue(PeSignatures.isSigned(signed), "$name is signed in bin")
                    assertTrue(PeSignatures.sameImage(built, signed), "$name is the same image")
                    compared++
                }
            }
        }
        assertTrue(compared > 0, "found natives to compare")
        println("compared $compared natives")
    }
}

package hivens.packaging

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The Authenticode signature of a Windows PE file (an exe or a dll), read
 * straight from its headers.
 *
 * A signature changes three things in the file and nothing else: the optional
 * header's checksum, the security entry of its data directory, and a
 * certificate table appended at the end (after padding to eight bytes). So two
 * files whose bytes agree once those three are set aside hold the same code,
 * whichever of them carries a signature.
 */
internal object PeSignatures {

    /** Whether [file] carries a signature at all. Not whether it verifies. */
    fun isSigned(file: ByteArray): Boolean = headers(file)?.let { it.securityOffset != 0 } ?: false

    /**
     * Whether [a] and [b] are the same PE image apart from their signatures.
     * False for anything that is not a PE file.
     */
    fun sameImage(a: ByteArray, b: ByteArray): Boolean {
        val na = unsigned(a) ?: return false
        val nb = unsigned(b) ?: return false
        return na.contentEquals(nb)
    }

    private class Headers(val checksum: Int, val securityEntry: Int, val securityOffset: Int)

    private fun headers(file: ByteArray): Headers? {
        if (file.size < 0x40 || file[0] != 'M'.code.toByte() || file[1] != 'Z'.code.toByte()) return null
        val buf = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN)
        val pe = buf.getInt(0x3C)
        if (pe < 0 || pe + 24 + 2 > file.size || buf.getInt(pe) != PE_SIGNATURE) return null
        val optional = pe + 24
        val directories = when (buf.getShort(optional).toInt() and 0xFFFF) {
            PE32 -> optional + 96
            PE32_PLUS -> optional + 112
            else -> return null
        }
        val securityEntry = directories + SECURITY_DIRECTORY * 8
        if (securityEntry + 8 > file.size) return null
        val securityOffset = buf.getInt(securityEntry)
        if (securityOffset < 0 || securityOffset > file.size) return null
        return Headers(optional + 64, securityEntry, securityOffset)
    }

    /** [file] with its signature, the checksum and the security entry taken out, and the padding before the table with them. */
    private fun unsigned(file: ByteArray): ByteArray? {
        val h = headers(file) ?: return null
        val body = if (h.securityOffset != 0) file.copyOf(h.securityOffset) else file.copyOf()
        body.fill(0, h.checksum, h.checksum + 4)
        body.fill(0, h.securityEntry, h.securityEntry + 8)
        var end = body.size
        while (end > 0 && body[end - 1] == 0.toByte()) end--
        return body.copyOf(end)
    }

    private const val PE_SIGNATURE = 0x00004550
    private const val PE32 = 0x10B
    private const val PE32_PLUS = 0x20B
    private const val SECURITY_DIRECTORY = 4
}

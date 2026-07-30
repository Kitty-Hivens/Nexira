package hivens.core.net

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * A content hash the transfer engine can verify bytes against.
 *
 * The algorithm is carried with the value rather than fixed, because the
 * download surface pins four of them at once: Mojang and the smrt mirror
 * publish sha1, Modrinth sha512, our own releases and the open-smrt helper
 * sha256, and the SmartyCraft protocol md5. A single-algorithm engine would
 * push each caller back into hashing its own bytes, which is how the
 * verification rules ended up different in every path.
 */
data class Digest(val algorithm: DigestAlgorithm, val value: String) {
    /** True when [hex] is this digest's value, compared case-insensitively. */
    fun matches(hex: String): Boolean = value.equals(hex, ignoreCase = true)
}

enum class DigestAlgorithm(val jcaName: String, val blockSizeHint: Int = 64 * 1024) {
    MD5("MD5"),
    SHA1("SHA-1"),
    SHA256("SHA-256"),
    SHA512("SHA-512"),
    ;

    fun digester(): MessageDigest = MessageDigest.getInstance(jcaName)
}

/** Lowercase hex of [bytes] under this algorithm. */
fun DigestAlgorithm.of(bytes: ByteArray): String = digester().digest(bytes).toHex()

/**
 * Lowercase hex of the whole file, streamed. The 64 KiB buffer is what keeps a
 * 300 MB resource pack off the heap; every caller on this surface hashes files
 * of that order.
 */
fun DigestAlgorithm.of(path: Path): String {
    val md = digester()
    Files.newInputStream(path).use { input ->
        val buf = ByteArray(blockSizeHint)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            md.update(buf, 0, n)
        }
    }
    return md.digest().toHex()
}

/**
 * Whole-file hex plus the hex of each [blockSize] slice, from ONE read of the
 * file.
 *
 * The block hashes are only worth anything because they are taken here: the
 * whole-file value returned alongside them is what the caller compares against
 * the manifest, so a block list that survives that comparison was computed over
 * bytes already proven correct. Taken at any other moment it would be a hash of
 * whatever happens to be on disk, which proves nothing.
 */
fun DigestAlgorithm.ofWithBlocks(path: Path, blockSize: Int): FileDigests {
    val whole = digester()
    val block = digester()
    val blocks = ArrayList<String>()
    var inBlock = 0
    Files.newInputStream(path).use { input ->
        val buf = ByteArray(blockSizeHint)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            whole.update(buf, 0, n)
            var off = 0
            while (off < n) {
                val take = minOf(n - off, blockSize - inBlock)
                block.update(buf, off, take)
                off += take
                inBlock += take
                if (inBlock == blockSize) {
                    blocks += block.digest().toHex()
                    inBlock = 0
                }
            }
        }
    }
    // Trailing partial block: a file whose length is not a multiple of the block
    // size ends here, and its last block still has to be verifiable.
    if (inBlock > 0) blocks += block.digest().toHex()
    return FileDigests(whole = whole.digest().toHex(), blocks = blocks, blockSize = blockSize)
}

/** The two views of one file's bytes: the whole hash, and the per-block hashes. */
data class FileDigests(val whole: String, val blocks: List<String>, val blockSize: Int)

private fun ByteArray.toHex(): String {
    val out = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xff
        out.append(HEX[v ushr 4]).append(HEX[v and 0x0f])
    }
    return out.toString()
}

private const val HEX = "0123456789abcdef"

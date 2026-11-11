package hivens.launcher.util

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Streaming hashes of a file, lowercase hex. Shared by the provisioners, the
 * sync service, and the pack installers that verify downloads against manifest
 * hashes; the 64 KiB buffer keeps large library jars off the heap.
 */
internal fun sha1Of(path: Path): String = digestOf(path, "SHA-1")

internal fun sha512Of(path: Path): String = digestOf(path, "SHA-512")

private fun digestOf(path: Path, algorithm: String): String {
    val md = MessageDigest.getInstance(algorithm)
    Files.newInputStream(path).use { input ->
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            md.update(buf, 0, n)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

package hivens.launcher.util

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Streaming SHA-1 of a file, lowercase hex. Shared by the provisioners
 * and sync service that verify downloads against manifest hashes; the
 * 64 KiB buffer keeps large library jars off the heap.
 */
internal fun sha1Of(path: Path): String {
    val md = MessageDigest.getInstance("SHA-1")
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

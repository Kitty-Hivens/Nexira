package hivens.core.util

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Common hashing utilities shared across services.
 */
object HashUtils {

    fun md5(input: String): String {
        return try {
            val md = MessageDigest.getInstance("MD5")
            val hash = md.digest(input.toByteArray(StandardCharsets.UTF_8))
            hash.joinToString("") { "%02x".format(it) }
        } catch (_: Exception) { "" }
    }
}

package hivens.core.util

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.*
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

object SecurityUtils {

    private const val SECRET = "AuraLauncherLocalKeySalt_v1" // Локальная соль

    private fun getKey(): SecretKeySpec {
        return try {
            var key = SECRET.toByteArray(StandardCharsets.UTF_8)
            val sha = MessageDigest.getInstance("SHA-1")
            key = sha.digest(key)
            key = key.copyOf(16) // 128-bit keys
            SecretKeySpec(key, "AES")
        } catch (e: Exception) {
            throw RuntimeException("Error generating key", e)
        }
    }

    fun encrypt(strToEncrypt: String): String? {
        return try {
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, getKey())
            Base64.getEncoder().encodeToString(cipher.doFinal(strToEncrypt.toByteArray(StandardCharsets.UTF_8)))
        } catch (e: Exception) {
            null
        }
    }

    fun decrypt(strToDecrypt: String): String? {
        return try {
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, getKey())
            String(cipher.doFinal(Base64.getDecoder().decode(strToDecrypt)))
        } catch (e: Exception) {
            null
        }
    }
}

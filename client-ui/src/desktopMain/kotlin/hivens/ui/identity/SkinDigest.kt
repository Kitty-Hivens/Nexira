package hivens.ui.identity

import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import org.jetbrains.skia.Image
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Content fingerprint of a skin/cape PNG, hashed over the DECODED ARGB pixels
 * rather than the file bytes. The server re-encodes a skin on upload/download and
 * the client re-encodes again when caching, so the same texture has different file
 * bytes at each hop; hashing pixels is stable across those re-encodings, which is
 * what the library's dedup needs. Returns null when [bytes] cannot be decoded.
 */
fun skinContentHash(bytes: ByteArray): String? = runCatching {
    val pm = Image.makeFromEncoded(bytes).use { it.toComposeImageBitmap() }.toPixelMap()
    val argb = IntArray(pm.width * pm.height)
    var i = 0
    for (y in 0 until pm.height) for (x in 0 until pm.width) argb[i++] = pm[x, y].toArgb()
    val buf = ByteArray(argb.size * 4)
    ByteBuffer.wrap(buf).asIntBuffer().put(argb)
    MessageDigest.getInstance("SHA-256").digest(buf).joinToString("") { "%02x".format(it) }
}.getOrNull()

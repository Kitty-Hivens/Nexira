package hivens.launcher.security

import java.nio.file.Files
import java.nio.file.Path

/**
 * Answers whether the interpreter a launch is about to run is a real program.
 *
 * Everything a launch command decides -- which agents attach, which arguments
 * survive, whether the attach listener is open -- is decided by handing a path to
 * `ProcessBuilder`. Replace the file at that path with a two-line shell script
 * that re-exports `LD_PRELOAD`, appends a `-javaagent:` and calls the real
 * binary, and every one of those decisions is made again by someone else, with
 * the launcher supplying its own arguments to the wrapper. It is the cheapest
 * total bypass there is, and it lives in a directory the launcher provisions.
 *
 * The check is deliberately shallow: a program starts with its format's magic
 * number and a script starts with `#!`, so this separates the two and nothing
 * more. It does not, and is not meant to, notice a genuinely patched binary --
 * that costs a recorded digest of a two-hundred-megabyte runtime, and the digest
 * would sit on the same disk. Refusing the easy version is what is on offer.
 */
object JavaBinary {

    private val MAGICS: List<ByteArray> = listOf(
        byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()), // Linux, BSD
        byteArrayOf(0x4D, 0x5A.toByte()),                                            // Windows PE
        byteArrayOf(0xCF.toByte(), 0xFA.toByte(), 0xED.toByte(), 0xFE.toByte()),     // Mach-O 64, LE
        byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()),     // Mach-O universal
    )

    /**
     * True when [path] opens with a known executable magic number. A path that
     * cannot be read at all answers false: a launch about to hand over a session
     * token is the wrong place to give something the benefit of the doubt.
     */
    fun isNativeExecutable(path: Path): Boolean {
        if (!Files.isRegularFile(path)) return false
        val head = runCatching {
            Files.newInputStream(path).use { input ->
                val buffer = ByteArray(4)
                val read = input.read(buffer)
                if (read <= 0) ByteArray(0) else buffer.copyOf(read)
            }
        }.getOrNull() ?: return false
        return MAGICS.any { magic ->
            head.size >= magic.size && magic.indices.all { head[it] == magic[it] }
        }
    }
}

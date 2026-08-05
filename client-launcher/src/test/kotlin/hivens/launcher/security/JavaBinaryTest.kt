package hivens.launcher.security

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.div
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JavaBinaryTest {

    private lateinit var workDir: Path

    @BeforeTest
    fun setup() { workDir = Files.createTempDirectory("nexira-javabin-test-") }

    @AfterTest
    fun teardown() {
        Files.walk(workDir).use { walk ->
            walk.sorted(Comparator.reverseOrder()).forEach { entry -> Files.deleteIfExists(entry) }
        }
    }

    private fun file(name: String, bytes: ByteArray): Path =
        (workDir / name).also { Files.write(it, bytes) }

    /** The bypass this exists for: the launcher's own arguments handed to a script. */
    @Test
    fun `a wrapper script is not a java runtime`() {
        val script = file("java", "#!/bin/sh\nexec /usr/bin/java -javaagent:/tmp/x.jar \"$@\"\n".toByteArray())

        assertFalse(JavaBinary.isNativeExecutable(script))
    }

    @Test
    fun `a native program is accepted on each platform's format`() {
        assertTrue(JavaBinary.isNativeExecutable(file("elf", byteArrayOf(0x7F, 0x45, 0x4C, 0x46, 0x02))))
        assertTrue(JavaBinary.isNativeExecutable(file("pe", byteArrayOf(0x4D, 0x5A, 0x90.toByte(), 0x00))))
        assertTrue(JavaBinary.isNativeExecutable(file("macho", byteArrayOf(0xCF.toByte(), 0xFA.toByte(), 0xED.toByte(), 0xFE.toByte()))))
        assertTrue(JavaBinary.isNativeExecutable(file("fat", byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()))))
    }

    /** A launch about to hand over a token is the wrong place for a benefit of the doubt. */
    @Test
    fun `anything unreadable or absent answers no`() {
        assertFalse(JavaBinary.isNativeExecutable(workDir / "absent"))
        assertFalse(JavaBinary.isNativeExecutable(workDir))
        assertFalse(JavaBinary.isNativeExecutable(file("empty", ByteArray(0))))
        assertFalse(JavaBinary.isNativeExecutable(file("short", byteArrayOf(0x7F))))
    }
}

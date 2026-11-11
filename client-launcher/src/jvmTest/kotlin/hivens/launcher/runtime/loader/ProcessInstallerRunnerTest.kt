package hivens.launcher.runtime.loader

import hivens.core.api.interfaces.IJavaManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS as JunitOs
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProcessInstallerRunnerTest {

    private class FakeJava(private val java: Path) : IJavaManager {
        val asked = ArrayList<Int>()
        override suspend fun getJavaPath(version: String): Path = java
        override suspend fun getJavaPathForMajor(javaMajor: Int, onProgress: (String) -> Unit): Path {
            asked.add(javaMajor)
            return java
        }
    }

    private fun script(dir: Path, body: String): Path =
        dir.resolve("java").also {
            Files.writeString(it, "#!/bin/sh\n$body\n")
            it.toFile().setExecutable(true)
        }

    /**
     * The installer used to be waited on with a plain `waitFor` of up to twenty
     * minutes that no cancellation reached, so a stopped launch left it writing into
     * a cache the next launch then deleted. A shell script stands in for the JVM: it
     * marks that it started, sleeps, and marks that it finished, which it must not
     * get to do.
     */
    @Test
    @EnabledOnOs(JunitOs.LINUX, disabledReason = "the stand-in installer is a shell script")
    fun `a cancelled launch kills the installer instead of waiting for it`() = runBlocking {
        val dir = Files.createTempDirectory("runner-cancel")
        val target = Files.createDirectories(dir.resolve("neoforge-1.21.1-21.1.0"))
        val runner = ProcessInstallerRunner(FakeJava(script(dir, "touch started\nsleep 30\ntouch finished")))
        try {
            val job = launch(Dispatchers.IO) {
                runner.installClient(null, "1.21.1", dir.resolve("installer.jar"), target, "neoforge")
            }
            withTimeout(15_000) { while (!Files.exists(target.resolve("started"))) delay(50) }

            job.cancel()
            withTimeout(10_000) { job.join() }
            delay(1_500)

            assertFalse(Files.exists(target.resolve("finished")), "the installer outlived the launch that started it")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    @EnabledOnOs(JunitOs.LINUX, disabledReason = "the stand-in installer is a shell script")
    fun `an installer that fails reports its exit code and what it last said`() = runBlocking {
        val dir = Files.createTempDirectory("runner-fail")
        val target = Files.createDirectories(dir.resolve("out"))
        val runner = ProcessInstallerRunner(FakeJava(script(dir, "echo processor failed here\nexit 3")))
        try {
            val failure = assertFailsWith<IOException> {
                runner.installClient(8, "1.20.1", dir.resolve("installer.jar"), target, "forge")
            }
            val message = failure.message.orEmpty()
            assertTrue("exited 3" in message, message)
            assertTrue("processor failed here" in message, message)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    @EnabledOnOs(JunitOs.LINUX, disabledReason = "the stand-in installer is a shell script")
    fun `the Java asked for is the pinned major, else the one the Minecraft version uses`() = runBlocking {
        val dir = Files.createTempDirectory("runner-major")
        val target = Files.createDirectories(dir.resolve("out"))
        val java = FakeJava(script(dir, "exit 0"))
        val runner = ProcessInstallerRunner(java)
        try {
            runner.installClient(8, "1.20.1", dir.resolve("installer.jar"), target, "forge")
            runner.installClient(null, "1.21.1", dir.resolve("installer.jar"), target, "neoforge")
            assertEquals(listOf(8, 21), java.asked)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}

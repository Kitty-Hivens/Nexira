package hivens.launcher.runtime.loader

import hivens.core.api.interfaces.IJavaManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Runs the installer as a child process of a Java the manager provides. */
class ProcessInstallerRunner(private val javaManager: IJavaManager) : InstallerRunner {

    private val log = LoggerFactory.getLogger(ProcessInstallerRunner::class.java)

    override suspend fun installClient(
        javaMajor: Int?,
        mcVersion: String,
        installer: Path,
        dotMinecraft: Path,
        loaderId: String,
    ) = withContext(Dispatchers.IO) {
        val major = javaMajor ?: javaManager.detectJavaVersion(mcVersion)
        val java = javaManager.getJavaPathForMajor(major)
        runInstaller(java, installer, dotMinecraft, loaderId)
    }

    /**
     * Runs the installer and waits for it, for as long as the caller wants it.
     *
     * The wait is interruptible, and a cancelled one kills the installer and its
     * children on the way out. It used to park on a plain `waitFor` for up to
     * twenty minutes that no cancellation could reach, so a stopped launch left the
     * installer writing into a cache the next launch was about to delete.
     */
    private suspend fun runInstaller(java: Path, installer: Path, dotMinecraft: Path, loaderId: String) {
        // --installClient runs the installer's console (non-GUI) path;
        // headless=true keeps it from touching AWT on a display-less host.
        val command = listOf(
            java.toString(),
            "-Djava.awt.headless=true",
            "-jar",
            installer.toString(),
            "--installClient",
            dotMinecraft.toString(),
        )
        log.info("{}: running installer: {}", loaderId, command.joinToString(" "))
        // Runs on IO: the only caller is inside withContext(Dispatchers.IO).
        @Suppress("BlockingMethodInNonBlockingContext")
        val process = ProcessBuilder(command)
            .directory(dotMinecraft.toFile())
            .redirectErrorStream(true)
            .start()

        val tail = ArrayDeque<String>()
        val drain = Thread {
            try {
                process.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(tail) {
                        tail.addLast(line)
                        if (tail.size > INSTALLER_LOG_TAIL) tail.removeFirst()
                    }
                    log.debug("[{} installer] {}", loaderId, line)
                }
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true; start() }

        val finished = try {
            runInterruptible { process.waitFor(INSTALL_TIMEOUT_MINUTES, TimeUnit.MINUTES) }
        } catch (e: CancellationException) {
            log.info("{}: launch cancelled, stopping the installer", loaderId)
            kill(process)
            throw e
        }
        if (!finished) {
            kill(process)
            throw IOException("$loaderId installer timed out after $INSTALL_TIMEOUT_MINUTES min")
        }
        @Suppress("BlockingMethodInNonBlockingContext")
        drain.join(2000)
        if (process.exitValue() != 0) {
            val recent = synchronized(tail) { tail.joinToString("\n") }
            throw IOException("$loaderId installer exited ${process.exitValue()}:\n$recent")
        }
    }

    /**
     * Ends the installer and anything it started, and waits briefly for it to go.
     *
     * The parent goes first. Killing a child first hands the parent the moment
     * between the two signals to notice and carry on, and whatever it does next
     * lands in the cache. The children are listed before that, because an orphan
     * no longer shows up among the parent's descendants.
     */
    private fun kill(process: Process) {
        val children = runCatching { process.descendants().toList() }.getOrDefault(emptyList())
        val _ = runCatching { process.destroyForcibly() }
        children.forEach { val _ = runCatching { it.destroyForcibly() } }
        val _ = runCatching { process.waitFor(5, TimeUnit.SECONDS) }
    }

    private companion object {
        const val INSTALL_TIMEOUT_MINUTES = 20L
        const val INSTALLER_LOG_TAIL = 40
    }
}

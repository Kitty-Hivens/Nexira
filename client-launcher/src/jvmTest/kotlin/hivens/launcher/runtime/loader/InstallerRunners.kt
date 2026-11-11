package hivens.launcher.runtime.loader

import java.io.IOException
import java.nio.file.Path

/** For tests that never reach the installer: running it would be a mistake in the test. */
internal object NoInstallerRunner : InstallerRunner {
    override suspend fun installClient(javaMajor: Int?, mcVersion: String, installer: Path, dotMinecraft: Path, loaderId: String) {
        error("this test must not run an installer")
    }
}

/** Records the Java each install asked for, then stops before anything runs. */
internal class RecordingRunner : InstallerRunner {
    val asked = ArrayList<Int?>()

    override suspend fun installClient(javaMajor: Int?, mcVersion: String, installer: Path, dotMinecraft: Path, loaderId: String) {
        asked.add(javaMajor)
        throw IOException("stop before running anything")
    }
}

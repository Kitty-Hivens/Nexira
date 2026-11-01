package hivens.launcher.runtime.loader

import java.nio.file.Path

/**
 * Runs a loader's official installer, which needs a Java of its own and a process to
 * run it in. Forge from 1.13 and NeoForge cannot be installed without it, so a host
 * that cannot start such a process supplies none, and those loaders are then absent
 * from the registry instead of failing deep inside a launch.
 */
interface InstallerRunner {

    /**
     * Runs `--installClient` of the jar at [installer] into [dotMinecraft].
     *
     * [javaMajor] is the Java to run it under, or null for the one [mcVersion] itself
     * runs on. A caller that is cancelled stops the installer, and anything it started,
     * before this returns.
     */
    suspend fun installClient(javaMajor: Int?, mcVersion: String, installer: Path, dotMinecraft: Path, loaderId: String)
}

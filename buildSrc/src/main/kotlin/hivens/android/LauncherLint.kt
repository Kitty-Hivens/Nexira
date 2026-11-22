package hivens.android

import com.android.build.api.dsl.Lint
import java.io.File

/**
 * How Android Lint runs over a module of the launcher, the app and every library alike:
 * `lint { launcherLint(rootDir) }`.
 *
 * Warnings fail the build as errors do, the way the compiler's own warnings do here. A
 * report nobody has to act on is one nobody reads, and lint earns its place by what it
 * alone sees: a Java API that Android has only above the minimum API, which compiles
 * against the JDK and fails on the phone. The checks the project answers differently
 * are turned off in the root `lint.xml`, each with its reason.
 */
fun Lint.launcherLint(rootDir: File) {
    lintConfig = File(rootDir, "lint.xml")
    abortOnError = true
    warningsAsErrors = true
    sarifReport = true
}

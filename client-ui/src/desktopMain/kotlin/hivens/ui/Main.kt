package hivens.ui

import hivens.launcher.bootstrap.LogDirectory

/**
 * The launcher's entry point, for every way it starts: the AppImage's jar manifest, the jpackage
 * launchers and `./gradlew run` all name this class.
 *
 * Logback reads where to write once, when anything first asks for a logger, so the first line
 * names the log directory and the launcher starts after it. That is also why this file holds
 * nothing else. The JVM makes a file's top-level values while it initialises the class, before
 * `main` runs, so a value here that reached a logger, however indirectly, sent every log of the
 * session to the fallback folder in the temp directory. Everything else lives in [runLauncher].
 */
fun main(args: Array<String>) {
    LogDirectory.claim()
    runLauncher(args)
}

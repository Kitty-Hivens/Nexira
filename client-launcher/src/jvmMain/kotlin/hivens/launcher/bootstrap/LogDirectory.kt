package hivens.launcher.bootstrap

import hivens.launcher.platform.PlatformPaths
import java.util.UUID

/**
 * Where this process writes its logs, told to logback through the system properties its
 * logback.xml reads.
 *
 * Logback reads its configuration once, when anything in the process first asks for a logger, and
 * a property set after that is never seen: every log of the session then goes to the fallback
 * folder under java.io.tmpdir. So [claim] is the first statement of the entry point, and neither it
 * nor anything it reaches creates a logger. [PlatformPaths.system] only reads the environment, and
 * the one thing on that path that can log, a broken bootstrap conf, does so lazily and only when
 * NEXIRA_DATA_DIR is not set.
 */
object LogDirectory {

    /** The log directory, read by logback.xml as `LOGS_DIR`. */
    const val LOGS_DIR_PROPERTY: String = "nexira.logs.dir"

    /**
     * A short id on every log line of this process, so one launch can be told apart in a dump of
     * several. A system property rather than MDC, which is per thread.
     */
    const val SESSION_ID_PROPERTY: String = "nexira.sessionId"

    /**
     * Points the log files at the logs directory of [paths] and tags the session, and returns the
     * session id. The id is made on the first call and kept, so a later call from the boot pipeline
     * agrees with the one the entry point made.
     */
    @Synchronized
    fun claim(paths: PlatformPaths = PlatformPaths.system()): String {
        System.setProperty(LOGS_DIR_PROPERTY, paths.logsDir.toString())
        return System.getProperty(SESSION_ID_PROPERTY)
            ?: UUID.randomUUID().toString().take(8).also { System.setProperty(SESSION_ID_PROPERTY, it) }
    }
}

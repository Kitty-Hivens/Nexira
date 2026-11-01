package dev.hivens.nexira.host

import org.json.JSONArray
import org.json.JSONObject

/** The messages between the launcher's process and the process the game runs in. */
internal object GameIpc {
    /** Launcher to game: start the launch described by the spec file at [KEY_SPEC]. */
    const val MSG_START = 1

    /** Launcher to game: end the game politely, then by force. */
    const val MSG_TERMINATE = 2

    /** Game to launcher: one line of output, in [KEY_LINE]. */
    const val MSG_LINE = 3

    /** Game to launcher: the game is over, the code in arg1. */
    const val MSG_EXIT = 4

    const val KEY_SPEC = "spec"
    const val KEY_LINE = "line"

    /** Long enough for a modded client to run its shutdown hooks and save. */
    const val TERMINATE_GRACE_MS = 8_000L
}

/**
 * Everything the game's process needs to start, written to a file rather than carried
 * in the message: a classpath is long, and the file survives the launcher's process
 * being killed while the game's is not.
 */
internal data class LaunchSpec(
    val javaHome: String,
    val options: List<String>,
    val mainClass: String,
    val args: List<String>,
) {
    fun toJson(): String = JSONObject()
        .put("javaHome", javaHome)
        .put("options", JSONArray(options))
        .put("mainClass", mainClass)
        .put("args", JSONArray(args))
        .toString()

    companion object {
        fun fromJson(text: String): LaunchSpec {
            val json = JSONObject(text)
            fun strings(key: String) = json.getJSONArray(key).let { a -> List(a.length()) { a.getString(it) } }
            return LaunchSpec(json.getString("javaHome"), strings("options"), json.getString("mainClass"), strings("args"))
        }
    }
}

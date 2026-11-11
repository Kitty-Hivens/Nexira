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

    /** Launcher to game: the surface at [KEY_SURFACE], absent when it is gone. */
    const val MSG_SURFACE = 5

    /** Launcher to game: the display, in [KEY_WIDTH], [KEY_HEIGHT], [KEY_REFRESH] and [KEY_DENSITY]. */
    const val MSG_DISPLAY = 6

    /** Launcher to game: one input event, its kind in arg1 and its values in [KEY_A], [KEY_B], [KEY_META], [KEY_X], [KEY_Y], [KEY_Z], [KEY_SOURCE]. */
    const val MSG_INPUT = 7

    /** Game to launcher: how the game wants the cursor, in arg1: [CURSOR_NORMAL], 1 hidden or [CURSOR_CAPTURED]. */
    const val MSG_CURSOR_MODE = 8

    const val CURSOR_NORMAL = 0
    const val CURSOR_CAPTURED = 2

    const val INPUT_KEY = 1
    const val INPUT_TEXT = 2
    const val INPUT_POINTER_MOVE = 3
    const val INPUT_POINTER_BUTTON = 4
    const val INPUT_SCROLL = 5
    const val INPUT_RELATIVE = 6
    const val INPUT_TOUCH = 7
    const val INPUT_FOCUS = 8
    const val INPUT_CLOSE = 9

    /** Where an event came from, as the host core names it. */
    const val SOURCE_DEVICE = 0
    const val SOURCE_TOUCH = 1

    const val KEY_SPEC = "spec"
    const val KEY_LINE = "line"
    const val KEY_SURFACE = "surface"
    const val KEY_WIDTH = "width"
    const val KEY_HEIGHT = "height"
    const val KEY_REFRESH = "refresh"
    const val KEY_DENSITY = "density"
    const val KEY_A = "a"
    const val KEY_B = "b"
    const val KEY_META = "meta"
    const val KEY_X = "x"
    const val KEY_Y = "y"
    const val KEY_Z = "z"
    const val KEY_SOURCE = "source"

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
    /** The host core library of this launch, when the game draws a window. Empty for a program that does not. */
    val hostLibrary: String? = null,
    /** Libraries to open by path before the VM starts, so the game's open of them by name finds them. */
    val preload: List<String> = emptyList(),
    /** Variables to set in the process before the VM starts: a native library reads them once, when it loads. */
    val environment: Map<String, String> = emptyMap(),
    /** The directory the game runs in. */
    val workDir: String? = null,
) {
    fun toJson(): String = JSONObject()
        .put("javaHome", javaHome)
        .put("options", JSONArray(options))
        .put("mainClass", mainClass)
        .put("args", JSONArray(args))
        .put("hostLibrary", hostLibrary ?: JSONObject.NULL)
        .put("preload", JSONArray(preload))
        .put("environment", JSONObject(environment))
        .put("workDir", workDir ?: JSONObject.NULL)
        .toString()

    companion object {
        fun fromJson(text: String): LaunchSpec {
            val json = JSONObject(text)
            fun strings(key: String) = json.getJSONArray(key).let { a -> List(a.length()) { a.getString(it) } }
            return LaunchSpec(
                json.getString("javaHome"), strings("options"), json.getString("mainClass"), strings("args"),
                hostLibrary = json.optString("hostLibrary").takeIf { it.isNotEmpty() && it != "null" },
                preload = json.optJSONArray("preload")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList(),
                environment = json.optJSONObject("environment")?.let { o -> o.keys().asSequence().associateWith(o::getString) } ?: emptyMap(),
                workDir = json.optString("workDir").takeIf { it.isNotEmpty() && it != "null" },
            )
        }
    }
}

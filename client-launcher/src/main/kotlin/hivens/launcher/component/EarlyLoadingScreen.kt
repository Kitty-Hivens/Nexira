package hivens.launcher.component

import hivens.core.io.AtomicFiles
import hivens.core.platform.OS
import hivens.launcher.runtime.loader.ResolvedRuntime
import java.nio.file.Files
import java.nio.file.Path

/**
 * The window FML opens while mods load, before Minecraft has one of its own.
 *
 * It draws on its own thread with vsync forced on and holds a lock across every
 * buffer swap. When Minecraft takes the window over, FML waits one second for that
 * lock and ends the launch if it does not get it: "trouble handing off the window,
 * tried for 1 second". On Wayland a surface on a workspace nobody is looking at
 * gets no frame callbacks, so the swap blocks until the user comes back. A large
 * pack loads for a minute, and a minute is long enough to look away.
 *
 * Forge 1.20+ and NeoForge switch it with `earlyWindowControl` in the instance's
 * `config/fml.toml`, read before anything else and overridden by no system
 * property. Forge 1.13 to 1.19 had an older screen, switched by the
 * `fml.earlyprogresswindow` property instead, which [GameCommandBuilder] passes.
 */
object EarlyLoadingScreen {

    val waylandSession: Boolean = OS.isLinux && !System.getenv("WAYLAND_DISPLAY").isNullOrBlank()

    /**
     * Whether the screen will open for an instance whose choice is [choice].
     * [packValue] is what the pack's own config says, which is the answer only
     * when neither the player nor the session decides it.
     */
    fun effective(choice: Boolean?, packValue: Boolean?, waylandSession: Boolean = this.waylandSession): Boolean =
        enforced(choice, waylandSession) ?: packValue ?: true

    /**
     * The value a launch enforces, or null to leave the pack's own config alone.
     *
     * A choice the player made is enforced in both directions. Turning the screen
     * back on has to undo the `false` an earlier launch wrote, and a pack that
     * ships it off gets it on when the player asked for that.
     */
    fun enforced(choice: Boolean?, waylandSession: Boolean = this.waylandSession): Boolean? =
        choice ?: false.takeIf { waylandSession }

    /**
     * Whether [loaderName] on [mcVersion] has a screen of either kind.
     *
     * Forge had one from 1.13 to 1.16, which the system property switches, and
     * has had the current one since 1.20. From 1.17 to 1.19 it drew its progress
     * inside the game window, and before 1.13 it always did, so there is nothing
     * to hand off and nothing to switch.
     */
    fun appliesTo(loaderName: String?, mcVersion: String?): Boolean =
        when (loaderName?.trim()?.lowercase()) {
            "neoforge" -> true
            "forge" -> mcVersion != null && forgeHasScreen(mcVersion)
            else -> false
        }

    // Minecraft numbered itself 1.x until 26.1, so any leading number above 1 is newer.
    private fun forgeHasScreen(mcVersion: String): Boolean {
        val parts = mcVersion.trim().split('.')
        val major = parts.getOrNull(0)?.toIntOrNull() ?: return false
        if (major > 1) return true
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: return false
        return minor in 13..16 || minor >= 20
    }

    /**
     * Whether [runtime] carries the screen that `fml.toml` switches. It lives in a
     * library of its own, so its presence answers exactly, without a version table.
     */
    internal fun configurableIn(runtime: ResolvedRuntime): Boolean = runtime.libraries.any {
        (it.coord.group == "net.neoforged.fancymodloader" && it.coord.artifact == "earlydisplay") ||
            (it.coord.group == "net.minecraftforge" && it.coord.artifact == "fmlearlydisplay")
    }

    /**
     * Sets `earlyWindowControl` in [gameDir]'s `config/fml.toml` to [enabled] and
     * leaves every other line as it was. Returns whether the file changed.
     *
     * FML fills in whatever keys a file is missing and rewrites it, so a file
     * holding only this line is a valid one. What it does not survive is a file it
     * cannot parse, which fails the launch outright, so the write is atomic and a
     * missing key goes at the top: below a `[table]` header it would land inside
     * that table.
     */
    internal fun writeConfig(gameDir: Path, enabled: Boolean): Boolean {
        val file = configFile(gameDir)
        val doc = TomlLines.read(file)
        val at = doc.keyLine()
        val updated = if (at >= 0) {
            val m = KEY_LINE.matchEntire(doc.lines[at])!!
            if (m.groupValues[VALUE].trim() == enabled.toString()) return false
            // Only the value changes. The key keeps its spelling and indent, and a
            // comment after it stays where the pack put it.
            doc.lines.toMutableList().also { it[at] = m.groupValues[PREFIX] + enabled + m.groupValues[REST] }
        } else {
            listOf("$KEY = $enabled") + doc.lines
        }
        // Through a link rather than over it: a config shared between instances by
        // a symbolic link stays shared.
        val target = if (Files.isSymbolicLink(file)) file.toRealPath() else file
        AtomicFiles.writeString(target, doc.render(updated))
        return true
    }

    /**
     * `earlyWindowControl` as [gameDir]'s `config/fml.toml` has it, or null when
     * the file or the key is missing or the value is not a boolean. FML reads a
     * missing key as true.
     */
    fun readConfig(gameDir: Path): Boolean? {
        val file = configFile(gameDir)
        if (!Files.exists(file)) return null
        val doc = TomlLines.read(file)
        val at = doc.keyLine()
        if (at < 0) return null
        return KEY_LINE.matchEntire(doc.lines[at])!!.groupValues[VALUE].trim().toBooleanStrictOrNull()
    }

    private fun configFile(gameDir: Path): Path = gameDir.resolve("config").resolve("fml.toml")

    /**
     * A TOML file as lines, with what a line list loses kept aside: a byte-order
     * mark and the line ending the file uses. A pack written on Windows ships CRLF,
     * and rewriting it as LF turned one changed value into a whole-file diff.
     */
    private class TomlLines(val bom: Boolean, val eol: String, val lines: List<String>, val trailingEol: Boolean) {

        /** The top-level line that sets the key, or -1. A key below a `[table]` header is that table's. */
        fun keyLine(): Int {
            for ((i, line) in lines.withIndex()) {
                if (TABLE_HEADER.matches(line)) return -1
                if (KEY_LINE.matches(line)) return i
            }
            return -1
        }

        fun render(updated: List<String>): String =
            (if (bom) BOM else "") + updated.joinToString(eol) + (if (trailingEol || updated.isNotEmpty()) eol else "")

        companion object {
            fun read(file: Path): TomlLines {
                if (!Files.exists(file)) return TomlLines(bom = false, eol = "\n", lines = emptyList(), trailingEol = false)
                var text = Files.readString(file)
                val bom = text.startsWith(BOM)
                if (bom) text = text.removePrefix(BOM)
                val eol = if (text.contains("\r\n")) "\r\n" else "\n"
                val trailing = text.endsWith("\n")
                val body = if (trailing) text.removeSuffix("\n").removeSuffix("\r") else text
                val lines = if (body.isEmpty()) emptyList() else body.split(eol)
                return TomlLines(bom, eol, lines, trailing)
            }
        }
    }

    private const val KEY = "earlyWindowControl"
    private const val BOM = "﻿"

    /**
     * The key, bare or quoted either way, then its value, then whatever follows the
     * value. A quoted key is the same key to TOML, and missing it meant a second
     * definition was added above, which the parser refuses outright.
     */
    private val KEY_LINE = Regex("""^(\s*(["']?)$KEY\2\s*=\s*)([^#\r\n]*?)(\s*(?:#.*)?)$""")
    private const val PREFIX = 1
    private const val VALUE = 3
    private const val REST = 4
    private val TABLE_HEADER = Regex("""^\s*\[.*$""")
}

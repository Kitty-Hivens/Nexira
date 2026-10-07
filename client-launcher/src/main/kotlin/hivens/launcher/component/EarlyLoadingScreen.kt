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
     * Sets the screen for one launch, keeping what was there so [restore] can put
     * it back. Returns whether the file changed.
     *
     * The file is often the pack's own, and the pack's files are tracked by a
     * hash of the whole file. One line changed by the launcher reads, to an
     * update, as a player's edit, so the pack's next version of the file was set
     * aside as a conflict, and to a repair as damage, restored and rewritten on
     * every launch. Put back after the game, the file between sessions is the
     * pack's again, byte for byte.
     *
     * Anything left over from a session that ended without its restore is put
     * back first, so the copy kept now is the pack's and not the launcher's.
     */
    internal fun prepare(gameDir: Path, enabled: Boolean): Boolean {
        restore(gameDir)
        val file = configFile(gameDir)
        val existed = Files.exists(file)
        val original = if (existed) Files.readString(file) else ""
        val backup = backupFile(gameDir)
        // Written before the config, so a crash between the two leaves a backup
        // of a file that was never changed, which restores to itself.
        AtomicFiles.writeString(backup, "wrote=$enabled\nexisted=$existed\n$BACKUP_SEPARATOR\n$original")
        val changed = runCatching { writeConfig(gameDir, enabled) }
            .onFailure { Files.deleteIfExists(backup) }
            .getOrThrow()
        if (!changed) Files.deleteIfExists(backup)
        return changed
    }

    /**
     * Puts back what [prepare] replaced. Returns whether anything was put back.
     *
     * Only while the value it wrote is still there: a value that changed during
     * the session was changed by someone on purpose, and that stands. A file that
     * did not exist before goes again, since whatever is there now was made for
     * this launch.
     */
    fun restore(gameDir: Path): Boolean {
        val backup = backupFile(gameDir)
        if (!Files.exists(backup)) return false
        val text = Files.readString(backup)
        val head = text.substringBefore("\n$BACKUP_SEPARATOR\n", missingDelimiterValue = "")
        val original = text.substringAfter("\n$BACKUP_SEPARATOR\n", missingDelimiterValue = "")
        val fields = head.lines().associate { it.substringBefore('=') to it.substringAfter('=', "") }
        val wrote = fields["wrote"]?.toBooleanStrictOrNull()
        val existed = fields["existed"]?.toBooleanStrictOrNull()
        var restored = false
        if (wrote != null && existed != null && readConfig(gameDir) == wrote) {
            val file = configFile(gameDir)
            if (existed) {
                val target = if (Files.isSymbolicLink(file)) file.toRealPath() else file
                AtomicFiles.writeString(target, original)
            } else {
                Files.deleteIfExists(file)
            }
            restored = true
        }
        Files.deleteIfExists(backup)
        return restored
    }

    /**
     * [restore] for every instance under [instancesDir]: what a session left
     * behind when the launcher closed before the game did, or did not close at
     * all. A running game has read its config long before, so this is safe with
     * one still open.
     */
    fun restoreAll(instancesDir: Path): Int {
        if (!Files.isDirectory(instancesDir)) return 0
        return Files.list(instancesDir).use { dirs ->
            dirs.filter { Files.isDirectory(it) && Files.exists(backupFile(it)) }.toList()
        }.count { dir -> runCatching { restore(dir) }.getOrDefault(false) }
    }

    /** Beside the launcher's other per-instance files, where no pack puts anything. */
    private fun backupFile(gameDir: Path): Path = gameDir.resolve(".nexira-fml-toml")

    private const val BACKUP_SEPARATOR = "--- original ---"

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

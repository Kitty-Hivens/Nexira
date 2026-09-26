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
     * Whether [loaderName] on [mcVersion] has a screen of either kind. Legacy
     * Forge draws its progress inside the game window, so there is nothing to
     * hand off and nothing to switch.
     */
    fun appliesTo(loaderName: String?, mcVersion: String?): Boolean =
        when (loaderName?.trim()?.lowercase()) {
            "neoforge" -> true
            "forge" -> mcVersion != null && atLeast113(mcVersion)
            else -> false
        }

    // Minecraft numbered itself 1.x until 26.1, so any leading number above 1 is newer.
    private fun atLeast113(mcVersion: String): Boolean {
        val parts = mcVersion.trim().split('.')
        val major = parts.getOrNull(0)?.toIntOrNull() ?: return false
        if (major > 1) return true
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: return false
        return minor >= 13
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
        val file = gameDir.resolve("config").resolve("fml.toml")
        val wanted = "$KEY = $enabled"
        val lines = if (Files.exists(file)) Files.readAllLines(file) else emptyList()
        val firstTable = lines.indexOfFirst { TABLE_HEADER.matches(it) }.let { if (it < 0) lines.size else it }
        val at = lines.subList(0, firstTable).indexOfFirst { KEY_LINE.matches(it) }
        val updated = when {
            at >= 0 && lines[at].trim() == wanted -> return false
            at >= 0 -> lines.toMutableList().also { it[at] = wanted }
            else -> listOf(wanted) + lines
        }
        AtomicFiles.writeString(file, updated.joinToString("\n", postfix = "\n"))
        return true
    }

    /**
     * `earlyWindowControl` as [gameDir]'s `config/fml.toml` has it, or null when
     * the file or the key is missing or the value is not a boolean. FML reads a
     * missing key as true.
     */
    fun readConfig(gameDir: Path): Boolean? {
        val file = gameDir.resolve("config").resolve("fml.toml")
        if (!Files.exists(file)) return null
        val lines = Files.readAllLines(file)
        val line = lines.takeWhile { !TABLE_HEADER.matches(it) }.firstOrNull { KEY_LINE.matches(it) } ?: return null
        return line.substringAfter('=').substringBefore('#').trim().toBooleanStrictOrNull()
    }

    private const val KEY = "earlyWindowControl"
    private val KEY_LINE = Regex("""^\s*$KEY\s*=.*$""")
    private val TABLE_HEADER = Regex("""^\s*\[.*$""")
}

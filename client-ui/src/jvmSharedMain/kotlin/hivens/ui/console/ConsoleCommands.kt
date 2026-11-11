package hivens.ui.console

import hivens.core.diag.MemoryReport
import hivens.ui.utils.GameConsoleService
import hivens.ui.utils.LogType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * One line the console understands when no game owns the input row.
 *
 * [run] is handed a sink rather than the console service so a command is a pure
 * function of what it prints, which is what the tests assert against.
 */
data class ConsoleCommand(
    val name: String,
    val summary: String,
    val aliases: List<String> = emptyList(),
    /** Kept out of `help`. Discovering it is the point. */
    val listed: Boolean = true,
    /**
     * Whether the body may leave the thread that typed it. The console's whole
     * design is that nothing blocks the UI thread, and reading `/proc` or forcing
     * a collection on it would put the blocking back. False belongs to the
     * commands that touch Compose state and must stay where the composition is.
     */
    val offThread: Boolean = true,
    val run: (print: (String, LogType) -> Unit) -> Unit,
) {
    /** Every spelling that reaches this command, as the router stores them. */
    val keys: List<String> get() = (listOf(name) + aliases).map { it.trim().lowercase() }
}

/**
 * The launcher's own console commands, declared in one table instead of being
 * sprinkled over the shell.
 *
 * [GameConsoleService.submitConsoleInput] matches the whole typed line against
 * its registry, so a multi-word name is a name and needs no argument parser:
 * "mem full" is simply a second entry. That is also why the router is left
 * alone here.
 *
 * The memory commands exist because the numbers that matter are host-dependent.
 * Heap ceiling, collector thread count and region size are all derived from the
 * machine's RAM and cores, so a measurement taken on one computer says little
 * about anyone else's, and the person who can answer "is this build heavy on
 * your hardware" is the person running it.
 */
object ConsoleCommands {

    private val log = LoggerFactory.getLogger("ConsoleCommands")

    /**
     * [debugOverlayToggle] is null on a build without the debug overlay, and its
     * command is then absent rather than present and inert.
     *
     * [launcherIsBusy] and [restartWorld] are passed in rather than reached for
     * so the commands stay callable from a test that would rather not end the
     * process it is running in. [restartWorld] returns false when there is no
     * relaunchable binary, and on success it does not return at all.
     *
     * [launcherIsBusy] must cover the whole launch, not just a game that is
     * already up. The console's command sink attaches when the process spawns,
     * so a check built on it would call a two gigabyte install "idle" and let the
     * restart take the half-written pack directory with it.
     */
    fun builtIn(
        debugOverlayToggle: (() -> Unit)? = null,
        launcherIsBusy: () -> Boolean = { false },
        restartWorld: () -> Boolean = { false },
    ): List<ConsoleCommand> = buildList {
        add(
            ConsoleCommand(
                name = "help",
                summary = "list what the console understands",
            ) { print ->
                val listed = builtIn(debugOverlayToggle, launcherIsBusy, restartWorld).filter { it.listed }
                val width = listed.maxOf { it.name.length }
                print("console commands", LogType.INFO)
                listed.forEach { print("  ${it.name.padEnd(width)}  ${it.summary}", LogType.INFO) }
                print("  not everything the console answers to is on this list", LogType.INFO)
            },
        )
        add(
            ConsoleCommand(
                name = "mem",
                summary = "what this process costs on this machine",
            ) { print ->
                MemoryReport.summaryLines(MemoryReport.collect()).forEach { print(it, LogType.INFO) }
            },
        )
        add(
            ConsoleCommand(
                name = "mem full",
                summary = "the same, broken down by mapping and by jvm category",
            ) { print ->
                MemoryReport.fullLines(MemoryReport.collect(withNativeCategories = true))
                    .forEach { print(it, LogType.INFO) }
            },
        )
        add(
            ConsoleCommand(
                name = "mem gc",
                summary = "collect, then report what survived",
            ) { print ->
                val (before, after, pauseMs) = MemoryReport.collectAfterGc()
                print("collected in ${pauseMs} ms of stopped time", LogType.INFO)
                print("  heap used      ${before.heap.usedMb} MB -> ${after.heap.usedMb} MB", LogType.INFO)
                print("  heap committed ${before.heap.committedMb} MB -> ${after.heap.committedMb} MB", LogType.INFO)
                after.rssMb?.let { rss ->
                    print("  resident       ${before.rssMb ?: rss} MB -> $rss MB", LogType.INFO)
                }
                print("  ${after.heap.usedMb} MB is what the launcher actually holds", LogType.INFO)
            },
        )
        add(
            ConsoleCommand(
                name = "stop the reality!",
                summary = "",
                aliases = listOf("stop the reality", "stop the world"),
                listed = false,
            ) { print ->
                val (before, after, pauseMs) = MemoryReport.collectAfterGc()
                val reclaimed = (before.heap.usedMb - after.heap.usedMb).coerceAtLeast(0)
                print("reality stopped for $pauseMs ms", LogType.WARN)
                print("  ${after.heap.usedMb} MB survived, $reclaimed MB did not", LogType.INFO)
                print("reality resumed. the world is concurrent again", LogType.INFO)
            },
        )
        add(
            ConsoleCommand(
                name = "stop the everything!",
                summary = "",
                aliases = listOf("stop everything", "restart the reality"),
                listed = false,
            ) { print ->
                when {
                    // A launch owns this process from the first prepare step: the
                    // download, the hashing and the unpack all run here, and a
                    // restart in the middle of them leaves a half written pack
                    // behind. The game itself would survive, its bookkeeping
                    // would not.
                    launcherIsBusy() -> {
                        print("a launch is in progress. reality is load bearing right now", LogType.WARN)
                    }
                    else -> {
                        print("stopping everything. reality restarts in a moment", LogType.WARN)
                        if (!restartWorld()) {
                            print("reality is not relaunchable from here, so it stays as it is", LogType.INFO)
                        }
                    }
                }
            },
        )
        if (debugOverlayToggle != null) {
            add(
                ConsoleCommand(
                    name = "uidebug",
                    summary = "toggle the ui debug overlay (same as F9)",
                    aliases = listOf("ui-debug"),
                    offThread = false,
                ) { _ -> debugOverlayToggle() },
            )
        }
    }

    /**
     * Register every command against the console's local router.
     *
     * The router runs a handler on the thread that submitted the line, which is
     * the UI thread, so anything that reads `/proc` or asks for a collection is
     * dispatched to [scope] instead. [GameConsoleService.append] is a
     * non-blocking enqueue from any thread, so the output ordering survives the
     * move.
     */
    fun registerAll(
        console: GameConsoleService,
        scope: CoroutineScope,
        debugOverlayToggle: (() -> Unit)?,
        launcherIsBusy: () -> Boolean,
        restartWorld: () -> Boolean,
    ) {
        builtIn(debugOverlayToggle, launcherIsBusy, restartWorld).forEach { command ->
            val print: (String, LogType) -> Unit = { text, type -> console.append(text, type) }
            command.keys.forEach { key ->
                console.registerLocalCommand(key) {
                    if (command.offThread) {
                        scope.launch(Dispatchers.IO) { runReporting(command, print) }
                    } else {
                        runReporting(command, print)
                    }
                }
            }
        }
    }

    /**
     * A command that throws must say so in the console. Off the UI thread the
     * failure would otherwise reach only the scope's exception handler, and the
     * person who typed the line would watch their echo and then nothing at all.
     */
    internal fun runReporting(command: ConsoleCommand, print: (String, LogType) -> Unit) {
        try {
            command.run(print)
        } catch (e: Throwable) {
            print("${command.name} failed: ${e::class.simpleName}: ${e.message}", LogType.ERROR)
            log.warn("Console command {} failed", command.name, e)
        }
    }
}

package hivens.cli

import hivens.config.Branding
import hivens.core.api.interfaces.ICredentialStore
import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.OfflineIdentity
import hivens.core.data.PackInstance
import hivens.core.data.SessionData
import hivens.core.launch.LaunchError
import hivens.core.launch.LaunchLogEvent
import hivens.core.launch.LaunchState
import hivens.launcher.bootstrap.LauncherBootstrap
import hivens.launcher.launch.LauncherController
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.koin.core.Koin
import org.koin.core.context.GlobalContext
import kotlin.system.exitProcess

/**
 * Headless, Compose-free entrypoint over the launch pipeline. Built to a
 * GraalVM / Liberica-NIK native binary (see docs/native-image.md); the GUI
 * (:client-ui) stays on the JVM because Skiko/AWT blocks native-image of
 * Compose. Reuses the existing launcher core wholesale -- [LauncherBootstrap]
 * for logging/paths/Koin, [LauncherController] for the launch state machine --
 * and adds only argument parsing and stdout rendering on top.
 */
fun main(args: Array<String>) {
    // Belt-and-suspenders for the AWT-free guarantee. The launcher core is
    // structured so no AWT/Swing class is reachable from preBootHeadless, but
    // a stray transitive headful init would still be forced headless here.
    System.setProperty("java.awt.headless", "true")

    val code = when (val cmd = parseArgs(args)) {
        is CliCommand.Help -> { println(USAGE); 0 }
        is CliCommand.Version -> { printVersion(); 0 }
        is CliCommand.Invalid -> {
            System.err.println(cmd.message)
            System.err.println()
            System.err.println(USAGE)
            2
        }
        is CliCommand.ListPacks -> runListPacks()
        is CliCommand.Launch -> runLaunch(cmd)
    }
    exitProcess(code)
}

private fun printVersion() {
    println("${Branding.TITLE} ${Branding.VERSION}")
}

/** Boots the AWT-free launcher core once and returns the started Koin context. */
private fun bootLauncher(): Koin {
    LauncherBootstrap.preBootHeadless()
    return GlobalContext.get()
}

private fun runListPacks(): Int {
    val koin = bootLauncher()
    val packs = runBlocking { koin.get<IPackRepository>().list() }
    if (packs.isEmpty()) {
        println("No installed pack instances. Install one from the GUI, then 'nexira-cli launch <id>'.")
        return 0
    }
    println("Installed pack instances (${packs.size}):")
    packs.sortedBy { it.displayName.lowercase() }.forEach { p ->
        val ref = p.packRef
        val source = "${ref.origin} ${ref.id}" + (ref.version?.let { " @$it" } ?: "")
        val runtime = p.cachedManifest?.let {
            "MC ${it.minecraftVersion} / ${it.loaderName} ${it.loaderVersion} / Java ${it.javaMajor}"
        } ?: "manifest not cached"
        println("  - ${p.displayName}")
        println("      id:      ${p.id}")
        println("      source:  $source")
        println("      runtime: $runtime")
    }
    return 0
}

private fun runLaunch(cmd: CliCommand.Launch): Int {
    val koin = bootLauncher()
    val instance = runBlocking { koin.get<IPackRepository>().get(cmd.packId) }
    if (instance == null) {
        System.err.println("No installed pack instance with id '${cmd.packId}'. Run 'nexira-cli list'.")
        return 2
    }

    val session = resolveSession(koin, cmd) ?: return 2

    if (cmd.dryRun) {
        printPlan(instance, session)
        return 0
    }

    val controller = koin.get<LauncherController>()
    return runBlocking {
        // Console output: the controller emits semantic events + game stdout
        // on `events`; render to stdout. Cancelled once a terminal state lands.
        val output = launch {
            controller.events.collect { println(renderEvent(it)) }
        }
        // Coarse progress: dedup consecutive same-class states so the per-byte
        // Downloading storm does not flood the terminal.
        var lastLabel: String? = null
        val progress = launch {
            controller.state.collect { st ->
                coarseLabel(st)?.takeIf { it != lastLabel }?.let { lastLabel = it; println("[$it]") }
            }
        }

        controller.launchPackInstance(session, instance)

        // launchInternal sets state to Prepare synchronously before returning,
        // so the current value is already non-Idle; first{} then waits for the
        // terminal Idle (clean exit) or Error.
        val terminal = controller.state.first { it is LaunchState.Idle || it is LaunchState.Error }
        output.cancel()
        progress.cancel()

        when (terminal) {
            is LaunchState.Error -> {
                System.err.println("Launch failed: ${renderError(terminal.reason)}")
                1
            }
            else -> 0
        }
    }
}

private fun resolveSession(koin: Koin, cmd: CliCommand.Launch): SessionData? {
    return when (cmd.provider) {
        "offline" -> {
            val name = cmd.user ?: "Player"
            SessionData(
                playerName = name,
                uuid = OfflineIdentity.dashlessUuidFor(name),
                accessToken = "",
                offline = true,
            )
        }
        else -> {
            // smartycraft / microsoft: reuse the session the GUI stored in the
            // keyring; the controller re-auths SC-bound packs pre-spawn.
            val store = koin.get<ICredentialStore>()
            val stored = store.accountFor(cmd.provider) ?: store.load()
            if (stored == null) {
                System.err.println(
                    "No stored '${cmd.provider}' account. Sign in via the GUI first, then retry.",
                )
                null
            } else {
                cmd.user?.let { stored.copy(playerName = it) } ?: stored
            }
        }
    }
}

private fun printPlan(instance: PackInstance, session: SessionData) {
    val m = instance.cachedManifest
    println("Launch plan (dry-run, no game spawned):")
    println("  instance: ${instance.displayName} (${instance.id})")
    println("  source:   ${instance.packRef.origin} ${instance.packRef.id}")
    println("  player:   ${session.playerName}${if (session.offline) " (offline)" else ""}")
    if (m != null) {
        println("  runtime:  MC ${m.minecraftVersion} / ${m.loaderName} ${m.loaderVersion} / Java ${m.javaMajor}")
        println("  auth:     ${m.authRequirement ?: "none"}")
    } else {
        println("  runtime:  manifest not cached -- a real launch resolves it from the mirror")
    }
}

private fun renderEvent(event: LaunchLogEvent): String = when (event) {
    is LaunchLogEvent.ProcessOutput -> event.text
    LaunchLogEvent.AppBanner -> "${Branding.TITLE} starting..."
    is LaunchLogEvent.TargetServer -> "-> ${event.name}" + if (event.offline) " (offline)" else ""
    is LaunchLogEvent.SessionStarted -> "session: ${event.targetLabel ?: event.targetId ?: "?"}"
    LaunchLogEvent.OfflineSkipAuth -> "(offline: skipping auth)"
    is LaunchLogEvent.AuthSucceeded -> "auth ok (${event.uuid})"
    LaunchLogEvent.NoPassword -> "(no cached password; offline fallback)"
    // The cause decides what a stale session means here too: launching on it is
    // hopeful after Unreachable and hopeless after Rejected.
    is LaunchLogEvent.AuthFailed ->
        "auth failed (${event.cause.name.lowercase()}): ${event.message ?: "unknown error"}"
    is LaunchLogEvent.ForeignContentRemoved ->
        "removed ${event.paths.size} file(s) absent from the pack: ${event.paths.joinToString(", ")}"
    LaunchLogEvent.InstanceUnverified ->
        "instance not verified (no roster on disk); launching without a token -- sync the pack"
    LaunchLogEvent.TwoFactorDetected -> "(2FA account detected; no silent re-login from here)"
    LaunchLogEvent.OfflineSkipSync -> "(offline: skipping file sync)"
    LaunchLogEvent.Launching -> "launching game process..."
    is LaunchLogEvent.Error -> "error: ${renderError(event.reason)}"
}

private fun renderError(reason: LaunchError): String = when (reason) {
    is LaunchError.ExitCode -> "game exited with code ${reason.code}"
    is LaunchError.Internal -> "internal error: ${reason.message}"
    LaunchError.OfflineNoClient -> "no installed client directory (install/sync the pack first)"
    LaunchError.OfflineNoManifest -> "no cached manifest from a prior online run"
    LaunchError.TwoFactorExpired -> "2FA session expired -- re-login via the GUI"
    is LaunchError.HelperUnavailable -> "open-smrt helper unavailable for MC ${reason.mcVersion}"
    is LaunchError.AuthlibUnavailable -> "SmartyCraft authlib unavailable for MC ${reason.mcVersion}"
    is LaunchError.MissingAuthProvider -> "sign in with '${reason.providerKey}' to play this pack (use the GUI)"
}

private fun coarseLabel(state: LaunchState): String? = when (state) {
    is LaunchState.Prepare -> "prepare:${state.stage}"
    is LaunchState.Downloading -> "downloading"
    is LaunchState.GameRunning -> "running"
    LaunchState.Idle, is LaunchState.Error -> null
}

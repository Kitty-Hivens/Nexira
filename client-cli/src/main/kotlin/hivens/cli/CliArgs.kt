package hivens.cli

/**
 * Parsed CLI command. Pure data, no effects -- so [parseArgs] is unit-testable
 * without booting the launcher.
 */
sealed interface CliCommand {
    data object Help : CliCommand
    data object Version : CliCommand
    data object ListPacks : CliCommand

    data class Launch(
        val packId: String,
        /** "offline" | "smartycraft" | "microsoft". */
        val provider: String,
        val user: String?,
        val dryRun: Boolean,
    ) : CliCommand

    /** Parse failure; [message] explains what was wrong. */
    data class Invalid(val message: String) : CliCommand
}

private val PROVIDERS = setOf("offline", "smartycraft", "microsoft")

/**
 * Hand-rolled argument parser -- zero dependencies, the friendliest shape for
 * native-image (no reflection-driven CLI framework to register metadata for).
 */
fun parseArgs(args: Array<String>): CliCommand {
    if (args.isEmpty()) return CliCommand.Help
    return when (val cmd = args[0]) {
        "help", "--help", "-h" -> CliCommand.Help
        "version", "--version", "-v" -> CliCommand.Version
        "list" -> CliCommand.ListPacks
        "launch" -> parseLaunch(args.drop(1))
        else -> CliCommand.Invalid("Unknown command: $cmd")
    }
}

private fun parseLaunch(rest: List<String>): CliCommand {
    var packId: String? = null
    var provider = "offline"
    var user: String? = null
    var dryRun = false

    var i = 0
    while (i < rest.size) {
        when (val a = rest[i]) {
            "--provider" -> {
                provider = rest.getOrNull(++i) ?: return CliCommand.Invalid("--provider needs a value")
            }
            "--user" -> {
                user = rest.getOrNull(++i) ?: return CliCommand.Invalid("--user needs a value")
            }
            "--dry-run" -> dryRun = true
            else -> {
                if (a.startsWith("-")) return CliCommand.Invalid("Unknown flag: $a")
                if (packId != null) return CliCommand.Invalid("Unexpected extra argument: $a")
                packId = a
            }
        }
        i++
    }

    if (packId.isNullOrBlank()) return CliCommand.Invalid("launch requires a <packId> (see 'nexira-cli list')")
    if (provider !in PROVIDERS) {
        return CliCommand.Invalid("--provider must be one of ${PROVIDERS.joinToString("|")}")
    }
    return CliCommand.Launch(packId = packId, provider = provider, user = user, dryRun = dryRun)
}

const val USAGE: String = """nexira-cli -- headless Nexira launcher

Usage:
  nexira-cli <command> [options]

Commands:
  list                       List installed pack instances.
  launch <packId> [options]  Launch a pack instance by id.
  version                    Print the launcher version.
  help                       Show this help.

Launch options:
  --provider <p>   Auth provider: offline (default) | smartycraft | microsoft.
                   smartycraft/microsoft reuse the account stored by the GUI.
  --user <name>    Player name. Required shape for offline; overrides the
                   stored account name otherwise.
  --dry-run        Resolve and print the launch plan without spawning the game.
"""

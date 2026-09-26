package hivens.launcher.component

import hivens.config.Branding
import hivens.config.Protocol
import hivens.core.data.SessionData
import hivens.core.logging.Redactor
import hivens.core.platform.OS
import hivens.launcher.network.ServerProtocolConfig
import hivens.launcher.runtime.loader.ResolvedRuntime
import hivens.launcher.security.JvmArgPolicy
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Path

internal class GameCommandBuilder(
    private val protocolConfig: ServerProtocolConfig = ServerProtocolConfig(),
) {
    private val logger = LoggerFactory.getLogger(GameCommandBuilder::class.java)


    /**
     * The half of [EarlyLoadingScreen] that lives on the command line: Forge 1.13
     * to 1.19 reads this property. Later loaders ignore it and are switched
     * through `fml.toml` by the launch path.
     */
    private fun addEarlyWindowGuard(args: MutableList<String>, earlyLoadingScreen: Boolean?) {
        if (earlyLoadingScreen == false) args.add("-Dfml.earlyprogresswindow=false")
    }

    /**
     * Per-instance natives directory. Works for any Minecraft version: the pack
     * runtime is resolved generically, so the natives folder is just the
     * conventional `bin/natives-<version>`.
     */
    fun packNativesDir(mcVersion: String): String = "bin/natives-$mcVersion"

    /**
     * Closes the JVM's attach listener for a launch carrying a session token.
     *
     * Without it, any process running as the same user loads an agent into the
     * live game through the attach socket -- `jattach`, `jcmd`, the Attach API
     * -- which needs no cooperation from the launcher at all, since it happens
     * after the command line stopped mattering. Placed after the user's own
     * arguments so it wins on order as well as by policy, HotSpot taking the
     * last occurrence of a flag.
     *
     * The cost is real and worth naming: a thread dump of the game (`jstack`,
     * `jcmd`) stops working, so a hang in someone's bug report is harder to
     * read. It buys the convenient half of runtime injection; ptrace and
     * `/proc/pid/mem` are not addressable from in here and are not pretended to
     * be.
     */
    private fun addAttachGuard(args: MutableList<String>, restrict: Boolean) {
        if (restrict) args.add("-XX:+DisableAttachMechanism")
    }

    /**
     * Splits and, for a launch that will carry a token, filters the user's own
     * JVM arguments through [JvmArgPolicy]. What is refused is logged rather
     * than dropped in silence -- a flag that quietly stops applying reads as the
     * launcher being broken.
     */
    private fun userJvmArgs(raw: String?, restrict: Boolean): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        if (!restrict) return raw.trim().split(Regex("\\s+"))
        val result = JvmArgPolicy.filter(raw)
        if (result.refused.isNotEmpty()) {
            logger.warn(
                "Refused {} JVM argument(s) on a server-bound launch: {}",
                result.refused.size, result.refused,
            )
        }
        return result.kept
    }

    /**
     * Profile-driven command for a pack-centric launch. Everything that varies
     * by loader -- main class, classpath, jvm/game args (e.g. the FML tweak) --
     * comes from the resolved [runtime]. Assets and libraries come from the
     * SHARED roots; natives stay per-instance.
     *
     * Handles all three launch eras:
     * - launchwrapper / Knot (vanilla, Forge <=1.12.2, Fabric, Quilt): plain
     *   `-cp` + main class + game args; no arg templating.
     * - modlauncher Forge 1.13-1.16: the modern `arguments` template (inherited
     *   from vanilla) with `${...}` substitution, but launched off the classpath
     *   with no JPMS module path.
     * - BootstrapLauncher (Forge 1.17+, all NeoForge): same templating plus the
     *   module path (`-p`) carried in the version json's jvm args.
     *
     * [javaMajor] decides `-noverify` (legitimate on Java 8, warns on 17+).
     * [sharedLibrariesDir] backs the `${library_directory}` placeholder.
     */
    fun buildPackCommand(
        javaExec: String,
        memoryMB: Int,
        gameDir: Path,
        sharedAssetsDir: Path,
        sharedLibrariesDir: Path,
        nativesDirName: String,
        versionLabel: String,
        javaMajor: Int,
        runtime: ResolvedRuntime,
        session: SessionData,
        jvmArgsOverride: String?,
        redirectAuthHost: Boolean = true,
        // Hold the user's own JVM arguments to what a launch carrying a session
        // token may pass on. Same partition as the roster sweep and the
        // environment seal: a pack with no server binding is its owner's game.
        restrictJvmArgs: Boolean = true,
        agentJarPath: Path? = null,
        metricsOutPath: Path? = null,
        authlibAgentJarPath: Path? = null,
        windowWidth: Int? = null,
        windowHeight: Int? = null,
        fullScreen: Boolean = false,
        // What EarlyLoadingScreen.enforced answered: false emits the property,
        // true and null leave the loader's default.
        earlyLoadingScreen: Boolean? = null,
    ): List<String> {
        val args = ArrayList<String>()
        args.add(javaExec)

        // BootstrapLauncher (1.17+/NeoForge) carries a JPMS module path; the
        // modern `arguments` template (anything with a ${placeholder}) needs
        // substitution and a self-built classpath -- modlauncher 1.13-1.16 has
        // the template but no module path.
        val usesModulePath = runtime.mainClass.contains("bootstraplauncher", ignoreCase = true)
        val usesModernArgs = usesModulePath || runtime.jvmArgs.any { it.contains($$"${") }
        if (javaMajor <= 8) args.add("-noverify")
        addMacOsStartupFlags(args)

        // authlib redirect: point every era's host set at the SC backend so
        // joining an SC/mirror-derived server authenticates there. Legacy
        // auth/account flows live under /launcher/; the SESSION
        // service -- the join -- lives at the BARE host over plain http (SC's own
        // patched authlib hardcodes http://<host>; https and /launcher/ both
        // 404). Modern authlib (1.16.4+) additionally IGNORES the redirect
        // unless session AND services are both set -- it then joins against PROD
        // Mojang and the SC server kicks the session as invalid. Mirror-derived
        // packs ONLY -- a Modrinth / local / own pack keeps the default Mojang
        // hosts so its own auth provider (e.g. real Yggdrasil) is never
        // redirected to the mirror.
        if (redirectAuthHost) {
            args.add("-Dminecraft.api.auth.host=${protocolConfig.baseUrl}/launcher/")
            args.add("-Dminecraft.api.account.host=${protocolConfig.baseUrl}/launcher/")
            args.add("-Dminecraft.api.session.host=http://${protocolConfig.sslBypassHost}")
            args.add("-Dminecraft.api.services.host=http://${protocolConfig.sslBypassHost}")
        }
        args.add("-Dminecraft.launcher.brand=${Branding.UPSTREAM_NAME}")
        args.add("-Dminecraft.launcher.version=${Protocol.MIMIC_LAUNCHER_VERSION}")

        val nativesPath = gameDir.resolve(nativesDirName).toAbsolutePath()
        args.add("-Djava.library.path=$nativesPath")
        args.add("-Dfml.ignoreInvalidMinecraftCertificates=true")
        addEarlyWindowGuard(args, earlyLoadingScreen)

        args.addAll(userJvmArgs(jvmArgsOverride, restrictJvmArgs))
        addAttachGuard(args, restrictJvmArgs)
        if (usesModernArgs) {
            args.addAll(modernJvmArgs(runtime, gameDir, sharedAssetsDir, sharedLibrariesDir, nativesPath, versionLabel))
            // Java 9+ Vector API speeds up some mods (JEI, Ars Nouveau); only
            // meaningful where the module path is in play.
            if (usesModulePath) args.add("--add-modules=jdk.incubator.vector")
        } else {
            args.addAll(runtime.jvmArgs)
        }
        args.add("-Xms${minOf(memoryMB, 512)}M")
        args.add("-Xmx${memoryMB}M")
        addProfilerArgs(args, agentJarPath, metricsOutPath)
        addAuthlibAgentArg(args, authlibAgentJarPath)

        args.add("-cp")
        args.add(if (usesModernArgs) modernClasspath(runtime) else packClasspath(runtime))
        args.add(runtime.mainClass)

        args.add("--username"); args.add(session.playerName)
        args.add("--version"); args.add(versionLabel)
        args.add("--gameDir"); args.add(gameDir.toAbsolutePath().toString())
        args.add("--assetsDir"); args.add(sharedAssetsDir.toAbsolutePath().toString())
        args.add("--assetIndex"); args.add(runtime.assetIndexId)
        addSessionAuthArgs(args, session)
        addWindowArgs(args, windowWidth, windowHeight, fullScreen)
        args.addAll(runtime.gameArgs)

        return args
    }

    /**
     * Optional game-window geometry. Fullscreen wins (the client ignores an
     * explicit size in that mode); otherwise a non-null width/height emits
     * `--width`/`--height`. A null size means "keep the client's own remembered
     * size" -- the pack path only passes a value when the instance opted into a
     * window-size override, so an untouched instance launches unchanged.
     */
    private fun addWindowArgs(args: MutableList<String>, width: Int?, height: Int?, fullScreen: Boolean) {
        if (fullScreen) {
            args.add("--fullscreen")
            return
        }
        if (width != null && width > 0) { args.add("--width"); args.add(width.toString()) }
        if (height != null && height > 0) { args.add("--height"); args.add(height.toString()) }
    }

    /**
     * Ordered `-cp` for a pack: the libraries in the order the loader declared
     * them, with the client jar inserted right after the last bootstrap jar
     * (launchwrapper / asm / bootstraplauncher / foundation), which is what
     * keeps the bootstrap ahead of the client the way the legacy Forge path
     * always did. `foundation` is Cleanroom's launchwrapper replacement, whose
     * `Foundation` bootstrap starts FMLTweaker, so it counts as one. Mods stay
     * off this list. The loader scans the per-instance mods/ dir for them.
     *
     * Only the client jar moves, and that matters. Hoisting the bootstrap jars
     * to the front instead, which is what this did before, also lifted them over
     * loader jars declared ahead of them, and classpath order decides which
     * duplicate RESOURCE wins as much as which class. Three jars in a 1.12.2
     * launch carry a root `log4j2.xml`: the vanilla client, the loader core
     * (forge universal, cleanroom) and Cleanroom's foundation. So the hoist was
     * choosing the logging config, and it chose wrong both times. For Cleanroom
     * it picked foundation's, whose pattern calls a `%rgbFormat` converter that
     * nothing in the runtime registers, so log4j read `%r` as "millis since
     * start", left `gbFormat` as a literal and dropped the message. That pattern
     * also keeps its `%n` inside the missing converter, which is why stdout
     * arrived without a single newline and [LineAssembler] had to fall back to
     * splitting on the record header. For legacy Forge it picked the vanilla
     * client's, which carries neither the `[%logger]` field nor the
     * `forge.logging.*` levels. Declared order hands each loader the config it
     * ships.
     */
    private fun packClasspath(runtime: ResolvedRuntime): String {
        val libPaths = runtime.libraries.map { it.path }
        // A vanilla runtime has no bootstrap jar at all, so indexOfLast answers
        // -1 and the client jar lands at the head of the classpath.
        val afterBootstrap = libPaths.indexOfLast { isBootstrapJar(it) } + 1
        // listOf(clientJar), NOT `+ clientJar`: a Path is Iterable<Path> over its
        // name segments, so `List<Path> + Path` would spread the client jar into
        // its path components instead of appending it as one classpath entry.
        val ordered = libPaths.take(afterBootstrap) +
            listOf(runtime.clientJar) +
            libPaths.drop(afterBootstrap)
        return ordered.joinToString(File.pathSeparator) { it.toAbsolutePath().toString() }
    }

    /** A jar the loader boots through before Minecraft's own classes are touched. */
    private fun isBootstrapJar(path: Path): Boolean {
        val name = path.fileName.toString().lowercase()
        return name.contains("launchwrapper") || name.contains("asm") ||
            name.contains("bootstraplauncher") || name.contains("foundation")
    }

    /**
     * Full `-cp` for a modern (templated) launch: the resolved libraries only,
     * NOT the class-bearing client jar. Modern Forge/NeoForge load minecraft from
     * the installer's processor output (the slim/srg client) through FML's own path
     * locator; putting the class-bearing client on `-cp` too yields a second module
     * named `minecraft` and "reads more than one module named minecraft". Boot
     * modules stay on `-cp` -- the version json's `-DignoreList` tells
     * BootstrapLauncher which entries to keep flat versus promote to modules,
     * mirroring the official launcher.
     *
     * The resources-only client jar ([ResolvedRuntime.clientResourcesJar], the
     * installer's `-extra` output) IS appended: it carries `version.json` but no
     * classes, so it restores that resource on `-cp` (the official launcher ships
     * it there) without creating a second `minecraft` module. Without it, mods that
     * read the MC version from `version.json` as a resource -- CustomSkinLoader --
     * detect "version 0" and mis-patch.
     */
    private fun modernClasspath(runtime: ResolvedRuntime): String =
        (runtime.libraries.map { it.path } + listOfNotNull(runtime.clientResourcesJar))
            .joinToString(File.pathSeparator) { it.toAbsolutePath().toString() }

    /**
     * Resolves the modern `arguments.jvm` template to concrete tokens. The
     * version json's `${...}` placeholders are substituted from the known
     * paths; the `-cp ${classpath}` pair and any `-Djava.library.path` are
     * dropped because the builder emits its own, while `-p <module path>` is
     * kept (its value substituted) so BootstrapLauncher gets the exact boot
     * module set the installer chose.
     */
    private fun modernJvmArgs(
        runtime: ResolvedRuntime,
        gameDir: Path,
        sharedAssetsDir: Path,
        sharedLibrariesDir: Path,
        nativesPath: Path,
        versionLabel: String,
    ): List<String> {
        val substitutions = mapOf(
            $$"${library_directory}" to sharedLibrariesDir.toAbsolutePath().toString(),
            $$"${classpath_separator}" to File.pathSeparator,
            $$"${version_name}" to versionLabel,
            $$"${natives_directory}" to nativesPath.toString(),
            $$"${assets_root}" to sharedAssetsDir.toAbsolutePath().toString(),
            $$"${game_directory}" to gameDir.toAbsolutePath().toString(),
            $$"${primary_jar}" to runtime.clientJar.toAbsolutePath().toString(),
            $$"${launcher_name}" to Branding.UPSTREAM_NAME,
            $$"${launcher_version}" to Protocol.MIMIC_LAUNCHER_VERSION,
        )
        fun substitute(token: String): String {
            var result = token
            for ((placeholder, value) in substitutions) result = result.replace(placeholder, value)
            return result
        }

        val jvm = runtime.jvmArgs
        val out = ArrayList<String>(jvm.size)
        var i = 0
        while (i < jvm.size) {
            val token = jvm[i]
            when {
                token == "-cp" || token == "-classpath" || token == "--class-path" ->
                    i += if (i + 1 < jvm.size) 2 else 1
                token == "-p" || token == "--module-path" -> {
                    if (i + 1 < jvm.size) {
                        out.add(token)
                        out.add(substitute(jvm[i + 1]))
                        i += 2
                    } else i += 1
                }
                token == $$"${classpath}" || token.startsWith("-Djava.library.path") -> i += 1
                else -> {
                    out.add(substitute(token))
                    i += 1
                }
            }
        }
        return out
    }

    /** -XstartOnFirstThread is mandatory for LWJGL on macOS; no-op on other OSes. */
    private fun addMacOsStartupFlags(args: MutableList<String>) {
        if (OS.isMacOS) {
            args.add("-XstartOnFirstThread")
            args.add("-Djava.awt.headless=false")
        }
    }

    /**
     * Attaches the heap-profiler agent for an adaptive launch. Both flags go in
     * as discrete argv elements (ProcessBuilder's list form passes each verbatim,
     * so a path with spaces is safe). The metrics out-path rides a `-D` property,
     * NOT the `-javaagent:jar=opts` suffix -- that suffix splits on its first `=`
     * and mangles Windows drive/paths. No-op unless both paths are present.
     */
    private fun addProfilerArgs(args: MutableList<String>, agentJarPath: Path?, metricsOutPath: Path?) {
        if (agentJarPath == null || metricsOutPath == null) return
        args.add("-Dnexira.profiler.out=${metricsOutPath.toAbsolutePath()}")
        args.add("-javaagent:${agentJarPath.toAbsolutePath()}")
    }

    /**
     * Attaches the authlib-redirect agent for an SC-bound join. The SC host
     * rides the `=host=<host>` agent-option suffix: the JVM splits the jar path
     * from options on the FIRST `=`, and the host is a bare hostname (no `=`, no
     * path), so the split is unambiguous -- unlike the profiler's metrics path
     * (a user-data path that could carry an `=`), which goes via `-D`. The host
     * matches the `-Dminecraft.api.*.host` redirect above so legacy and modern
     * authlib aim at the same SC backend. No-op unless an agent path is present.
     */
    private fun addAuthlibAgentArg(args: MutableList<String>, authlibAgentJarPath: Path?) {
        if (authlibAgentJarPath == null) return
        args.add("-javaagent:${authlibAgentJarPath.toAbsolutePath()}=host=${protocolConfig.sslBypassHost}")
    }

    private fun addSessionAuthArgs(args: MutableList<String>, session: SessionData) {
        // The game process echoes this token back in ways no log pattern
        // predicts -- authlib logs it verbatim when it fails to read it as a
        // JWT. Registering the value here, at the one point where a token
        // crosses into the process, masks every such echo.
        Redactor.registerSecret(session.accessToken)

        // Never emit a blank uuid/token. An offline relaunch of a server whose
        // per-server SmartyCraft token was never cached leaves accessToken empty,
        // which puts an empty element in argv ("--accessToken" then "") -- the
        // client crashes parsing it before it can even report a bad session.
        // A "0" placeholder degrades to a clean in-game "invalid session" instead.
        args.add("--uuid"); args.add(session.uuid.ifBlank { "0" })
        args.add("--accessToken"); args.add(session.accessToken.ifBlank { "0" })
        args.add("--userProperties"); args.add("{}")
        // Offline play has no Mojang/SC session: userType "legacy" tells the client
        // not to expect one. The uuid is the vanilla OfflinePlayer:<name> value, so
        // singleplayer world data lines up with other launchers' offline mode.
        args.add("--userType"); args.add(if (session.offline) "legacy" else "mojang")
    }

}

package hivens.launcher.imports

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

// Discovery sources for each supported launcher. They live together because
// they share the same tiny read-only helpers and never grow launch logic --
// each only answers "what is importable here". Parsing is deliberately
// tolerant: a shape we do not recognise yields a null field, never an
// exception, so one weird instance cannot hide the rest.

private val log = LoggerFactory.getLogger("hivens.launcher.imports.discovery")

/** Count of `*.jar` files directly under `<gameDir>/mods`. Disabled (`.jar.disabled`) excluded. */
internal fun countMods(gameDir: Path): Int {
    val mods = gameDir.resolve("mods")
    if (!Files.isDirectory(mods)) return 0
    return runCatching {
        Files.newDirectoryStream(mods, "*.jar").use { it.count() }
    }.getOrDefault(0)
}

/** Parse [file] as a JSON object, or null if absent / unreadable / not an object. */
internal fun readJsonObject(file: Path, json: Json): JsonObject? = runCatching {
    if (!Files.isRegularFile(file)) null
    else json.parseToJsonElement(Files.readString(file)).jsonObject
}.onFailure { log.debug("import: could not parse {}", file, it) }.getOrNull()

private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

/** True when a directory looks like a real game dir (has any of mods/saves/versions/options.txt). */
private fun looksLikeGameDir(dir: Path): Boolean =
    Files.isDirectory(dir.resolve("mods")) ||
        Files.isDirectory(dir.resolve("saves")) ||
        Files.isDirectory(dir.resolve("versions")) ||
        Files.isRegularFile(dir.resolve("options.txt"))

/**
 * Vanilla Mojang launcher / TLauncher. Both back onto `.minecraft`. Yields one
 * instance for the shared root, plus one for each `launcher_profiles.json`
 * profile that points at its own `gameDir`. Pseudo-versions (`latest-release` /
 * `latest-snapshot`) are left as a null [DiscoveredInstance.mcVersion].
 */
class MinecraftLauncherSource(
    private val locator: LauncherRootLocator,
    private val json: Json,
) : LauncherInstanceSource {
    override val launcher = ForeignLauncher.Vanilla

    override fun discover(): List<DiscoveredInstance> = locator.existingRoots(launcher).flatMap { root ->
        val found = mutableListOf<DiscoveredInstance>()
        if (looksLikeGameDir(root)) {
            found += DiscoveredInstance(
                launcher = launcher,
                id = "root",
                displayName = ".minecraft",
                gameDir = root,
                mcVersion = null,
                modCount = countMods(root),
            )
        }
        // Profiles with a custom gameDir are separate installs.
        val profiles = readJsonObject(root.resolve("launcher_profiles.json"), json)
            ?.get("profiles")?.let { runCatching { it.jsonObject }.getOrNull() }
        profiles?.forEach { (key, value) ->
            val prof = runCatching { value.jsonObject }.getOrNull() ?: return@forEach
            val gameDir = prof.str("gameDir")?.let { Path.of(it) } ?: return@forEach
            if (!Files.isDirectory(gameDir) || gameDir.normalize() == root.normalize()) return@forEach
            val version = prof.str("lastVersionId")?.takeIf { !it.startsWith("latest-") }
            found += DiscoveredInstance(
                launcher = launcher,
                id = "profile:$key",
                displayName = prof.str("name") ?: gameDir.fileName.toString(),
                gameDir = gameDir,
                mcVersion = version,
                modCount = countMods(gameDir),
            )
        }
        found
    }
}

/**
 * Modrinth App. Each subdirectory of `<root>/profiles` is a full game dir. The
 * MC version + loader live in the app's `app.db` (SQLite); reading them is left
 * to the import step, so discovery reports name + mod count only.
 */
class ModrinthAppSource(
    private val locator: LauncherRootLocator,
) : LauncherInstanceSource {
    override val launcher = ForeignLauncher.Modrinth

    override fun discover(): List<DiscoveredInstance> = locator.existingRoots(launcher).flatMap { root ->
        val profilesDir = root.resolve("profiles")
        if (!Files.isDirectory(profilesDir)) return@flatMap emptyList()
        runCatching {
            Files.newDirectoryStream(profilesDir).use { stream ->
                stream.filter { Files.isDirectory(it) }.map { dir ->
                    DiscoveredInstance(
                        launcher = launcher,
                        id = dir.fileName.toString(),
                        displayName = dir.fileName.toString(),
                        gameDir = dir,
                        modCount = countMods(dir),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }
}

/**
 * Prism Launcher / MultiMC-family. Instances live under `<root>/instances/<id>`,
 * with the game dir in `.minecraft` (or `minecraft`). `mmc-pack.json` names the
 * MC version and loader via component uids; `instance.cfg` carries the display
 * name.
 */
class PrismLauncherSource(
    private val locator: LauncherRootLocator,
    private val json: Json,
) : LauncherInstanceSource {
    override val launcher = ForeignLauncher.Prism

    override fun discover(): List<DiscoveredInstance> = locator.existingRoots(launcher).flatMap { root ->
        val instances = root.resolve("instances")
        if (!Files.isDirectory(instances)) return@flatMap emptyList()
        runCatching {
            Files.newDirectoryStream(instances).use { stream ->
                stream.filter { Files.isDirectory(it) && !it.fileName.toString().startsWith(".") }
                    .mapNotNull { dir -> readPrismInstance(dir) }
                    .toList()
            }
        }.getOrDefault(emptyList())
    }

    private fun readPrismInstance(dir: Path): DiscoveredInstance? {
        val gameDir = listOf(dir.resolve(".minecraft"), dir.resolve("minecraft")).firstOrNull { Files.isDirectory(it) }
            ?: return null
        val name = runCatching {
            Files.readAllLines(dir.resolve("instance.cfg"))
                .firstOrNull { it.startsWith("name=") }?.substringAfter("name=")?.takeIf { it.isNotBlank() }
        }.getOrNull() ?: dir.fileName.toString()

        var mc: String? = null
        var loader: String? = null
        var loaderVersion: String? = null
        // mmc-pack.json's `components` is an array of {uid, version}; walk it tolerantly.
        runCatching {
            val comps = json.parseToJsonElement(Files.readString(dir.resolve("mmc-pack.json")))
                .jsonObject["components"] as? JsonArray ?: return@runCatching
            comps.forEach { el ->
                val c = el.jsonObject
                val uid = c.str("uid") ?: return@forEach
                val ver = c.str("version")
                when (uid) {
                    "net.minecraft" -> mc = ver
                    "net.minecraftforge" -> { loader = "forge"; loaderVersion = ver }
                    "net.neoforged" -> { loader = "neoforge"; loaderVersion = ver }
                    "net.fabricmc.fabric-loader" -> { loader = "fabric"; loaderVersion = ver }
                    "org.quiltmc.quilt-loader" -> { loader = "quilt"; loaderVersion = ver }
                }
            }
        }
        return DiscoveredInstance(
            launcher = launcher,
            id = dir.fileName.toString(),
            displayName = name,
            gameDir = gameDir,
            mcVersion = mc,
            loader = loader,
            loaderVersion = loaderVersion,
            modCount = countMods(gameDir),
        )
    }
}

/**
 * FTB App. Instances live under `~/.ftba/instances/<id>`; the instance directory
 * IS the game dir. `instance.json` carries the name, MC version and loader.
 */
class FtbAppSource(
    private val locator: LauncherRootLocator,
    private val json: Json,
) : LauncherInstanceSource {
    override val launcher = ForeignLauncher.Ftb

    override fun discover(): List<DiscoveredInstance> = locator.existingRoots(launcher).flatMap { root ->
        val instances = root.resolve("instances")
        if (!Files.isDirectory(instances)) return@flatMap emptyList()
        runCatching {
            Files.newDirectoryStream(instances).use { stream ->
                stream.filter { Files.isDirectory(it) && !it.fileName.toString().startsWith(".") }
                    .mapNotNull { dir -> readFtbInstance(dir) }
                    .toList()
            }
        }.getOrDefault(emptyList())
    }

    private fun readFtbInstance(dir: Path): DiscoveredInstance? {
        if (!looksLikeGameDir(dir)) return null
        val meta = readJsonObject(dir.resolve("instance.json"), json)
        return DiscoveredInstance(
            launcher = launcher,
            id = dir.fileName.toString(),
            displayName = meta?.str("name") ?: dir.fileName.toString(),
            gameDir = dir,
            mcVersion = meta?.str("mcVersion"),
            loader = meta?.str("modLoader")?.substringBefore('-')?.lowercase()?.takeIf { it.isNotBlank() },
            modCount = countMods(dir),
        )
    }
}

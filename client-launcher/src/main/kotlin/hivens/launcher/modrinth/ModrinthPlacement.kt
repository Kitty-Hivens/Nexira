package hivens.launcher.modrinth

import hivens.core.api.dto.modrinth.ModrinthVersion
import hivens.launcher.instance.ContentKind
import hivens.launcher.instance.ModUpdateChannel

/*
 * Where a catalogue build goes in a pack, and which build of a project a pack
 * should take.
 *
 * The catalogue has no field that says "this is a resource pack". It derives a
 * project's type from the loaders its builds are published for, and so does this:
 * `minecraft` is a resource pack, `iris` and `optifine` are shader packs, `canvas`
 * and `vanilla` are shaders that ship as resource packs, `datapack` is a data pack,
 * `mrpack` is a modpack, and the rest are mod loaders or server plugin platforms.
 * The table is the catalogue's own, from its loader seed data.
 *
 * A build can name several of these. A mod that also ships as a data pack names
 * its mod loader and `datapack`, and in a pack that runs that loader it is a mod.
 */

/** Why a build has no place in a pack. */
enum class PlacementRefusal {
    /** A mod for a loader the pack does not run, or any mod in a pack that runs none. */
    WrongLoader,

    /** A data pack. It belongs to a world, and choosing one is not offered yet. */
    Datapack,

    /** A whole modpack. It is installed as a pack of its own, not into one. */
    Modpack,

    /** A server plugin. A client has nowhere to load it from. */
    Plugin,

    /** No loader the catalogue is known to publish under. */
    Unknown,
}

/** Where a build goes: a content folder, or nowhere and why. */
sealed interface Placement {
    data class Into(val kind: ContentKind) : Placement
    data class Refused(val reason: PlacementRefusal) : Placement
}

/**
 * The catalogue loader names whose mods a pack running [packLoader] on
 * [mcVersion] loads, the pack's own first.
 *
 * Quilt loads Fabric mods. NeoForge on 1.20.1 is the fork point and still reads
 * Forge mods there, which it stopped doing from 1.20.2. Cleanroom and lwjgl3ify run
 * Forge 1.12.2 and are not catalogue loaders at all. A blank or `vanilla` loader
 * runs no mods, so it accepts none.
 */
fun acceptedLoaders(packLoader: String, mcVersion: String): List<String> =
    when (packLoader.trim().lowercase()) {
        "", "vanilla" -> emptyList()
        "quilt" -> listOf("quilt", "fabric")
        "neoforge" -> if (mcVersion.trim() == NEOFORGE_FORK_VERSION) listOf("neoforge", "forge") else listOf("neoforge")
        "cleanroom", "lwjgl3ify" -> listOf("forge")
        else -> listOf(packLoader.trim().lowercase())
    }

/**
 * Where a build published for [buildLoaders] goes in a pack running [packLoader]
 * on [mcVersion].
 *
 * A mod for the pack's loader wins over everything else the build also names, so
 * a hybrid lands in `mods/` where the loader reads it. A shader for Iris or
 * OptiFine goes to `shaderpacks/` whether or not the pack carries either: the
 * catalogue does not check that, and the folder is where the player would put it
 * by hand.
 */
fun placementFor(buildLoaders: List<String>, packLoader: String, mcVersion: String): Placement {
    val loaders = buildLoaders.map { it.lowercase() }.toSet()
    val accepted = acceptedLoaders(packLoader, mcVersion)
    return when {
        accepted.any { it in loaders } -> Placement.Into(ContentKind.Mod)
        RESOURCE_PACK_LOADER in loaders -> Placement.Into(ContentKind.ResourcePack)
        loaders.any { it in SHADER_PACK_LOADERS } -> Placement.Into(ContentKind.ShaderPack)
        loaders.any { it in RESOURCE_PACK_SHADER_LOADERS } -> Placement.Into(ContentKind.ResourcePack)
        loaders.any { it in MOD_LOADERS } -> Placement.Refused(PlacementRefusal.WrongLoader)
        DATAPACK_LOADER in loaders -> Placement.Refused(PlacementRefusal.Datapack)
        MODPACK_LOADER in loaders -> Placement.Refused(PlacementRefusal.Modpack)
        loaders.any { it in PLUGIN_LOADERS } -> Placement.Refused(PlacementRefusal.Plugin)
        else -> Placement.Refused(PlacementRefusal.Unknown)
    }
}

/** [placementFor] for one build. */
fun ModrinthVersion.placementIn(packLoader: String, mcVersion: String): Placement =
    placementFor(loaders, packLoader, mcVersion)

/**
 * The build of a project a pack should take from [listing], or null when none
 * runs there.
 *
 * A build fits when it names [mcVersion] (a blank one is not a constraint) and has
 * a place in the pack. Of those, the newest by publish date on the stablest
 * channel that has any: a project publishing releases is not handed an alpha
 * because the alpha is newer, and one that only ever published betas still gets
 * its newest beta. The listing's own order is not trusted, since the catalogue
 * does not promise one.
 */
fun chooseBuild(
    listing: List<ModrinthVersion>,
    mcVersion: String,
    packLoader: String,
    channel: ModUpdateChannel = ModUpdateChannel.Release,
): ModrinthVersion? {
    val fitting = listing.filter { v ->
        v.files.isNotEmpty() &&
            (mcVersion.isBlank() || mcVersion in v.gameVersions) &&
            v.placementIn(packLoader, mcVersion) is Placement.Into
    }
    for (types in channel.rungs) {
        fitting.filter { it.versionType.lowercase() in types }
            .maxByOrNull { it.datePublished }
            ?.let { return it }
    }
    return null
}

private const val NEOFORGE_FORK_VERSION = "1.20.1"
private const val RESOURCE_PACK_LOADER = "minecraft"
private const val DATAPACK_LOADER = "datapack"
private const val MODPACK_LOADER = "mrpack"
private val SHADER_PACK_LOADERS = setOf("iris", "optifine")

/** Shaders the game or Canvas reads out of a resource pack. */
private val RESOURCE_PACK_SHADER_LOADERS = setOf("canvas", "vanilla")

private val MOD_LOADERS = setOf(
    "babric", "bta-babric", "fabric", "forge", "java-agent", "legacy-fabric", "liteloader",
    "modloader", "neoforge", "nilloader", "ornithe", "quilt", "rift",
)

private val PLUGIN_LOADERS = setOf(
    "bukkit", "bungeecord", "folia", "paper", "purpur", "spigot", "sponge", "velocity", "waterfall",
)

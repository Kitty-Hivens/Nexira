package hivens.core.api.dto.curseforge

import kotlinx.serialization.Serializable

/**
 * The subset of a CurseForge modpack export's `manifest.json` we can act on
 * without the CurseForge API. `files[]` reference mods by project/file id only,
 * so they cannot be resolved to download URLs here -- only [overrides] (configs
 * and any bundled jars) install. Tolerant decoder ignores the rest.
 */
@Serializable
data class CfManifest(
    val minecraft: CfMinecraft = CfMinecraft(),
    val files: List<CfFile> = emptyList(),
    val overrides: String = "overrides",
    val name: String = "",
    val version: String = "",
)

@Serializable
data class CfMinecraft(
    val version: String = "",
    val modLoaders: List<CfModLoader> = emptyList(),
)

@Serializable
data class CfModLoader(
    /** e.g. `forge-47.2.0`, `neoforge-21.1.1`, `fabric-0.16.0`. */
    val id: String = "",
    val primary: Boolean = false,
)

@Serializable
data class CfFile(
    val projectID: Long = 0,
    val fileID: Long = 0,
    val required: Boolean = true,
)

package hivens.core.data

import kotlinx.serialization.Serializable

/**
 * Subset of a Hivens mirror manifest the launcher needs to launch a
 * pack instance: MC version, loader, and Java major. Cached on
 * [PackInstance.cachedManifest] at install / sync time so a Play
 * click does not require a manifest fetch -- the JSON repository on
 * disk already has every value the launch command builder needs.
 *
 * Source of truth is the live mirror manifest at sync time; if the
 * pack ever updates server-side, the next sync refreshes this
 * snapshot. Offline Play uses whatever the last sync recorded --
 * that is the whole point of caching here rather than re-fetching.
 */
@Serializable
data class CachedManifestSnapshot(
    val minecraftVersion: String,
    val loaderName: String,
    val loaderVersion: String,
    val javaMajor: Int,
    /**
     * Which auth provider the launcher must have a live session for
     * before spawning this pack. Null = no precondition (vanilla,
     * future offline-only packs); existing serialized instances
     * predate this field and read back as null cleanly.
     */
    val authRequirement: PackAuthRequirement? = null,
)

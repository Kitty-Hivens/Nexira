package hivens.core.data

import kotlinx.serialization.Serializable

/**
 * One local installation of a [Pack]. Multiple instances of the
 * same pack are allowed -- "Industrial vanilla" and "Industrial with
 * shaders" can coexist as two PackInstances pointing at the same
 * `(packRef.origin, packRef.id)` with different selected optional
 * content and different runtime preferences.
 *
 * The instance's files live under
 * `<dataDir>/instances/<instanceDirName>/`. The resolved [java.nio.file.Path]
 * is not held here on purpose -- the data class stays pure data,
 * path resolution is a repository concern.
 *
 * Identity is the UUID-string [id]. It's preferred over the slug-
 * style `instanceDirName` because (a) two instances can legitimately
 * want the same human-readable folder name across packs ("default")
 * with disambiguation handled at fs-creation time, and (b) UUIDs
 * never need a uniqueness check at create time.
 */
@Serializable
data class PackInstance(
    /** UUID-as-string, generated at create time via `UUID.randomUUID().toString()`. */
    val id: String,
    val packRef: PackReference,
    val displayName: String,
    /**
     * Subdirectory name under `<dataDir>/instances/`. Repository
     * resolves to the absolute path; this field never carries a
     * leading separator or any path-traversal component.
     */
    val instanceDirName: String,
    val createdAtEpoch: Long,
    val lastPlayedEpochOrZero: Long = 0L,
    /** Total seconds spent in-game across all sessions; summed on each game exit. */
    val playtimeSeconds: Long = 0L,
    /**
     * Pack version this instance is pinned to. Null means "follow
     * whatever [Pack.latestVersion] currently is". Pinning is the
     * pack-centric analogue of a server-list snapshot -- the user
     * explicitly opts out of auto-updates for this instance.
     */
    val pinnedPackVersion: String? = null,
    val runtime: InstanceRuntime = InstanceRuntime(),
    /**
     * Per-entry on/off toggles for the pack's optional content.
     * Entries the pack lists as required do not appear here (always
     * installed); the manifest is the source of truth for what's
     * required. Default empty = all-optional-off; the install flow
     * may pre-populate this from manifest defaults at create time.
     */
    val optionalContent: List<ContentToggle> = emptyList(),
    /**
     * Set when this instance was forked from another (Modrinth-style
     * "duplicate this pack and customise"). Tracks the source so the
     * UI can show "forked from X", and so future cross-source
     * import / sync flows have provenance to work with.
     */
    val forkedFrom: PackReference? = null,
    /** User-editable free text. Shown on the instance detail surface. */
    val notes: String = "",
    /**
     * Snapshot of the fields the launch command builder needs from the
     * mirror manifest (MC version, loader, Java major). Filled in by
     * the install / sync flow so Play does not require a fresh
     * manifest fetch. Null on instances created before the field
     * existed; the launch path falls back to a one-shot fetch in that
     * case, then writes the snapshot back.
     */
    val cachedManifest: CachedManifestSnapshot? = null,
    /**
     * Square pack icon, captured from the catalogue at install time. Drives the
     * Library card avatar; null (local imports, pre-field instances) falls back
     * to title initials.
     */
    val iconUrl: String? = null,
    /**
     * Wide banner, captured from the catalogue at install time. Backs the
     * Library card; null falls back to procedural pixel art.
     */
    val bannerUrl: String? = null,
    /**
     * The set of files the pack itself laid down at install / last sync -- the
     * update BASELINE. Captured by the installers (sha1 + size per file, reused from
     * the source manifest). Null on instances created before this field existed (and
     * on Local instances never installed from a remote source). The
     * [hivens.core.update.UpdateReconciler] diffs a new version's target manifest
     * against this baseline + the current on-disk state to decide what to add /
     * update / safely delete, leaving user-added files (which are NOT in here) alone.
     */
    val installedManifest: FileManifest? = null,
    /**
     * Whether this instance auto-updates to the mirror's latest build. True
     * (default) = follow latest; false = pinned, i.e. the user opted out of
     * auto-updates and stays on [pinnedPackVersion] until they update by hand.
     * Distinct from [pinnedPackVersion], which always records the CURRENT
     * installed version regardless of this policy.
     */
    val followLatest: Boolean = true,
)

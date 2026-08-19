package hivens.core.update

import hivens.core.update.PackBuild
import hivens.core.data.PackInstance
import kotlinx.coroutines.flow.Flow

/**
 * Moves an installed pack instance to another build. The launcher implements it
 * against the mirror; abstracted so the auto-updater and UI depend on the
 * contract rather than the concrete driver, and so the policy logic can be tested
 * without touching the network or disk.
 */
interface PackUpdater {
    /**
     * Whether anything can offer other builds of [instance] at all.
     *
     * A capability, deliberately not a test of where the pack came from. A local
     * instance and one synced from a game server have no version feed and answer
     * false; asking by origin instead spread the same question across the tree
     * and had to be widened by hand every time a source was added.
     */
    fun handles(instance: PackInstance): Boolean = true

    /**
     * Whether this source can say what a build CONTAINS without installing it.
     *
     * The mirror publishes a manifest per build, so two of them can be compared
     * before either is on disk. Modrinth publishes an archive and nothing else:
     * answering the same question means downloading both, which is the update
     * itself. A surface that asks anyway gets an error where it wanted a file
     * list -- and asking the mirror's endpoint about a pack the mirror has never
     * heard of gets a 404 shown to the player as a failure of their pack.
     */
    fun describesBuildContents(instance: PackInstance): Boolean = false

    /**
     * Read-only: is a different build available for [instance], and what would it
     * change? [forceRefresh] bypasses the read cache and belongs to a check the
     * user asked for -- a background pass leaves it false, since answering from a
     * warm cache is the point there.
     */
    suspend fun checkForUpdate(instance: PackInstance, forceRefresh: Boolean = false): UpdateCheck

    /**
     * Read-only: what switching [instance] to the specific [targetVersion] would
     * change (forward or backward), with its compat grade. [UpdateCheck.UpToDate]
     * when the instance is already on that build.
     */
    suspend fun previewSwitch(instance: PackInstance, targetVersion: String): UpdateCheck

    /**
     * Apply an update. [targetVersion] null updates to the latest build; a specific
     * version switches or rolls back to it.
     */
    suspend fun applyUpdate(
        instance: PackInstance,
        targetVersion: String? = null,
        progress: ((current: Int, total: Int, path: String) -> Unit)? = null,
    ): UpdateOutcome

    /** The mirror's retained builds for [instance], newest first (server order is canonical). */
    suspend fun availableBuilds(instance: PackInstance): List<PackBuild>

    /**
     * The same listing as a stale-then-fresh stream: the cached one arrives at
     * once when it is merely stale, the reloaded one replaces it. A screen that
     * reads [availableBuilds] once paints whatever the cache held and never sees
     * the refresh that read triggered, which is how the newest builds go missing
     * until the screen is reopened.
     */
    fun availableBuildsStream(instance: PackInstance): Flow<List<PackBuild>>

    /** Snapshots [instance] can be rolled back to, newest first. */
    fun listSnapshots(instance: PackInstance): List<PackSnapshot>

    /**
     * Roll [instance] back to snapshot [snapshotId]: restore the captured files
     * and the pre-update instance record. Returns the restored instance, pinned
     * (no longer following latest).
     */
    suspend fun rollback(instance: PackInstance, snapshotId: String): PackInstance
}

package hivens.core.launch

/**
 * Result of an `ILauncherService` spawn. Replaces the prior `Process` return +
 * `@Throws(IOException)` so a frontend gets a typed outcome instead of exception
 * handling: [Started] hands back the running process [LaunchHandle]; [Failed]
 * carries the semantic [LaunchError] the service mapped (a provisioning / spawn
 * failure, or an SC-binding block).
 */
sealed interface SpawnResult {
    data class Started(
        val handle: LaunchHandle,
        /** The loader version the launch resolved, for a pack that pinned none. Null for vanilla. */
        val resolvedLoaderVersion: String? = null,
    ) : SpawnResult
    data class Failed(val error: LaunchError) : SpawnResult
}

package hivens.launcher

import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.ContentToggle
import hivens.core.data.InstanceRuntime
import hivens.core.data.PackInstance
import hivens.core.data.PackLoader
import hivens.core.data.PackOrigin
import hivens.core.data.PackReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

/**
 * In-memory implementation of [IPackRepository]. Seeded with a small
 * fixture of representative [PackInstance]s so the Library screen has
 * something to render before the persistence layer + real install
 * flow exist.
 *
 * Seed deliberately covers every [PackOrigin] value so a UI run
 * shows all four source-badge variants at once. Versions and meta
 * are plausible (real-ish) but invented; nothing here implies an
 * actual install on disk -- the [PackInstance.instanceDirName]s
 * resolve to non-existent paths until the install flow lands.
 *
 * Replaced by a JSON-on-disk repository in a follow-up PR. Keep
 * the contract identical so callers don't need changes.
 */
class InMemoryPackRepository : IPackRepository {

    private val mutex = Mutex()
    private val state: MutableStateFlow<List<PackInstance>> = MutableStateFlow(seed())

    override fun observe(): StateFlow<List<PackInstance>> = state.asStateFlow()

    override suspend fun list(): List<PackInstance> = state.value

    override suspend fun get(id: String): PackInstance? = state.value.firstOrNull { it.id == id }

    override suspend fun put(instance: PackInstance) = mutex.withLock {
        state.update { current ->
            val replaced = current.map { if (it.id == instance.id) instance else it }
            if (replaced.any { it.id == instance.id }) replaced else replaced + instance
        }
    }

    override suspend fun delete(id: String) = mutex.withLock {
        state.update { current -> current.filterNot { it.id == id } }
    }

    private fun seed(): List<PackInstance> {
        val now = Instant.now().epochSecond
        val daysAgo: (Long) -> Long = { d -> now - d * 86_400 }
        return listOf(
            PackInstance(
                id = "00000000-0000-0000-0000-00000000aaaa",
                packRef = PackReference(PackOrigin.Smartycraft, "Industrial", "2026.04.18"),
                displayName = "Industrial",
                instanceDirName = "industrial-default",
                createdAtEpoch = daysAgo(28),
                lastPlayedEpochOrZero = daysAgo(1),
                runtime = InstanceRuntime(memoryMb = 6144),
            ),
            PackInstance(
                id = "00000000-0000-0000-0000-00000000bbbb",
                packRef = PackReference(PackOrigin.Smartycraft, "SkyBlock", "2026.05.02"),
                displayName = "SkyBlock",
                instanceDirName = "skyblock-default",
                createdAtEpoch = daysAgo(14),
                lastPlayedEpochOrZero = daysAgo(3),
                runtime = InstanceRuntime(memoryMb = 4096),
            ),
            PackInstance(
                id = "00000000-0000-0000-0000-00000000cccc",
                packRef = PackReference(PackOrigin.Mirror, "Create", "2026.05.20"),
                displayName = "Create (Hivens improved)",
                instanceDirName = "create-hivens",
                createdAtEpoch = daysAgo(7),
                lastPlayedEpochOrZero = daysAgo(0),
                runtime = InstanceRuntime(memoryMb = 8192),
                optionalContent = listOf(
                    ContentToggle("Sodium.jar", enabled = true),
                    ContentToggle("ComplementaryShaders.zip", enabled = true),
                ),
            ),
            PackInstance(
                id = "00000000-0000-0000-0000-00000000dddd",
                packRef = PackReference(PackOrigin.Modrinth, "AbcDef12", "1.3.4"),
                displayName = "Better Minecraft",
                instanceDirName = "better-minecraft",
                createdAtEpoch = daysAgo(3),
                lastPlayedEpochOrZero = 0L,
                runtime = InstanceRuntime(memoryMb = 6144),
            ),
            PackInstance(
                id = "00000000-0000-0000-0000-00000000eeee",
                packRef = PackReference(PackOrigin.Local, "11111111-aaaa-bbbb-cccc-222222222222"),
                displayName = "Create Tinker (my fork)",
                instanceDirName = "create-tinker-fork",
                createdAtEpoch = daysAgo(2),
                lastPlayedEpochOrZero = daysAgo(0),
                runtime = InstanceRuntime(memoryMb = 8192),
                forkedFrom = PackReference(PackOrigin.Mirror, "Create", "2026.05.20"),
                notes = "Added Tinker's Construct + Iron Jetpacks; removed quark sound. Personal use.",
            ),
        )
    }
}

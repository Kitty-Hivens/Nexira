package hivens.widget.api

import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.mutableStateMapOf
import hivens.widget.model.WidgetService
import kotlin.reflect.KClass

// Cross-widget service registry. Provider widgets register their
// service implementations on mount; consumer widgets / mixins read by
// kind, by instance, or as a broadcast list. Backed by snapshot state
// maps so Compose consumers recompose automatically when a provider
// mounts or unmounts -- no separate Flow plumbing required.
//
// Scope: single global registry, one Koin singleton per launcher
// process. A consumer on any surface can read a provider on any
// other; achievement detection and music ducking are cross-surface
// by design. Per-surface scoping can land later via a `scope`
// filter without breaking the default.
//
// Lookups return nullable snapshots so a consumer firing during the
// brief between-mount window (provider unmounted, replacement not yet
// mounted) does not block or deadlock -- it gets null this frame and
// recomposes next frame when the new provider registers.
//
// Sort by instanceId is the determinism contract: with two providers
// of the same kind, every consumer using first() must agree on which
// one wins, and that agreement must survive process restarts (no
// random.shuffle, no insertion-order from a non-deterministic source).
class WidgetServiceRegistry {

    // Two-level snapshot map: outer keyed by service KClass, inner
    // keyed by provider widget's instanceId. Reading either level
    // subscribes the Compose snapshot to changes, so a consumer that
    // calls first<MusicPlayerService>() automatically recomposes
    // when the MusicPlayerWidget mounts / unmounts.
    private val byKind: SnapshotStateMap<KClass<*>, SnapshotStateMap<String, WidgetService>> =
        mutableStateMapOf()

    fun register(clazz: KClass<out WidgetService>, instanceId: String, service: WidgetService) {
        // The reified provideService<T> wrapper is type-safe, but a
        // direct register call from a future plugin / hand-written
        // provider could store a service that does not actually
        // implement clazz; failure would surface as a
        // ClassCastException on the consumer's first<T>() call, with
        // no breadcrumb back to the wrong provider. Reject loudly
        // here instead.
        require(clazz.isInstance(service)) {
            "service does not implement ${clazz.qualifiedName} (got ${service::class.qualifiedName})"
        }
        val perKind = byKind.getOrPut(clazz) { mutableStateMapOf() }
        perKind[instanceId] = service
    }

    fun unregister(clazz: KClass<out WidgetService>, instanceId: String) {
        val perKind = byKind[clazz] ?: return
        perKind.remove(instanceId)
        if (perKind.isEmpty()) byKind.remove(clazz)
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : WidgetService> byInstance(clazz: KClass<T>, instanceId: String): T? =
        byKind[clazz]?.get(instanceId) as T?

    @Suppress("UNCHECKED_CAST")
    fun <T : WidgetService> first(clazz: KClass<T>): T? {
        val perKind = byKind[clazz] ?: return null
        // O(n) min-key scan instead of all().firstOrNull() which sorts
        // the entire list just to drop everything but the first.
        // SnapshotStateMap's iteration order is unspecified, so the
        // sort was for determinism; min-by-instanceId gives the same
        // deterministic winner without paying for the full sort.
        val firstKey = perKind.keys.minOrNull() ?: return null
        return perKind[firstKey] as T?
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : WidgetService> all(clazz: KClass<T>): List<T> {
        val perKind = byKind[clazz] ?: return emptyList()
        // Sort by instanceId for determinism. Without this, the
        // SnapshotStateMap's iteration order is unspecified and a
        // "first" lookup could pick a different provider between
        // runs, or worse, flicker between providers on
        // recomposition.
        return perKind.entries
            .sortedBy { it.key }
            .map { it.value as T }
    }
}

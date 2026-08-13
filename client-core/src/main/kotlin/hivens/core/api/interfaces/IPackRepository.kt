package hivens.core.api.interfaces

import hivens.core.data.PackInstance
import kotlinx.coroutines.flow.Flow

/**
 * Local store of installed [PackInstance]s. Library screen consumes
 * [observe] for live updates; install / fork / delete flows go
 * through the mutation methods so the underlying Flow re-emits.
 *
 * Implementations choose their own persistence shape (in-memory for
 * dev / tests, JSON-on-disk for production). Repository contract
 * does not constrain that.
 *
 * Concurrency: implementations must be safe to call from multiple
 * coroutines. Mutations are serialised so a concurrent install +
 * delete cannot leave the store in a half-applied state.
 */
interface IPackRepository {
    /**
     * Cold-start snapshot, then re-emits the full list on any
     * mutation. Library / Home screens collect this directly.
     */
    fun observe(): Flow<List<PackInstance>>

    /** One-shot snapshot. Useful for non-Compose call sites. */
    suspend fun list(): List<PackInstance>

    /** Fetch a specific instance by its UUID id. Null when not installed. */
    suspend fun get(id: String): PackInstance?

    /**
     * Persist [instance]. Creates if its id is unknown, replaces if
     * known. The id is the source of truth for identity, NOT the
     * displayName (the user can rename instances freely).
     *
     * Implementations hold the instance to [hivens.core.data.PackIdentity]
     * before storing it: a reference that names no pack is a bug in whatever
     * built it, and the store is where it stops rather than where it is kept
     * until some later screen cannot resolve it.
     *
     * @throws IllegalArgumentException when the instance's identity is malformed.
     */
    suspend fun put(instance: PackInstance)

    /** Remove the instance with [id]. No-op when not present. */
    suspend fun delete(id: String)
}

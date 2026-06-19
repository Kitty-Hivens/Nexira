package hivens.auth

/**
 * The set of auth providers the launcher can satisfy, keyed by [AuthProvider.id].
 *
 * The content router maps a pack or server to a provider id; the launch gate then
 * enforces a requirement only when its provider is actually [contains]ed here. A
 * provider that has not landed yet (e.g. Microsoft before its phase) is therefore
 * advisory -- content routed to it still launches -- rather than a hard block.
 */
class AuthProviderRegistry(providers: List<AuthProvider>) {

    private val byId: Map<String, AuthProvider> = providers.associateBy { it.id }

    operator fun get(id: String): AuthProvider? = byId[id]

    fun contains(id: String): Boolean = id in byId

    val all: List<AuthProvider> get() = byId.values.toList()
}

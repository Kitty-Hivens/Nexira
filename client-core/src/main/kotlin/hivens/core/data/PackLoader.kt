package hivens.core.data

import kotlinx.serialization.Serializable

/**
 * Minecraft mod loader a pack runs against. Drives launch-time
 * choices (classpath assembly, JVM flags, mod-folder layout) and
 * mod-source compatibility (a Forge pack can't load a Fabric-only
 * mod, etc).
 *
 * [Quilt] is included as a distinct value even though it's a
 * Fabric superset at the protocol level -- pack manifests
 * legitimately request Quilt-specific features (Quilt loader API,
 * Quilted Fabric API) that a plain Fabric setup can't satisfy.
 */
@Serializable
enum class PackLoader {
    Forge,
    NeoForge,
    Fabric,
    Quilt,
    Vanilla,
}

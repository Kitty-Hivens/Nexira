package hivens.core.data

import kotlinx.serialization.KSerializer
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

    /**
     * A loader a newer build wrote that this build does not know.
     * Never emitted intentionally -- [PackLoaderSerializer] folds an
     * unrecognised wire value here so an older build does not silently
     * misread it as [Forge] and assemble the wrong classpath / flags.
     * The loader registry has no resolver for it, so a launch attempt
     * fails with an honest error instead of mislaunching.
     */
    Unknown,
}

/** Persistence codec that folds an unknown wire loader to [PackLoader.Unknown]. */
object PackLoaderSerializer : KSerializer<PackLoader> by LenientEnumSerializer(
    PackLoader.entries.toTypedArray(),
    PackLoader.Unknown,
    PackLoader.serializer(),
)

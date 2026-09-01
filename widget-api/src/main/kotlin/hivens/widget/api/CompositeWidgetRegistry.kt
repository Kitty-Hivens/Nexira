package hivens.widget.api

import hivens.widget.model.WidgetKind

/**
 * One registry over several, so widgets can come from more than the single
 * generated object.
 *
 * The kernel was built for this -- [WidgetRegistry] is an interface, the
 * validator's rules were written to apply to a method rather than only to a KSP
 * symbol, and the processor warns rather than fails on a contract nobody in the
 * build provides, precisely because the provider is expected to arrive from
 * outside it. What was missing was the thing that puts two registries together.
 *
 * **Order is precedence, and the first source wins.** The caller passes the
 * built-in registry first, so a contributed widget cannot take over a kind the
 * application depends on -- the shell regions, the sign-in panel -- by declaring
 * the same id. Which is a different question from whether a person may remove one
 * from their own layout: they may, because the way back into the editor is a
 * window chord rather than anything in the graph.
 *
 * A shadowed kind is not silently dropped: [shadowed] reports every id a later
 * source offered and did not get, which is what a management surface needs to
 * explain why a contribution did not take effect. Nothing here logs -- this
 * module has no logger and no opinion about where a diagnostic belongs.
 */
class CompositeWidgetRegistry(
    private val sources: List<WidgetRegistry>,
) : WidgetRegistry {

    /**
     * Built once: the sources are fixed for the life of the process, so this is
     * a value rather than a lookup. A registry set that could change under a
     * running composition would invalidate every descriptor the editor is
     * holding, which is why it cannot.
     */
    private val merged: Map<WidgetKind, WidgetDescriptor> = buildMap {
        sources.forEach { source ->
            source.all().forEach { (kind, descriptor) -> putIfAbsent(kind, descriptor) }
        }
    }

    /**
     * Kinds a later source offered that an earlier one already had, in the order
     * the sources were given. Empty in the ordinary case.
     */
    val shadowed: List<ShadowedKind> = buildList {
        val seen = HashMap<WidgetKind, Int>()
        sources.forEachIndexed { index, source ->
            source.all().keys.forEach { kind ->
                val owner = seen[kind]
                if (owner == null) seen[kind] = index else add(ShadowedKind(kind, owner, index))
            }
        }
    }

    override fun all(): Map<WidgetKind, WidgetDescriptor> = merged

    override fun get(kind: WidgetKind): WidgetDescriptor? = merged[kind]
}

/** A kind offered by [bySource] that [heldBy] had already registered. Indices into the source list. */
data class ShadowedKind(
    val kind: WidgetKind,
    val heldBy: Int,
    val bySource: Int,
)

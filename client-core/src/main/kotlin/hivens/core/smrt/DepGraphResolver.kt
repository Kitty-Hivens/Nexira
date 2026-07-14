package hivens.core.smrt

import hivens.core.api.dto.smrt.SmrtModEntry
import hivens.core.api.dto.smrt.SmrtPackManifest

/**
 * Walks a manifest's `display.requires` declarations into a directed
 * graph suitable for the Library PackDetail Content tab's dep-tree
 * rendering. The graph layer stays pure and synchronous; the UI
 * builds collapsible tree views on top.
 *
 * Validation surfaces two error classes on the result:
 *  - [DepGraph.missingRequirements] -- a `requires` entry that points
 *    at a filename not present in the manifest's mods[]. Render as
 *    inline warning; mod likely won't load.
 *  - [DepGraph.cycles] -- defensively detected even though mod-mod
 *    cycles can't load in practice. Surfacing them means the manifest
 *    author wrote bad metadata, which we want visible at install time
 *    rather than at first launch.
 */
object DepGraphResolver {

    fun resolve(manifest: SmrtPackManifest): DepGraph {
        val nodes = manifest.mods.map { DepNode(it.filename, it) }
        val byFilename = nodes.associateBy { it.filename }

        val edges = mutableListOf<DepEdge>()
        val missing = mutableListOf<MissingRequirement>()

        for (mod in manifest.mods) {
            val requires = mod.display?.requires ?: continue
            for (req in requires) {
                if (req.filename in byFilename) {
                    edges += DepEdge(
                        from = mod.filename,
                        to = req.filename,
                        versionRange = req.versionRange,
                        optional = req.optional,
                    )
                } else {
                    missing += MissingRequirement(
                        from = mod.filename,
                        requiresFilename = req.filename,
                    )
                }
            }
        }

        return DepGraph(
            nodes = nodes,
            edges = edges,
            missingRequirements = missing,
            cycles = findCycles(nodes, edges),
        )
    }

    /**
     * Tarjan-style SCC detection. Returns each strongly-connected
     * component with more than one node, plus single-node components
     * that have a self-loop. Singleton non-self-loop nodes are skipped
     * (those are healthy leaves, not cycles).
     */
    private fun findCycles(nodes: List<DepNode>, edges: List<DepEdge>): List<Cycle> {
        val adj: Map<String, List<String>> = edges
            .groupBy { it.from }
            .mapValues { (_, edgesFrom) -> edgesFrom.map { it.to } }

        val indices = mutableMapOf<String, Int>()
        val lowLinks = mutableMapOf<String, Int>()
        val onStack = mutableSetOf<String>()
        val stack = ArrayDeque<String>()
        var nextIndex = 0
        val sccs = mutableListOf<List<String>>()

        fun strongConnect(v: String) {
            indices[v] = nextIndex
            lowLinks[v] = nextIndex
            nextIndex++
            stack.addLast(v)
            onStack += v

            for (w in adj[v] ?: emptyList()) {
                if (w !in indices) {
                    strongConnect(w)
                    lowLinks[v] = minOf(lowLinks.getValue(v), lowLinks.getValue(w))
                } else if (w in onStack) {
                    lowLinks[v] = minOf(lowLinks.getValue(v), indices.getValue(w))
                }
            }

            if (lowLinks[v] == indices[v]) {
                val component = mutableListOf<String>()
                while (true) {
                    val w = stack.removeLast()
                    onStack -= w
                    component += w
                    if (w == v) break
                }
                sccs += component
            }
        }

        for (node in nodes) {
            if (node.filename !in indices) strongConnect(node.filename)
        }

        return sccs
            .filter { component ->
                component.size > 1 ||
                    (component.size == 1 && edges.any { it.from == component[0] && it.to == component[0] })
            }
            .map { Cycle(it) }
    }
}

/** Snapshot result of a [DepGraphResolver.resolve] call. Pure data, safe to cache. */
data class DepGraph(
    val nodes: List<DepNode>,
    val edges: List<DepEdge>,
    val missingRequirements: List<MissingRequirement>,
    val cycles: List<Cycle>,
)

data class DepNode(val filename: String, val mod: SmrtModEntry)
data class DepEdge(val from: String, val to: String, val versionRange: String?, val optional: Boolean)
data class MissingRequirement(val from: String, val requiresFilename: String)
data class Cycle(val members: List<String>)

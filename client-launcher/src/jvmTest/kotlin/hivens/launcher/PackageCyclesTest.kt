package hivens.launcher

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The packages of this module depend on each other in one direction.
 *
 * A cycle between packages is what stops one of them being moved into a module of
 * its own, and each one that was here began as a single shared type left in the
 * package that happened to define it first. This reads the imports and fails with
 * the packages that close the loop, so the type can be moved down the day it is
 * added instead of the day somebody tries to cut the module.
 *
 * `di` and `bootstrap` assemble everything and nothing imports them, so they never
 * sit in a cycle by themselves; the check covers them like any other package.
 */
class PackageCyclesTest {

    @Test
    fun `the packages of the module form no cycle`() {
        val cycles = findCycles(importEdges(sourceRoots()))
        assertTrue(cycles.isEmpty(), "packages that depend on each other: ${cycles.map { it.sorted() }}")
    }

    @Test
    fun `a loop is found and a chain is not`() {
        val loop = mapOf("a" to setOf("b"), "b" to setOf("c"), "c" to setOf("a"), "d" to setOf("a"))
        assertEquals(listOf(setOf("a", "b", "c")), findCycles(loop))

        val chain = mapOf("a" to setOf("b"), "b" to setOf("c"), "c" to emptySet())
        assertTrue(findCycles(chain).isEmpty())
    }

    @Test
    fun `two packages that import each other are a cycle`() {
        assertEquals(listOf(setOf("x", "y")), findCycles(mapOf("x" to setOf("y"), "y" to setOf("x"))))
    }

    /** The shared and the desktop source sets are one module, so one graph. */
    private fun sourceRoots(): List<Path> =
        listOf("jvmSharedMain", "jvmMain").map { Paths.get("src", it, "kotlin", "hivens", "launcher") }

    /** package -> the packages it imports from, by the first segment under `hivens.launcher`. */
    private fun importEdges(roots: List<Path>): Map<String, Set<String>> {
        val edges = HashMap<String, MutableSet<String>>()
        for (root in roots) Files.walk(root).use { paths ->
            paths.filter { it.toString().endsWith(".kt") }.forEach { file ->
                val text = Files.readString(file)
                val own = segmentOf(PACKAGE.find(text)?.groupValues?.get(1) ?: return@forEach)
                val deps = edges.getOrPut(own) { mutableSetOf() }
                IMPORT.findAll(text).forEach { match ->
                    val target = segmentOf(match.groupValues[1])
                    if (target != own) deps += target
                }
            }
        }
        return edges
    }

    /**
     * The first lowercase segment after `hivens.launcher`, or the root for a type that
     * sits in the package itself (its name starts with a capital).
     */
    private fun segmentOf(qualified: String): String {
        val rest = qualified.removePrefix("hivens.launcher").removePrefix(".")
        val first = rest.substringBefore('.')
        return if (first.isEmpty() || first.first().isUpperCase()) ROOT else first
    }

    private companion object {
        const val ROOT = "(root)"
        val PACKAGE = Regex("""^package (hivens\.launcher[\w.]*)""", RegexOption.MULTILINE)
        val IMPORT = Regex("""^import (hivens\.launcher\.[\w.]+)""", RegexOption.MULTILINE)
    }
}

/** The strongly connected groups of more than one package, by Tarjan's algorithm. */
internal fun findCycles(graph: Map<String, Set<String>>): List<Set<String>> {
    val nodes = graph.keys + graph.values.flatten()
    val index = HashMap<String, Int>()
    val low = HashMap<String, Int>()
    val stack = ArrayDeque<String>()
    val onStack = HashSet<String>()
    val found = mutableListOf<Set<String>>()
    var counter = 0

    fun visit(node: String) {
        index[node] = counter
        low[node] = counter
        counter++
        stack.addLast(node)
        onStack += node
        for (next in graph[node].orEmpty()) {
            if (next !in index) {
                visit(next)
                low[node] = minOf(low.getValue(node), low.getValue(next))
            } else if (next in onStack) {
                low[node] = minOf(low.getValue(node), index.getValue(next))
            }
        }
        if (low[node] == index[node]) {
            val group = mutableSetOf<String>()
            while (true) {
                val member = stack.removeLast()
                onStack -= member
                group += member
                if (member == node) break
            }
            if (group.size > 1) found += group
        }
    }

    nodes.sorted().forEach { if (it !in index) visit(it) }
    return found
}

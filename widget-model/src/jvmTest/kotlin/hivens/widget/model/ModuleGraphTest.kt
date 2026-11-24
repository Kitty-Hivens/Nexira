package hivens.widget.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModuleGraphTest {

    private val v1 = ApiVersion(1, 0)

    /** A module that offers itself at 1.0. */
    private fun node(id: String, requires: List<String> = emptyList(), uses: List<String> = emptyList(), off: Boolean = false) =
        ModuleNode(id, mapOf(id to v1), requires.associateWith { v1 }, uses.associateWith { v1 }, off)

    @Test
    fun `modules that require nothing run in the order given`() {
        val graph = ModuleGraph.of(listOf(node("b"), node("a")))
        assertEquals(listOf("b", "a"), graph.order)
        assertEquals(emptyMap(), graph.waiting)
        assertEquals(emptyList(), ModuleGraph.EMPTY.order)
    }

    @Test
    fun `a module comes after everything it requires, however far down`() {
        val graph = ModuleGraph.of(listOf(node("app", listOf("mid")), node("mid", listOf("base")), node("other"), node("base")))
        assertEquals(listOf("other", "base", "mid", "app"), graph.order)
    }

    @Test
    fun `a module waits for what is not there, naming it and the version`() {
        val graph = ModuleGraph.of(listOf(node("a", listOf("gone"))))
        assertEquals(emptyList(), graph.order)
        assertEquals(mapOf("a" to listOf<ModuleWait>(ModuleWait.Missing("gone", v1))), graph.waiting)
        assertNull(graph.providerOf("gone"))
    }

    @Test
    fun `a module waits for another version, naming both`() {
        val graph = ModuleGraph.of(
            listOf(
                ModuleNode("base", mapOf("api" to ApiVersion(1, 2))),
                ModuleNode("older", requires = mapOf("api" to ApiVersion(1, 1))),
                ModuleNode("newer", requires = mapOf("api" to ApiVersion(1, 3))),
                ModuleNode("major", requires = mapOf("api" to ApiVersion(2, 0))),
            ),
        )
        assertEquals(listOf("base", "older"), graph.order)
        assertEquals(listOf<ModuleWait>(ModuleWait.OtherVersion("api", ApiVersion(1, 3), ApiVersion(1, 2), "base")), graph.waiting["newer"])
        assertEquals(listOf<ModuleWait>(ModuleWait.OtherVersion("api", ApiVersion(2, 0), ApiVersion(1, 2), "base")), graph.waiting["major"])
    }

    @Test
    fun `a module waits for one that is switched off, and what requires it waits with it`() {
        val graph = ModuleGraph.of(listOf(node("base", off = true), node("mid", listOf("base")), node("app", listOf("mid"))))
        assertEquals(emptyList(), graph.order)
        assertEquals(listOf<ModuleWait>(ModuleWait.Off("base", "base")), graph.waiting["mid"])
        assertEquals(listOf<ModuleWait>(ModuleWait.Stopped("mid", "mid")), graph.waiting["app"])
        assertFalse("base" in graph.waiting, "one a person switched off does not wait for anything")
    }

    @Test
    fun `every reason is given, not the first`() {
        val graph = ModuleGraph.of(listOf(node("off", off = true), node("a", listOf("gone", "off"))))
        assertEquals(listOf(ModuleWait.Missing("gone", v1), ModuleWait.Off("off", "off")), graph.waiting["a"])
    }

    @Test
    fun `every module of a circle waits with the circle, and what requires one of them waits for it`() {
        val graph = ModuleGraph.of(listOf(node("a", listOf("b")), node("b", listOf("c")), node("c", listOf("a")), node("out", listOf("b")), node("free")))
        assertEquals(listOf("free"), graph.order)
        val circle = ModuleWait.Cycle(listOf("a", "b", "c"))
        listOf("a", "b", "c").forEach { assertEquals(listOf<ModuleWait>(circle), graph.waiting[it], it) }
        assertEquals(listOf<ModuleWait>(ModuleWait.Stopped("b", "b")), graph.waiting["out"])
    }

    @Test
    fun `two circles that share nothing are told apart`() {
        val graph = ModuleGraph.of(listOf(node("a", listOf("b")), node("b", listOf("a")), node("x", listOf("y")), node("y", listOf("x"))))
        assertEquals(listOf<ModuleWait>(ModuleWait.Cycle(listOf("a", "b"))), graph.waiting["b"])
        assertEquals(listOf<ModuleWait>(ModuleWait.Cycle(listOf("x", "y"))), graph.waiting["x"])
    }

    @Test
    fun `a module on two circles at once still waits and stops the search`() {
        val graph = ModuleGraph.of(listOf(node("a", listOf("b", "c")), node("b", listOf("a")), node("c", listOf("a"))))
        assertEquals(emptyList(), graph.order)
        assertEquals(setOf("a", "b", "c"), graph.waiting.keys)
    }

    @Test
    fun `a module that requires what it offers itself requires nothing`() {
        assertEquals(listOf("a"), ModuleGraph.of(listOf(node("a", listOf("a")))).order)
    }

    @Test
    fun `what is only used sets the order and never makes a module wait`() {
        val graph = ModuleGraph.of(listOf(node("a", uses = listOf("b", "gone")), node("b")))
        assertEquals(listOf("b", "a"), graph.order)
        assertEquals(emptyMap(), graph.waiting)
    }

    @Test
    fun `a circle of uses, or of a use and a requirement, is no circle`() {
        assertEquals(listOf("a", "b"), ModuleGraph.of(listOf(node("a", uses = listOf("b")), node("b", uses = listOf("a")))).order)
        assertEquals(listOf("b", "a"), ModuleGraph.of(listOf(node("a", requires = listOf("b")), node("b", uses = listOf("a")))).order)
    }

    @Test
    fun `a used name counts as served only from a module that runs, in a version that serves`() {
        val graph = ModuleGraph.of(
            listOf(
                ModuleNode("base", mapOf("api" to ApiVersion(1, 2))),
                node("off", off = true),
                node("stuck", listOf("gone")),
            ),
        )
        assertTrue(graph.serves("api", ApiVersion(1, 1)))
        assertFalse(graph.serves("api", ApiVersion(1, 3)))
        assertFalse(graph.serves("off", v1))
        assertFalse(graph.serves("stuck", v1))
        assertFalse(graph.serves("gone", v1))
        assertEquals(ApiVersion(1, 2), graph.versionOf("api"))
    }

    @Test
    fun `the first module to offer a name offers it`() {
        val graph = ModuleGraph.of(listOf(ModuleNode("first", mapOf("api" to v1)), ModuleNode("second", mapOf("api" to ApiVersion(2, 0))), ModuleNode("a", requires = mapOf("api" to v1))))
        assertEquals("first", graph.providerOf("api"))
        assertEquals(listOf("first", "second", "a"), graph.order)
    }

    @Test
    fun `of two modules with one id the first is read`() {
        val graph = ModuleGraph.of(listOf(node("a"), node("a", listOf("gone"))))
        assertEquals(listOf("a"), graph.order)
    }

    @Test
    fun `what a module requires and what requires it are followed all the way`() {
        val graph = ModuleGraph.of(listOf(node("app", listOf("mid")), node("mid", listOf("base")), node("base"), node("other", listOf("base")), node("free")))
        assertEquals(listOf("base", "mid"), graph.requiredBy(listOf("app")))
        assertEquals(listOf("base", "mid"), graph.requiredBy(listOf("app", "other")))
        assertEquals(emptyList(), graph.requiredBy(listOf("free", "not there")))
        assertEquals(setOf("app", "mid", "other"), graph.dependantsOf("base").toSet())
        assertEquals(listOf("app"), graph.dependantsOf("mid"))
        assertEquals(emptyList(), graph.dependantsOf("free"))
    }

    @Test
    fun `a long chain resolves`() {
        val graph = ModuleGraph.of((0 until 400).map { i -> node("m$i", if (i < 399) listOf("m${i + 1}") else emptyList()) })
        assertEquals("m399", graph.order.first())
        assertEquals("m0", graph.order.last())
    }
}

package hivens.core.smrt

import hivens.core.api.dto.smrt.SmrtDisplay
import hivens.core.api.dto.smrt.SmrtJava
import hivens.core.api.dto.smrt.SmrtLoader
import hivens.core.api.dto.smrt.SmrtMinecraft
import hivens.core.api.dto.smrt.SmrtModEntry
import hivens.core.api.dto.smrt.SmrtPackManifest
import hivens.core.api.dto.smrt.SmrtRequirement
import hivens.core.api.dto.smrt.SmrtSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DepGraphResolverTest {

    @Test
    fun `manifest with no display block resolves to bare nodes, no edges`() {
        val g = DepGraphResolver.resolve(manifest(
            mod("a.jar"),
            mod("b.jar"),
        ))
        assertEquals(2, g.nodes.size)
        assertTrue(g.edges.isEmpty())
        assertTrue(g.missingRequirements.isEmpty())
        assertTrue(g.cycles.isEmpty())
    }

    @Test
    fun `requires entries become edges with version range + optional flag`() {
        val g = DepGraphResolver.resolve(manifest(
            mod("jei.jar"),
            mod("addon.jar", requires = listOf(
                SmrtRequirement(filename = "jei.jar", versionRange = ">=4.0", optional = false),
            )),
        ))
        assertEquals(1, g.edges.size)
        val edge = g.edges[0]
        assertEquals("addon.jar", edge.from)
        assertEquals("jei.jar", edge.to)
        assertEquals(">=4.0", edge.versionRange)
        assertEquals(false, edge.optional)
    }

    @Test
    fun `requires pointing at absent filename surfaces missing requirement`() {
        val g = DepGraphResolver.resolve(manifest(
            mod("addon.jar", requires = listOf(
                SmrtRequirement(filename = "ghost.jar"),
            )),
        ))
        assertEquals(0, g.edges.size, "no edge created when target missing")
        assertEquals(1, g.missingRequirements.size)
        assertEquals("ghost.jar", g.missingRequirements[0].requiresFilename)
    }

    @Test
    fun `cycle of two mods is detected`() {
        val g = DepGraphResolver.resolve(manifest(
            mod("a.jar", requires = listOf(SmrtRequirement("b.jar"))),
            mod("b.jar", requires = listOf(SmrtRequirement("a.jar"))),
        ))
        assertEquals(1, g.cycles.size)
        assertEquals(setOf("a.jar", "b.jar"), g.cycles[0].members.toSet())
    }

    @Test
    fun `self-loop is detected as a cycle`() {
        val g = DepGraphResolver.resolve(manifest(
            mod("solo.jar", requires = listOf(SmrtRequirement("solo.jar"))),
        ))
        assertEquals(1, g.cycles.size)
        assertEquals(listOf("solo.jar"), g.cycles[0].members)
    }

    @Test
    fun `linear chain produces no cycles`() {
        val g = DepGraphResolver.resolve(manifest(
            mod("a.jar", requires = listOf(SmrtRequirement("b.jar"))),
            mod("b.jar", requires = listOf(SmrtRequirement("c.jar"))),
            mod("c.jar"),
        ))
        assertTrue(g.cycles.isEmpty())
        assertEquals(2, g.edges.size)
    }

    @Test
    fun `diamond DAG produces no cycles`() {
        // a -> b -> d, a -> c -> d
        val g = DepGraphResolver.resolve(manifest(
            mod("a.jar", requires = listOf(SmrtRequirement("b.jar"), SmrtRequirement("c.jar"))),
            mod("b.jar", requires = listOf(SmrtRequirement("d.jar"))),
            mod("c.jar", requires = listOf(SmrtRequirement("d.jar"))),
            mod("d.jar"),
        ))
        assertTrue(g.cycles.isEmpty())
        assertEquals(4, g.edges.size)
    }

    @Test
    fun `optional flag round-trips into edge`() {
        val g = DepGraphResolver.resolve(manifest(
            mod("jei.jar"),
            mod("addon.jar", requires = listOf(
                SmrtRequirement("jei.jar", optional = true),
            )),
        ))
        assertEquals(true, g.edges[0].optional)
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun manifest(vararg mods: SmrtModEntry) = SmrtPackManifest(
        schemaVersion = 2,
        packId = "Test",
        packVersion = "1",
        generatedAt = "2026-05-26T00:00:00Z",
        minecraft = SmrtMinecraft("1.12.2"),
        loader = SmrtLoader("forge", "14.23.5.2922"),
        java = SmrtJava(8),
        mods = mods.toList(),
    )

    private fun mod(filename: String, requires: List<SmrtRequirement> = emptyList()) = SmrtModEntry(
        filename = filename,
        sha1 = "0".repeat(40),
        sizeBytes = 1L,
        source = SmrtSource.SmrtCache(url = "https://example/$filename"),
        display = if (requires.isEmpty()) null else SmrtDisplay(requires = requires),
    )
}

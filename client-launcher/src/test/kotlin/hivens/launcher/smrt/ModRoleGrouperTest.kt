package hivens.launcher.smrt

import hivens.core.api.dto.smrt.SmrtDisplay
import hivens.core.api.dto.smrt.SmrtModEntry
import hivens.core.api.dto.smrt.SmrtSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModRoleGrouperTest {

    @Test
    fun `mods without role land in ungrouped`() {
        val g = ModRoleGrouper.group(listOf(mod("a.jar"), mod("b.jar")))
        assertTrue(g.byRole.isEmpty())
        assertEquals(2, g.ungrouped.size)
    }

    @Test
    fun `mods with same role bucket together`() {
        val g = ModRoleGrouper.group(listOf(
            mod("jei.jar", role = "recipe_viewer"),
            mod("rei.jar", role = "recipe_viewer"),
            mod("emi.jar", role = "recipe_viewer"),
        ))
        assertEquals(1, g.byRole.size)
        assertEquals("recipe_viewer", g.byRole[0].role)
        assertEquals(3, g.byRole[0].members.size)
    }

    @Test
    fun `role keys are case-folded`() {
        val g = ModRoleGrouper.group(listOf(
            mod("a.jar", role = "Recipe_Viewer"),
            mod("b.jar", role = "RECIPE_VIEWER"),
            mod("c.jar", role = "recipe_viewer"),
        ))
        assertEquals(1, g.byRole.size, "all three should bucket together after case-folding")
        assertEquals("recipe_viewer", g.byRole[0].role)
        assertEquals(3, g.byRole[0].members.size)
    }

    @Test
    fun `blank role goes to ungrouped, not its own group`() {
        val g = ModRoleGrouper.group(listOf(
            mod("a.jar", role = ""),
            mod("b.jar", role = "   "),
            mod("c.jar", role = null),
        ))
        assertTrue(g.byRole.isEmpty())
        assertEquals(3, g.ungrouped.size)
    }

    @Test
    fun `multiple distinct roles produce multiple groups`() {
        val g = ModRoleGrouper.group(listOf(
            mod("jei.jar", role = "recipe_viewer"),
            mod("xaero.jar", role = "minimap"),
            mod("jade.jar", role = "block_info"),
            mod("ungrouped.jar"),
        ))
        assertEquals(3, g.byRole.size)
        assertEquals(1, g.ungrouped.size)
        assertEquals("ungrouped.jar", g.ungrouped[0].filename)
    }

    @Test
    fun `members within a role preserve manifest order`() {
        val g = ModRoleGrouper.group(listOf(
            mod("zaero.jar", role = "minimap"),
            mod("aaa.jar",   role = "minimap"),
            mod("mmm.jar",   role = "minimap"),
        ))
        val names = g.byRole[0].members.map { it.filename }
        assertEquals(listOf("zaero.jar", "aaa.jar", "mmm.jar"), names)
    }

    private fun mod(filename: String, role: String? = null) = SmrtModEntry(
        filename = filename,
        sha1 = "0".repeat(40),
        sizeBytes = 1L,
        source = SmrtSource.SmrtCache(url = "https://example/$filename"),
        display = role?.let { SmrtDisplay(role = it) },
    )
}

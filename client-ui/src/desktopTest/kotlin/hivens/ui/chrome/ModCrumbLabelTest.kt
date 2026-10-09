package hivens.ui.chrome

import hivens.ui.feature.catalogue.project.ModTarget
import hivens.ui.feature.catalogue.project.OpenProject
import hivens.ui.feature.catalogue.project.ProjectSource
import kotlin.test.Test
import kotlin.test.assertEquals

class ModCrumbLabelTest {

    private val target = ModTarget.Catalogue("AANobbMI")

    private fun open(title: String, pending: Boolean) =
        OpenProject(targetKey = target.key, title = title, slug = "sodium", source = ProjectSource.Catalogue, pending = pending)

    @Test
    fun `a page still waiting does not cover the name a visit before left`() {
        val label = modCrumbLabel(target, open("AANobbMI", pending = true), mapOf(target.key to "Sodium"))
        assertEquals("Sodium", label)
    }

    @Test
    fun `a page that has heard back names its crumb`() {
        val label = modCrumbLabel(target, open("Sodium 2", pending = false), mapOf(target.key to "Sodium"))
        assertEquals("Sodium 2", label)
    }

    @Test
    fun `a page never read falls back on the route's id`() {
        assertEquals("AANobbMI", modCrumbLabel(target, open("AANobbMI", pending = true), emptyMap()))
    }
}

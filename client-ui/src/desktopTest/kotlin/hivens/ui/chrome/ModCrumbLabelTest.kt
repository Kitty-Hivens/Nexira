package hivens.ui.chrome

import hivens.catalogue.api.OpenProject
import hivens.catalogue.api.ProjectSource
import hivens.catalogue.api.ProjectTarget
import kotlin.test.Test
import kotlin.test.assertEquals

class ModCrumbLabelTest {

    private val target = ProjectTarget.Catalogue("AANobbMI")

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

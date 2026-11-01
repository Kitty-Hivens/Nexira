package hivens.widget.api

import hivens.widget.model.FamilyId
import hivens.widget.model.SurfaceId
import kotlin.test.Test
import kotlin.test.assertEquals

class SurfaceFamiliesTest {

    private val rail = SurfaceId("rail")
    private val browse = FamilyId("browse")
    private val project = FamilyId("projectView")

    @Test
    fun `the family stays up while a screen that wants it is still mounted`() {
        val families = SurfaceFamilies()
        val leaving = Any()
        val arriving = Any()
        families.switch(rail, project, leaving)
        families.switch(rail, project, arriving)

        families.reset(rail, leaving)

        assertEquals(project, families.activeIn(rail))
        families.reset(rail, arriving)
        assertEquals(FamilyId.GENERAL, families.activeIn(rail))
    }

    /**
     * Back inside the fade keeps the screen it returns to and never runs its effects
     * again. A count only knew that someone still wanted a family, so the rail beside
     * Browse went on showing the project view of the page just left.
     */
    @Test
    fun `a screen leaving over another hands the surface back to that screen's family`() {
        val families = SurfaceFamilies()
        val search = Any()
        val page = Any()
        families.switch(rail, browse, search)
        families.switch(rail, project, page)
        assertEquals(project, families.activeIn(rail))

        families.reset(rail, page)

        assertEquals(browse, families.activeIn(rail))
    }

    @Test
    fun `a reset of an owner that never switched changes nothing`() {
        val families = SurfaceFamilies()
        val owner = Any()
        families.switch(rail, browse, owner)

        families.reset(rail, Any())
        families.reset(SurfaceId("other"), owner)

        assertEquals(browse, families.activeIn(rail))
    }
}

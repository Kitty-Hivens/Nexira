package hivens.look

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** A look module's namespace and API id come from its name, the way the map of look modules names them. */
class LookModuleNamesTest {

    @Test
    fun `an API module's version is the property its name gives, beside the other module APIs'`() {
        val names = LookModuleNames("manager-api")
        assertEquals("hivens.look.manager.api", names.namespace)
        assertEquals("nexira.lookManagerApi", names.versionProperty)
        assertEquals("nexira.lookColourMaterialApi", LookModuleNames("colour-material-api").versionProperty)
    }

    @Test
    fun `a provider's namespace names its axis and its family`() {
        assertEquals("hivens.look.colour.nx", LookModuleNames("colour-nx").namespace)
        assertEquals("hivens.look.manager", LookModuleNames("manager").namespace)
    }

    @Test
    fun `a name that would not make a package is refused`() {
        listOf("Colour-nx", "colour_nx", "-colour", "colour--nx", "2d-api").forEach { name ->
            assertFailsWith<IllegalArgumentException>(name) { LookModuleNames(name) }
        }
    }
}

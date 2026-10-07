package hivens.launcher.launch

import hivens.core.data.PackAuthRequirement
import kotlin.test.Test
import kotlin.test.assertEquals

class PackAuthRouterTest {

    @Test
    fun `explicit manifest requirement wins`() {
        val explicit = PackAuthRequirement.SmartyCraft("Survival")
        assertEquals(explicit, PackAuthRouter.requirementFor(explicit))
    }

    /**
     * The binding is the manifest's `auth` block and nothing else. Deriving one from
     * the origin used to hand a live session to a launch no manifest had bound, with
     * none of a bound launch's guards armed.
     */
    @Test
    fun `without an explicit block the answer is Microsoft, never a server binding`() {
        assertEquals(PackAuthRequirement.Microsoft, PackAuthRouter.requirementFor(null))
    }
}

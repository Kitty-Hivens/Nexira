package hivens.launcher.launch

import hivens.core.data.PackAuthRequirement
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.core.data.PackReference
import kotlin.test.Test
import kotlin.test.assertEquals

class PackAuthRouterTest {

    private fun instance(origin: PackOrigin, name: String = "Some Pack", id: String = "proj") =
        PackInstance(
            id = "uuid",
            packRef = PackReference(origin = origin, id = id),
            displayName = name,
            instanceDirName = "dir",
            createdAtEpoch = 0L,
        )

    @Test
    fun `explicit manifest requirement wins over origin`() {
        val explicit = PackAuthRequirement.SmartyCraft("Survival")
        assertEquals(explicit, PackAuthRouter.requirementFor(instance(PackOrigin.Modrinth), explicit))
    }

    @Test
    fun `non-SC origins derive Microsoft`() {
        for (origin in listOf(PackOrigin.Modrinth, PackOrigin.Local, PackOrigin.Unknown)) {
            assertEquals(
                PackAuthRequirement.Microsoft,
                PackAuthRouter.requirementFor(instance(origin), null),
                "origin $origin should derive Microsoft",
            )
        }
    }

    @Test
    fun `smartycraft origin without a known name has no requirement`() {
        assertEquals(null, PackAuthRouter.requirementFor(instance(PackOrigin.Smartycraft, name = "Random"), null))
    }

    @Test
    fun `industrial name falls back to SC for SC and mirror origins`() {
        assertEquals(
            PackAuthRequirement.SmartyCraft("Industrial"),
            PackAuthRouter.requirementFor(instance(PackOrigin.Smartycraft, name = "Industrial"), null),
        )
        assertEquals(
            PackAuthRequirement.SmartyCraft("Industrial"),
            PackAuthRouter.requirementFor(instance(PackOrigin.Mirror, id = "industrial"), null),
        )
    }

    @Test
    fun `mirror origin without an SC name derives Microsoft`() {
        assertEquals(
            PackAuthRequirement.Microsoft,
            PackAuthRouter.requirementFor(instance(PackOrigin.Mirror, name = "Cool", id = "cool"), null),
        )
    }
}

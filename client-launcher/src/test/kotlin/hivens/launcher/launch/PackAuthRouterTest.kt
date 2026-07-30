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
    fun `smartycraft origin binds to its pack id`() {
        assertEquals(
            PackAuthRequirement.SmartyCraft("Industrial"),
            PackAuthRouter.requirementFor(instance(PackOrigin.Smartycraft, id = "Industrial"), null),
        )
    }

    @Test
    fun `mirror origin without an explicit block derives Microsoft`() {
        // The SC binding for mirror packs comes exclusively from the manifest's
        // auth block now; a name is not an identity.
        assertEquals(
            PackAuthRequirement.Microsoft,
            PackAuthRouter.requirementFor(instance(PackOrigin.Mirror, name = "Create", id = "Create"), null),
        )
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
}

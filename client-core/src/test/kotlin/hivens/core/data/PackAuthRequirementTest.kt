package hivens.core.data

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class PackAuthRequirementTest {

    private val json = Json

    @Test
    fun `round-trips every variant through the polymorphic serializer`() {
        val cases = listOf<PackAuthRequirement>(
            PackAuthRequirement.SmartyCraft("Industrial"),
            PackAuthRequirement.Microsoft,
            PackAuthRequirement.Both("Survival"),
        )
        for (case in cases) {
            val encoded = json.encodeToString(PackAuthRequirement.serializer(), case)
            val decoded = json.decodeFromString(PackAuthRequirement.serializer(), encoded)
            assertEquals(case, decoded)
        }
    }

    @Test
    fun `scServerId is the SC server id for SC-bound variants and null otherwise`() {
        assertEquals("Industrial", PackAuthRequirement.SmartyCraft("Industrial").scServerId)
        assertEquals("Survival", PackAuthRequirement.Both("Survival").scServerId)
        assertEquals(null, PackAuthRequirement.Microsoft.scServerId)
    }
}

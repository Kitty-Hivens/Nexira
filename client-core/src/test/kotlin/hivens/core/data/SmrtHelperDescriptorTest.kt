package hivens.core.data

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SmrtHelperDescriptorTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val descriptor = SmrtHelperDescriptor(
        variants = listOf(
            SmrtHelperVariant("1.12", tag = "v1", asset = "broad.jar", sha256 = "aa"),
            SmrtHelperVariant("1.12.2", tag = "v2", asset = "exact.jar", sha256 = "bb"),
            SmrtHelperVariant("1.7", tag = "v3", asset = "legacy.jar", sha256 = "cc"),
        ),
    )

    @Test
    fun `variantFor picks the longest matching prefix`() {
        assertEquals("exact.jar", descriptor.variantFor("1.12.2")?.asset)
    }

    @Test
    fun `variantFor falls back to a broader prefix`() {
        assertEquals("broad.jar", descriptor.variantFor("1.12.1")?.asset)
    }

    @Test
    fun `variantFor returns null when nothing matches`() {
        assertNull(descriptor.variantFor("1.21.1"))
    }

    @Test
    fun `parses wire JSON and defaults smarty names`() {
        val parsed = json.decodeFromString<SmrtHelperDescriptor>(
            """
            {
              "schema_version": 1,
              "variants": [
                { "mc_prefix": "1.12.2", "tag": "v2", "asset": "open-smrt-1.12.2.jar",
                  "sha256": "deadbeef", "size_bytes": 1234 }
              ]
            }
            """.trimIndent(),
        )
        val v = parsed.variantFor("1.12.2")!!
        assertEquals("open-smrt-1.12.2.jar", v.asset)
        assertEquals(1234, v.sizeBytes)
        assertEquals(listOf("Smarty*.jar"), v.smartyNames)
    }
}

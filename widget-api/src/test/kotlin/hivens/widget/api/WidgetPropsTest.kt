package hivens.widget.api

import hivens.widget.model.WidgetInstance
import hivens.widget.model.WidgetKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

@Serializable
private data class SampleProps(
    val flag: Boolean = true,
    val count: Int = 7,
    val label: String = "hi",
)

class WidgetPropsTest {

    private fun inst(props: JsonObject) = WidgetInstance(WidgetKind("x"), "i", props)

    @Test
    fun `empty props decodes to all defaults`() {
        assertEquals(SampleProps(), inst(JsonObject(emptyMap())).decodeProps<SampleProps>())
    }

    @Test
    fun `partial props overrides only the set keys`() {
        val p = inst(buildJsonObject { put("count", 42) }).decodeProps<SampleProps>()
        assertEquals(SampleProps(count = 42), p)
    }

    @Test
    fun `unknown keys are ignored`() {
        val p = inst(buildJsonObject { put("count", 3); put("ghost", "x") }).decodeProps<SampleProps>()
        assertEquals(SampleProps(count = 3), p)
    }

    @Test
    fun `malformed value falls back to defaults`() {
        // count is an Int field; a non-numeric string fails the decode,
        // and the accessor must fall back to defaults rather than throw.
        val p = inst(buildJsonObject { put("count", "not-a-number") }).decodeProps<SampleProps>()
        assertEquals(SampleProps(), p)
    }
}

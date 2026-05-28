package hivens.ui.editor.props

import hivens.widget.model.PropChoice
import hivens.widget.model.PropColor
import hivens.widget.model.PropHidden
import hivens.widget.model.PropLabel
import hivens.widget.model.PropRange
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Serializable
private enum class Flavor { A, B, C }

@Serializable
private data class SampleProps(
    @PropLabel("Флаг") val flag: Boolean = true,
    @PropRange(80.0, 200.0) val size: Int = 140,
    @PropColor val accent: String = "",
    @PropChoice(["x", "y"]) val pick: String = "x",
    @PropHidden val secret: Int = 0,
    val flavor: Flavor = Flavor.B,
)

// Validates the contract the prop editor's form builder relies on: the
// @SerialInfo prop annotations must survive into the SerialDescriptor and
// be readable via getElementAnnotations, and enum fields must report the
// ENUM kind with their entry names. If @SerialInfo retention or targeting
// were wrong, the editor would silently render every field as a plain
// text box -- this catches that at build time.
class PropDescriptorTest {

    private val d = serializer<SampleProps>().descriptor

    private fun annsOf(name: String): List<Annotation> {
        val idx = (0 until d.elementsCount).first { d.getElementName(it) == name }
        return d.getElementAnnotations(idx)
    }

    @Test
    fun `PropLabel surfaces with its value`() {
        assertEquals("Флаг", annsOf("flag").filterIsInstance<PropLabel>().firstOrNull()?.value)
    }

    @Test
    fun `PropRange surfaces with bounds`() {
        val r = annsOf("size").filterIsInstance<PropRange>().firstOrNull()
        assertEquals(80.0, r?.min)
        assertEquals(200.0, r?.max)
    }

    @Test
    fun `PropColor surfaces`() {
        assertTrue(annsOf("accent").any { it is PropColor })
    }

    @Test
    fun `PropChoice surfaces with options`() {
        val c = annsOf("pick").filterIsInstance<PropChoice>().firstOrNull()
        assertEquals(listOf("x", "y"), c?.options?.toList())
    }

    @Test
    fun `PropHidden surfaces`() {
        assertTrue(annsOf("secret").any { it is PropHidden })
    }

    @Test
    fun `enum field reports ENUM kind with entry names`() {
        val idx = (0 until d.elementsCount).first { d.getElementName(it) == "flavor" }
        val ed = d.getElementDescriptor(idx)
        assertEquals(SerialKind.ENUM, ed.kind)
        assertEquals(listOf("A", "B", "C"), (0 until ed.elementsCount).map { ed.getElementName(it) })
    }
}

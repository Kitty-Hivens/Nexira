package hivens.ui.layout

import hivens.widget.model.DefaultLayout
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every slot the code renders by name is one the bundled default layout declares.
 *
 * A slot exists in a graph only because the default declares it, which is where
 * the reconciler seeds it from. The top bar rendered `center` and `right` that no
 * layout carried: the editor still drew their placeholders and took a drop on
 * them, and the move then did nothing, since a write to an absent slot is a no-op,
 * so the widget vanished with no error and no undo entry. Read off the sources as
 * text, because the slot names live in the calls that render them.
 */
class RenderedSlotsDeclaredTest {

    private val sourceRoot = File("src/desktopMain/kotlin")

    // SlotRenderer(SurfaceId("x") or SurfaceId(CONST), SlotId("y"), ...). A surface
    // held in a variable (a made screen, the rail's live family) is left out: its
    // name is not known until it runs.
    private val call = Regex("""SlotRenderer\(\s*SurfaceId\(\s*(?:"([^"]+)"|([A-Z_][A-Z0-9_]*))\s*\)\s*,\s*SlotId\("([^"]+)"\)""")
    private val constant = Regex("""const\s+val\s+([A-Z_][A-Z0-9_]*)\s*=\s*"([^"]+)"""")

    private fun renderedSlots(): Set<Pair<String, String>> =
        sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                val text = file.readText()
                val constants = constant.findAll(text).associate { it.groupValues[1] to it.groupValues[2] }
                call.findAll(text).mapNotNull { m ->
                    val surface = m.groupValues[1].ifEmpty { constants[m.groupValues[2]] } ?: return@mapNotNull null
                    surface to m.groupValues[3]
                }
            }
            .toSet()

    @Test
    fun `the sources are where this test reads them`() {
        assertTrue(sourceRoot.isDirectory, "expected the desktop sources at ${sourceRoot.absolutePath}")
        assertTrue(renderedSlots().isNotEmpty(), "no SlotRenderer call was recognised, so the check below proves nothing")
    }

    @Test
    fun `every slot the code renders by name is declared by the default layout`() {
        val declared = DefaultLayout.load().surfaces.flatMap { (surface, layout) ->
            layout.families.values.flatMap { family -> family.slots.keys.map { surface.value to it.value } }
        }.toSet()

        assertEquals(emptySet(), renderedSlots() - declared, "rendered but never seeded, so a drop on them vanishes")
    }
}

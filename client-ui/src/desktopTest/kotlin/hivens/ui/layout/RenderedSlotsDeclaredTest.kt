package hivens.ui.layout

import hivens.widget.generated.GeneratedWidgetRegistry
import hivens.widget.model.StandardDocument
import hivens.widget.model.WidgetInstance
import hivens.widget.model.widgets
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every slot a container renders by name is one the data holds.
 *
 * An edit of a slot the data does not hold changes nothing, so a slot drawn without one swallowed every drop: the
 * editor drew its placeholder and took the drop, and the widget vanished with no error and no undo entry. The top bar
 * once rendered `center` and `right` that no layout carried. So a container declares the slots it renders
 * ([hivens.widget.model.Widget.slots]), and the standard document holds every slot each of its widgets declares. The
 * calls are read off the sources as text, because the slot names live in the calls that render them.
 */
class RenderedSlotsDeclaredTest {

    private val sourceRoots = listOf("jvmSharedMain", "desktopMain", "androidMain").map { File("src/$it/kotlin") }

    // SlotRenderer(instance, SlotId("y"), ...) or SlotRenderer(parent = instance, slot = SlotId("y"), ...). A name made
    // as it runs, a tab's say, is left out: it is not known until then.
    private val call = Regex("""SlotRenderer\(\s*(?:parent\s*=\s*)?[a-z][A-Za-z0-9]*\s*,\s*(?:slot\s*=\s*)?SlotId\("([^"$]+)"\)""")

    private fun renderedSlots(): Set<String> =
        sourceRoots.asSequence().flatMap { it.walkTopDown() }
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file -> call.findAll(file.readText()).map { it.groupValues[1] } }
            .toSet()

    private val declared: Map<String, Set<String>> =
        GeneratedWidgetRegistry.all().values.associate { d -> d.kind.value to d.slots.map { it.value }.toSet() }

    @Test
    fun `the sources are where this test reads them`() {
        sourceRoots.forEach { assertTrue(it.isDirectory, "expected sources at ${it.absolutePath}") }
        assertTrue(renderedSlots().isNotEmpty(), "no SlotRenderer call was recognised, so the check below proves nothing")
    }

    @Test
    fun `every slot a container renders by name is one some widget declares`() {
        val anyDeclared = declared.values.flatten().toSet()
        assertEquals(emptySet(), renderedSlots() - anyDeclared, "rendered but declared by no widget, so no data holds it")
    }

    @Test
    fun `every widget of the standard document holds each slot its kind declares`() {
        val missing = StandardDocument.load().compositions.flatMap { (name, composition) ->
            composition.widgets().flatMap { widget -> widget.missingSlots().map { "${name.value}: ${widget.instanceId} has no $it" } }
        }
        assertEquals(emptyList(), missing)
    }

    /** The slots [this] kind declares that it holds no content for, the record's where it is a single. */
    private fun WidgetInstance.missingSlots(): Set<String> =
        declared[kind.value].orEmpty() - children.keys.map { it.value }.toSet()
}

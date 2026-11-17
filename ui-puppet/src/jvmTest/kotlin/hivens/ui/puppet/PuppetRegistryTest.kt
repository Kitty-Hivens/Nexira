package hivens.ui.puppet

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The registry answers for what is registered now, by id, and refuses what is disabled or gone. */
class PuppetRegistryTest {

    @AfterTest
    fun clear() {
        PuppetRegistry.unregisterClick("test.click")
        PuppetRegistry.unregisterToggle("test.toggle")
        PuppetRegistry.unregisterField("test.field")
    }

    @Test
    fun `a registered click runs, and one taken back is refused`() {
        var clicks = 0
        PuppetRegistry.registerClick("test.click", enabled = { true }) { clicks++ }
        assertTrue(PuppetRegistry.click("test.click").isSuccess)
        PuppetRegistry.unregisterClick("test.click")
        assertTrue(PuppetRegistry.click("test.click").isFailure)
        assertEquals(1, clicks)
    }

    @Test
    fun `a disabled element is refused and left as it was`() {
        var on = false
        PuppetRegistry.registerToggle("test.toggle", getValue = { on }, setValue = { on = it }, enabled = { false })
        assertTrue(PuppetRegistry.setToggle("test.toggle", true).isFailure)
        assertEquals(false, on)
    }

    @Test
    fun `the snapshot reports each element with its kind and its value, by id`() {
        var text = "a"
        PuppetRegistry.registerField("test.field", getValue = { text }, setValue = { text = it }, enabled = { true })
        PuppetRegistry.registerToggle("test.toggle", getValue = { true }, setValue = {}, enabled = { true })
        PuppetRegistry.setCurrentScreen("Test")
        assertTrue(PuppetRegistry.setField("test.field", "b").isSuccess)
        val snapshot = PuppetRegistry.snapshot()
        assertEquals("Test", snapshot.screen)
        val mine = snapshot.elements.filter { it.id.startsWith("test.") }
        assertEquals(
            listOf(
                PuppetRegistry.Element("test.field", "field", value = "b"),
                PuppetRegistry.Element("test.toggle", "toggle", boolValue = true),
            ),
            mine,
        )
    }
}

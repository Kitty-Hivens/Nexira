package hivens.widget.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What a module declares for a person to adjust, how it is checked, and how the stored answer is read back. */
class ModuleSettingTest {

    private val settings = listOf(
        ModuleSetting.Toggle("flag", "flag", default = true),
        ModuleSetting.Amount("amount", "amount", default = 0.25f, range = 0f..2f),
        ModuleSetting.Choice("mode", "mode", listOf(ModuleSetting.Choice.Option("a", "a"), ModuleSetting.Choice.Option("b", "b")), default = "b"),
    )

    @Test
    fun `nothing stored reads as the declared defaults`() {
        val values = ModuleValues(settings)

        assertTrue(values.toggle("flag"))
        assertEquals(0.25f, values.amount("amount"))
        assertEquals("b", values.choice("mode"))
    }

    @Test
    fun `stored values are read back`() {
        val values = ModuleValues(settings, mapOf("flag" to "false", "amount" to "1.5", "mode" to "a"))

        assertEquals(false, values.toggle("flag"))
        assertEquals(1.5f, values.amount("amount"))
        assertEquals("a", values.choice("mode"))
    }

    @Test
    fun `a slider is held inside its range`() {
        assertEquals(2f, ModuleValues(settings, mapOf("amount" to "9")).amount("amount"))
        assertEquals(0f, ModuleValues(settings, mapOf("amount" to "-3")).amount("amount"))
    }

    @Test
    fun `a value that does not read is the default, never a crash`() {
        val values = ModuleValues(settings, mapOf("flag" to "maybe", "amount" to "NaN", "mode" to "gone"))

        assertTrue(values.toggle("flag"))
        assertEquals(0.25f, values.amount("amount"))
        assertEquals("b", values.choice("mode"))
    }

    @Test
    fun `asking for a key the module did not declare is the module's mistake`() {
        assertFailsWith<IllegalStateException> { ModuleValues(settings).toggle("nope") }
    }

    @Test
    fun `asking for a key as the wrong kind is the module's mistake`() {
        assertFailsWith<IllegalStateException> { ModuleValues(settings).amount("flag") }
    }

    @Test
    fun `well formed settings have no fault`() {
        assertNull(ModuleSetting.faultIn(settings))
    }

    @Test
    fun `the faults a module can declare are named`() {
        fun fault(vararg s: ModuleSetting) = ModuleSetting.faultIn(s.toList())
        val options = listOf(ModuleSetting.Choice.Option("a", "a"))

        assertTrue("no key" in fault(ModuleSetting.Toggle(" ", "x"))!!)
        assertTrue("enabled" in fault(ModuleSetting.Toggle("enabled", "x"))!!)
        assertTrue("share the key" in fault(ModuleSetting.Toggle("k", "x"), ModuleSetting.Toggle("k", "y"))!!)
        assertTrue("outside its range" in fault(ModuleSetting.Amount("k", "x", default = 3f))!!)
        assertTrue("no options" in fault(ModuleSetting.Choice("k", "x", emptyList()))!!)
        assertTrue("not one of its options" in fault(ModuleSetting.Choice("k", "x", options, default = "z"))!!)
        assertTrue("repeats" in fault(ModuleSetting.Choice("k", "x", options + options))!!)
    }
}

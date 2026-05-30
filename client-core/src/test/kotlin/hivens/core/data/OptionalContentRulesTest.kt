package hivens.core.data

import hivens.core.api.dto.smrt.SmrtDisplay
import hivens.core.api.dto.smrt.SmrtModEntry
import hivens.core.api.dto.smrt.SmrtSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OptionalContentRulesTest {

    private fun mod(
        filename: String,
        required: Boolean = true,
        defaultEnabled: Boolean = true,
        incompatibleWith: List<String> = emptyList(),
    ) = SmrtModEntry(
        filename = filename,
        sha1 = "x",
        sizeBytes = 1,
        required = required,
        defaultEnabled = defaultEnabled,
        source = SmrtSource.SmrtStatic("https://example/$filename"),
        display = if (incompatibleWith.isEmpty()) null else SmrtDisplay(incompatibleWith = incompatibleWith),
    )

    private val mods = listOf(
        mod("required.jar"),
        mod("foamfix.jar", required = false, defaultEnabled = false, incompatibleWith = listOf("mixinbooter.jar")),
        mod("mixinbooter.jar", required = false, defaultEnabled = true),
    )

    @Test
    fun `defaultToggles lists only optionals at their default_enabled`() {
        val toggles = OptionalContentRules.defaultToggles(mods)
        assertEquals(2, toggles.size)
        assertFalse(toggles.any { it.entryId == "required.jar" }, "required mods are never toggles")
        assertEquals(false, toggles.first { it.entryId == "foamfix.jar" }.enabled)
        assertEquals(true, toggles.first { it.entryId == "mixinbooter.jar" }.enabled)
    }

    @Test
    fun `enabledState forces required on and uses toggle-or-default for optionals`() {
        val state = OptionalContentRules.enabledState(mods, listOf(ContentToggle("foamfix.jar", true)))
        assertEquals(true, state["required.jar"], "required always on")
        assertEquals(true, state["foamfix.jar"], "user toggle wins over default")
        assertEquals(true, state["mixinbooter.jar"], "untouched optional uses default_enabled")
    }

    @Test
    fun `conflicts is mutual even when only one side declares it`() {
        assertTrue(OptionalContentRules.conflicts(mods, "foamfix.jar", "mixinbooter.jar"))
        assertTrue(OptionalContentRules.conflicts(mods, "mixinbooter.jar", "foamfix.jar"))
        assertFalse(OptionalContentRules.conflicts(mods, "foamfix.jar", "foamfix.jar"))
        assertFalse(OptionalContentRules.conflicts(mods, "required.jar", "mixinbooter.jar"))
    }

    @Test
    fun `applyToggle enabling disables conflicts and disabling does not cascade`() {
        val current = mapOf("foamfix.jar" to false, "mixinbooter.jar" to true)

        val afterEnable = OptionalContentRules.applyToggle(mods, current, "foamfix.jar", true)
        assertEquals(true, afterEnable["foamfix.jar"])
        assertEquals(false, afterEnable["mixinbooter.jar"], "enabling foamfix disables the incompatible mixinbooter")

        val afterDisable = OptionalContentRules.applyToggle(mods, afterEnable, "foamfix.jar", false)
        assertEquals(false, afterDisable["foamfix.jar"])
        assertEquals(false, afterDisable["mixinbooter.jar"], "disabling foamfix must not silently re-enable mixinbooter")
    }
}

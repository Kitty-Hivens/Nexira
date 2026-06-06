package hivens.core.data

import hivens.core.api.dto.smrt.SmrtDisplay
import hivens.core.api.dto.smrt.SmrtModEntry
import hivens.core.api.dto.smrt.SmrtRequirement
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
        requires: List<String> = emptyList(),
        optionalRequires: List<String> = emptyList(),
        role: String? = null,
    ): SmrtModEntry {
        val hasDisplay = incompatibleWith.isNotEmpty() || requires.isNotEmpty() ||
            optionalRequires.isNotEmpty() || role != null
        return SmrtModEntry(
            filename = filename,
            sha1 = "x",
            sizeBytes = 1,
            required = required,
            defaultEnabled = defaultEnabled,
            source = SmrtSource.SmrtStatic("https://example/$filename"),
            display = if (!hasDisplay) null else SmrtDisplay(
                incompatibleWith = incompatibleWith,
                role = role,
                requires = requires.map { SmrtRequirement(it) } +
                    optionalRequires.map { SmrtRequirement(it, optional = true) },
            ),
        )
    }

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

    @Test
    fun `applyToggle enabling a mod pulls its required deps on, transitively`() {
        // A library can ship optional + default-off and follow its consumer on,
        // instead of being flat-required: consumer -> libA -> libB.
        val deps = listOf(
            mod("consumer.jar", required = false, defaultEnabled = false, requires = listOf("libA.jar")),
            mod("libA.jar", required = false, defaultEnabled = false, requires = listOf("libB.jar")),
            mod("libB.jar", required = false, defaultEnabled = false),
        )
        val current = mapOf("consumer.jar" to false, "libA.jar" to false, "libB.jar" to false)
        val after = OptionalContentRules.applyToggle(deps, current, "consumer.jar", true)
        assertEquals(true, after["consumer.jar"])
        assertEquals(true, after["libA.jar"], "direct required dep follows on")
        assertEquals(true, after["libB.jar"], "transitive required dep follows on")
    }

    @Test
    fun `applyToggle does not follow optional (soft) requires`() {
        val deps = listOf(
            mod("consumer.jar", required = false, defaultEnabled = false, optionalRequires = listOf("soft.jar")),
            mod("soft.jar", required = false, defaultEnabled = false),
        )
        val after = OptionalContentRules.applyToggle(deps, mapOf("consumer.jar" to false, "soft.jar" to false), "consumer.jar", true)
        assertEquals(true, after["consumer.jar"])
        assertEquals(false, after["soft.jar"], "a soft (optional) requires must not be force-enabled")
    }

    @Test
    fun `applyToggle enabling one role member disables the others in that role`() {
        val viewers = listOf(
            mod("jei.jar", required = false, defaultEnabled = true, role = "recipe_viewer"),
            mod("rei.jar", required = false, defaultEnabled = false, role = "recipe_viewer"),
            mod("unrelated.jar", required = false, defaultEnabled = true),
        )
        val current = mapOf("jei.jar" to true, "rei.jar" to false, "unrelated.jar" to true)
        val after = OptionalContentRules.applyToggle(viewers, current, "rei.jar", true)
        assertEquals(true, after["rei.jar"])
        assertEquals(false, after["jei.jar"], "one active per interchangeable role")
        assertEquals(true, after["unrelated.jar"], "a different role is untouched")
    }

    @Test
    fun `applyToggle survives a requires cycle`() {
        // A bad manifest with a -> b -> a must terminate, not loop.
        val cyclic = listOf(
            mod("a.jar", required = false, defaultEnabled = false, requires = listOf("b.jar")),
            mod("b.jar", required = false, defaultEnabled = false, requires = listOf("a.jar")),
        )
        val after = OptionalContentRules.applyToggle(cyclic, mapOf("a.jar" to false, "b.jar" to false), "a.jar", true)
        assertEquals(true, after["a.jar"])
        assertEquals(true, after["b.jar"])
    }
}

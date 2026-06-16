package hivens.ui.screens

import hivens.core.data.InstanceProfile
import hivens.core.data.OptionalMod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerSettingsStateTest {

    @Test
    fun assembleProfileMapsEditorFieldsOntoBase() {
        val out = assembleProfile(
            base = InstanceProfile(serverId = "Industrial"),
            javaPath = "/usr/bin/java",
            memoryMb = 8192,
            isAutoMode = false,
            jvmArgs = "-XX:+UseZGC",
            winWidth = "1920",
            winHeight = "1080",
            fullScreen = true,
            autoConnect = false,
            modStates = mapOf("optifine" to true, "shaders" to false),
        )
        assertEquals("/usr/bin/java", out.javaPath)
        assertEquals(8192, out.memoryMb)
        assertTrue(out.fixedMemory) // !isAutoMode
        assertEquals("-XX:+UseZGC", out.jvmArgs)
        assertEquals(1920, out.windowWidth)
        assertEquals(1080, out.windowHeight)
        assertTrue(out.fullScreen)
        assertFalse(out.autoConnect)
        assertEquals(true, out.optionalModsState["optifine"])
        assertEquals(false, out.optionalModsState["shaders"])
        assertEquals("Industrial", out.serverId) // untouched base field survives
    }

    @Test
    fun assembleProfileBlankTextCollapsesToNullAndAutoUnpins() {
        val out = assembleProfile(
            base = InstanceProfile(),
            javaPath = "   ",
            memoryMb = 4096,
            isAutoMode = true,
            jvmArgs = "",
            winWidth = "925",
            winHeight = "530",
            fullScreen = false,
            autoConnect = true,
            modStates = emptyMap(),
        )
        assertNull(out.javaPath)
        assertNull(out.jvmArgs)
        assertFalse(out.fixedMemory) // auto mode -> not pinned
    }

    @Test
    fun assembleProfileBadDimensionsFallBackToDefaults() {
        val out = assembleProfile(
            base = InstanceProfile(),
            javaPath = "",
            memoryMb = 4096,
            isAutoMode = true,
            jvmArgs = "",
            winWidth = "abc",
            winHeight = "",
            fullScreen = false,
            autoConnect = true,
            modStates = emptyMap(),
        )
        assertEquals(925, out.windowWidth)
        assertEquals(530, out.windowHeight)
    }

    @Test
    fun enablingModDisablesItsDeclaredExclusions() {
        val states = mutableMapOf("jei" to true, "rei" to true)
        applyModToggle(states, OptionalMod(id = "emi", excludings = listOf("jei", "rei")), enabled = true)
        assertEquals(true, states["emi"])
        assertEquals(false, states["jei"])
        assertEquals(false, states["rei"])
    }

    @Test
    fun disablingModNeverCascades() {
        val states = mutableMapOf("jei" to true)
        applyModToggle(states, OptionalMod(id = "emi", excludings = listOf("jei")), enabled = false)
        assertEquals(false, states["emi"])
        assertEquals(true, states["jei"]) // disable leaves the excluded mod alone
    }
}

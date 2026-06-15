package hivens.core.platform

import kotlin.test.Test
import kotlin.test.assertEquals

class OSTest {

    // -- Platform.classify: os.name -> family ------------------------------

    @Test
    fun `classify folds Windows variants`() {
        assertEquals(Platform.WINDOWS, Platform.classify("Windows 10"))
        assertEquals(Platform.WINDOWS, Platform.classify("Windows 11"))
        assertEquals(Platform.WINDOWS, Platform.classify("Windows Server 2019"))
    }

    @Test
    fun `classify folds macOS, Darwin and osx`() {
        assertEquals(Platform.MACOS, Platform.classify("Mac OS X"))
        // Eclipse OpenJ9 reports Darwin; the old per-service ladders missed it
        // and mis-tagged the host as unknown.
        assertEquals(Platform.MACOS, Platform.classify("Darwin"))
        assertEquals(Platform.MACOS, Platform.classify("osx"))
    }

    @Test
    fun `classify folds the unix family into linux`() {
        assertEquals(Platform.LINUX, Platform.classify("Linux"))
        assertEquals(Platform.LINUX, Platform.classify("AIX"))
    }

    @Test
    fun `classify maps an unrecognised name to unknown`() {
        assertEquals(Platform.UNKNOWN, Platform.classify("Plan9"))
        assertEquals(Platform.UNKNOWN, Platform.classify(""))
    }

    // -- Platform mappers: one classifier, per-consumer tokens -------------

    @Test
    fun `windows mappers`() {
        val p = Platform.WINDOWS
        assertEquals("windows", p.mojang)
        assertEquals("win", p.bellsoft)
        assertEquals("windows", p.lwjgl)
        assertEquals("Windows", p.display)
    }

    @Test
    fun `macOS mappers`() {
        val p = Platform.MACOS
        assertEquals("osx", p.mojang)
        assertEquals("mac", p.bellsoft)
        assertEquals("macos", p.lwjgl)
        assertEquals("macOS", p.display)
    }

    @Test
    fun `linux mappers`() {
        val p = Platform.LINUX
        assertEquals("linux", p.mojang)
        assertEquals("linux", p.bellsoft)
        assertEquals("linux", p.lwjgl)
        assertEquals("Linux", p.display)
    }

    @Test
    fun `unknown mappers fall back to safe tokens`() {
        val p = Platform.UNKNOWN
        // Mojang ships no unknown build; linux is the historical fallback.
        assertEquals("linux", p.mojang)
        assertEquals("unknown", p.bellsoft)
        assertEquals("unknown", p.lwjgl)
        assertEquals("Unknown", p.display)
    }

    // -- Arch.classify + bellsoft token ------------------------------------

    @Test
    fun `classify folds arm64 spellings`() {
        assertEquals(Arch.ARM64, Arch.classify("aarch64"))
        assertEquals(Arch.ARM64, Arch.classify("arm64"))
        assertEquals("arm64", Arch.classify("aarch64").bellsoft)
    }

    @Test
    fun `classify folds 64-bit x86 spellings`() {
        assertEquals(Arch.X64, Arch.classify("amd64"))
        assertEquals(Arch.X64, Arch.classify("x86_64"))
        assertEquals("x64", Arch.classify("amd64").bellsoft)
    }

    @Test
    fun `classify folds 32-bit x86 spellings`() {
        assertEquals(Arch.X86, Arch.classify("i386"))
        assertEquals(Arch.X86, Arch.classify("i686"))
        assertEquals("x32", Arch.classify("i386").bellsoft)
    }

    @Test
    fun `unknown arch defaults to x64 for downloads`() {
        assertEquals(Arch.UNKNOWN, Arch.classify("sparc"))
        assertEquals(Arch.UNKNOWN, Arch.classify(""))
        assertEquals("x64", Arch.UNKNOWN.bellsoft)
    }

    // -- OS reads the live host properties ---------------------------------

    @Test
    fun `OS reflects the host os and arch properties`() {
        assertEquals(Platform.classify(System.getProperty("os.name", "")), OS.platform)
        assertEquals(Arch.classify(System.getProperty("os.arch", "")), OS.arch)
        assertEquals(OS.platform == Platform.WINDOWS, OS.isWindows)
        assertEquals(OS.platform == Platform.MACOS, OS.isMacOS)
        assertEquals(OS.platform == Platform.LINUX, OS.isLinux)
        assertEquals(OS.platform.display, OS.getName())
    }
}

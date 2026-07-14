package hivens.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CliArgsTest {

    @Test
    fun `no args is help`() {
        assertIs<CliCommand.Help>(parseArgs(emptyArray()))
    }

    @Test
    fun `help aliases`() {
        listOf("help", "--help", "-h").forEach {
            assertIs<CliCommand.Help>(parseArgs(arrayOf(it)), "alias=$it")
        }
    }

    @Test
    fun `version aliases`() {
        listOf("version", "--version", "-v").forEach {
            assertIs<CliCommand.Version>(parseArgs(arrayOf(it)), "alias=$it")
        }
    }

    @Test
    fun `list command`() {
        assertIs<CliCommand.ListPacks>(parseArgs(arrayOf("list")))
    }

    @Test
    fun `unknown command is invalid`() {
        assertIs<CliCommand.Invalid>(parseArgs(arrayOf("frobnicate")))
    }

    @Test
    fun `launch with id defaults to offline provider`() {
        val cmd = parseArgs(arrayOf("launch", "pack-1"))
        assertIs<CliCommand.Launch>(cmd)
        assertEquals("pack-1", cmd.packId)
        assertEquals("offline", cmd.provider)
        assertEquals(null, cmd.user)
        assertEquals(false, cmd.dryRun)
    }

    @Test
    fun `launch without id is invalid`() {
        assertIs<CliCommand.Invalid>(parseArgs(arrayOf("launch")))
    }

    @Test
    fun `launch parses all options`() {
        val cmd = parseArgs(arrayOf("launch", "pack-1", "--provider", "smartycraft", "--user", "Bob", "--dry-run"))
        assertIs<CliCommand.Launch>(cmd)
        assertEquals("pack-1", cmd.packId)
        assertEquals("smartycraft", cmd.provider)
        assertEquals("Bob", cmd.user)
        assertTrue(cmd.dryRun)
    }

    @Test
    fun `launch accepts flags before the positional id`() {
        val cmd = parseArgs(arrayOf("launch", "--provider", "microsoft", "pack-9"))
        assertIs<CliCommand.Launch>(cmd)
        assertEquals("pack-9", cmd.packId)
        assertEquals("microsoft", cmd.provider)
    }

    @Test
    fun `unknown provider is invalid`() {
        assertIs<CliCommand.Invalid>(parseArgs(arrayOf("launch", "p", "--provider", "steam")))
    }

    @Test
    fun `flag missing its value is invalid`() {
        assertIs<CliCommand.Invalid>(parseArgs(arrayOf("launch", "p", "--provider")))
        assertIs<CliCommand.Invalid>(parseArgs(arrayOf("launch", "p", "--user")))
    }

    @Test
    fun `unknown flag is invalid`() {
        assertIs<CliCommand.Invalid>(parseArgs(arrayOf("launch", "p", "--turbo")))
    }

    @Test
    fun `second positional argument is invalid`() {
        assertIs<CliCommand.Invalid>(parseArgs(arrayOf("launch", "p1", "p2")))
    }

    @Test
    fun `usage text lists every command`() {
        listOf("list", "launch", "version", "help").forEach {
            assertTrue(it in USAGE, "USAGE missing '$it'")
        }
    }
}

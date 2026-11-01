package hivens.launcher.instance

import hivens.core.data.GameMode
import hivens.core.data.WorldDimension
import hivens.launcher.nbt.Nbt
import hivens.launcher.nbt.NbtCompound
import hivens.launcher.nbt.NbtValue
import hivens.launcher.nbt.RootCompound
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorldScannerTest {

    private lateinit var instanceDir: Path
    private val scanner = WorldScanner()

    @BeforeTest
    fun setUp() {
        instanceDir = Files.createTempDirectory("world-scanner-test-")
        instanceDir.toFile().deleteOnExit()
    }

    @AfterTest
    fun tearDown() {
        instanceDir.toFile().deleteRecursively()
    }

    @Test
    fun `missing saves dir returns empty list`() = runBlocking {
        assertEquals(emptyList(), scanner.scan(instanceDir))
    }

    @Test
    fun `world with all fields decodes fully`() = runBlocking {
        writeWorld(
            name = "My World",
            displayName = "Pretty Name",
            lastPlayed = 1716639082000L,
            seed = 12345L,
            gameModeInt = 1, // creative
            mcVersionName = "1.20.1",
            withRegion = true,
            withNether = true,
            withEnd = false,
            withIcon = true,
        )
        val worlds = scanner.scan(instanceDir)
        assertEquals(1, worlds.size)
        val w = worlds[0]
        assertEquals("My World", w.dirName)
        assertEquals("Pretty Name", w.displayName)
        assertEquals(1716639082000L, w.lastPlayedEpochMs)
        assertEquals(12345L, w.seed)
        assertEquals(GameMode.Creative, w.gameMode)
        assertEquals("1.20.1", w.mcVersion)
        assertEquals(listOf(WorldDimension.Overworld, WorldDimension.Nether), w.dimensions)
        assertTrue(w.iconPath != null && w.iconPath!!.endsWith("icon.png"))
    }

    @Test
    fun `world with no level dat is skipped silently`() = runBlocking {
        val saves = Files.createDirectories(instanceDir.resolve("saves"))
        Files.createDirectory(saves.resolve("EmptyDir"))
        Files.createDirectory(saves.resolve("EmptyDir/region")) // even with region, no level.dat -> skip
        assertEquals(emptyList(), scanner.scan(instanceDir))
    }

    @Test
    fun `world with corrupt level dat is skipped, others survive`() = runBlocking {
        writeWorld(name = "Good", displayName = "Good", lastPlayed = 100L, withRegion = true)
        val saves = instanceDir.resolve("saves")
        val badDir = Files.createDirectory(saves.resolve("Bad"))
        Files.writeString(badDir.resolve("level.dat"), "this is not NBT")

        val worlds = scanner.scan(instanceDir)
        assertEquals(1, worlds.size)
        assertEquals("Good", worlds[0].dirName)
    }

    @Test
    fun `worlds are sorted by lastPlayed descending`() = runBlocking {
        writeWorld(name = "Old", lastPlayed = 100L, withRegion = true)
        writeWorld(name = "Newest", lastPlayed = 999L, withRegion = true)
        writeWorld(name = "Middle", lastPlayed = 500L, withRegion = true)

        val names = scanner.scan(instanceDir).map { it.dirName }
        assertEquals(listOf("Newest", "Middle", "Old"), names)
    }

    @Test
    fun `seed falls back to WorldGenSettings dot seed when RandomSeed missing`() = runBlocking {
        writeWorld(
            name = "Modern",
            seed = null,
            modernSeed = 777L,
            withRegion = true,
        )
        val w = scanner.scan(instanceDir).single()
        assertEquals(777L, w.seed)
    }

    @Test
    fun `missing GameType and Version yield nulls`() = runBlocking {
        writeWorld(
            name = "Bare",
            gameModeInt = null,
            mcVersionName = null,
            withRegion = true,
        )
        val w = scanner.scan(instanceDir).single()
        assertNull(w.gameMode)
        assertNull(w.mcVersion)
    }

    @Test
    fun `modded dimensions folder marks Other`() = runBlocking {
        writeWorld(
            name = "Modded",
            withRegion = true,
            withModdedDimensions = true,
        )
        val w = scanner.scan(instanceDir).single()
        assertTrue(WorldDimension.Other in w.dimensions)
    }

    private fun writeWorld(
        name: String,
        displayName: String = name,
        lastPlayed: Long = 0L,
        seed: Long? = null,
        modernSeed: Long? = null,
        gameModeInt: Int? = null,
        mcVersionName: String? = null,
        withRegion: Boolean = false,
        withNether: Boolean = false,
        withEnd: Boolean = false,
        withModdedDimensions: Boolean = false,
        withIcon: Boolean = false,
    ) {
        val saves = Files.createDirectories(instanceDir.resolve("saves"))
        val worldDir = Files.createDirectory(saves.resolve(name))
        if (withRegion)    Files.createDirectory(worldDir.resolve("region"))
        if (withNether)    Files.createDirectory(worldDir.resolve("DIM-1"))
        if (withEnd)       Files.createDirectory(worldDir.resolve("DIM1"))
        if (withModdedDimensions) {
            val dim = Files.createDirectories(worldDir.resolve("dimensions/mymod/myextra"))
            Files.createFile(dim.resolve("placeholder"))
        }
        if (withIcon)      Files.writeString(worldDir.resolve("icon.png"), "fake")

        val dataEntries = linkedMapOf<String, NbtValue>(
            "LevelName"  to NbtValue.String(displayName),
            "LastPlayed" to NbtValue.Long(lastPlayed),
        )
        if (seed != null)        dataEntries["RandomSeed"] = NbtValue.Long(seed)
        if (gameModeInt != null) dataEntries["GameType"]   = NbtValue.Int(gameModeInt)
        if (mcVersionName != null) {
            dataEntries["Version"] = NbtValue.Compound(NbtCompound(linkedMapOf(
                "Name" to NbtValue.String(mcVersionName),
                "Id"   to NbtValue.Int(3465),
            )))
        }
        if (modernSeed != null) {
            dataEntries["WorldGenSettings"] = NbtValue.Compound(NbtCompound(linkedMapOf(
                "seed" to NbtValue.Long(modernSeed),
            )))
        }
        val root = RootCompound(
            name = "",
            value = NbtCompound(linkedMapOf(
                "Data" to NbtValue.Compound(NbtCompound(dataEntries)),
            )),
        )
        Files.newOutputStream(worldDir.resolve("level.dat")).use { Nbt.write(it, root, gzipped = true) }
    }
}

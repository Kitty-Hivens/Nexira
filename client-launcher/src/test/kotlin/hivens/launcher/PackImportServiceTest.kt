package hivens.launcher

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PackImportServiceTest {

    private val temps = mutableListOf<Path>()

    @AfterTest
    fun cleanup() {
        temps.forEach { runCatching { Files.deleteIfExists(it) } }
    }

    private fun zipWith(vararg entries: String): Path {
        val f = Files.createTempFile("import-test", ".zip").also { temps.add(it) }
        ZipOutputStream(Files.newOutputStream(f)).use { zos ->
            entries.forEach { name ->
                zos.putNextEntry(ZipEntry(name)); zos.write("{}".toByteArray()); zos.closeEntry()
            }
        }
        return f
    }

    @Test
    fun `mrpack index is detected as Mrpack`() {
        assertEquals(
            PackArchiveKind.Mrpack,
            detectPackArchiveKind(zipWith("modrinth.index.json", "overrides/config/x.txt")),
        )
    }

    @Test
    fun `curseforge manifest is detected as CurseForge`() {
        assertEquals(
            PackArchiveKind.CurseForge,
            detectPackArchiveKind(zipWith("manifest.json", "overrides/mods/x.jar")),
        )
    }

    @Test
    fun `an archive with neither index is Unknown`() {
        assertEquals(PackArchiveKind.Unknown, detectPackArchiveKind(zipWith("readme.txt")))
    }

    @Test
    fun `the modrinth index wins when both indexes are present`() {
        assertEquals(
            PackArchiveKind.Mrpack,
            detectPackArchiveKind(zipWith("modrinth.index.json", "manifest.json")),
        )
    }
}

package hivens.launcher

import hivens.core.data.PackInstance
import hivens.launcher.curseforge.CurseForgeZipInstaller
import hivens.launcher.mrpack.MrpackInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * Imports a pack from a local archive the user picked (or handed to Nexira via
 * a file association later). Routes by [detectPackArchiveKind], which sniffs the
 * zip's index entry rather than the file extension: a Modrinth `.mrpack` carries
 * `modrinth.index.json`, a CurseForge export carries `manifest.json`. Modrinth
 * imports install fully; CurseForge is best-effort (F2) because resolving its
 * file ids needs the CF API.
 */
class PackImportService(
    private val mrpackInstaller: MrpackInstaller,
    private val cfInstaller: CurseForgeZipInstaller,
) {
    private val log = LoggerFactory.getLogger(PackImportService::class.java)

    suspend fun import(
        file: Path,
        progress: (current: Int, total: Int, filename: String) -> Unit = { _, _, _ -> },
    ): PackInstance {
        val kind = withContext(Dispatchers.IO) { detectPackArchiveKind(file) }
        log.info("import: {} detected as {}", file.fileName, kind)
        return when (kind) {
            PackArchiveKind.Mrpack -> mrpackInstaller.install(file, source = null, progress = progress)
            PackArchiveKind.CurseForge -> cfInstaller.install(file, progress)
            PackArchiveKind.Unknown -> throw IOException(
                "Unrecognized pack archive '${file.fileName}': expected a Modrinth .mrpack " +
                    "(modrinth.index.json) or a CurseForge export (manifest.json)",
            )
        }
    }
}

internal enum class PackArchiveKind { Mrpack, CurseForge, Unknown }

/** Classify a pack archive by the index file at its root (extension-agnostic). */
internal fun detectPackArchiveKind(file: Path): PackArchiveKind = ZipFile(file.toFile()).use { zip ->
    when {
        zip.getEntry("modrinth.index.json") != null -> PackArchiveKind.Mrpack
        zip.getEntry("manifest.json") != null -> PackArchiveKind.CurseForge
        else -> PackArchiveKind.Unknown
    }
}

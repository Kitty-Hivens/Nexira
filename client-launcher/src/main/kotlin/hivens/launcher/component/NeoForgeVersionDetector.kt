package hivens.launcher.component

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.Manifest
import java.util.zip.ZipFile
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/**
 * Auto-detection of NeoForge `--fml.*` argument values from a populated
 * `libraries-{mcVersion}/` directory.
 *
 * The official launcher's hardcoded q.java constants drift every time
 * Серафим bumps NeoForge/FML on the server. We mirror those constants
 * from `smrt-deco` syncs and have already shipped 21.1.506 / 4.0.42 by
 * hand — once. The version values are right there in the manifest sync
 * output: directory names under `net/neoforged/neoforge/` and
 * `net/neoforged/fancymodloader/loader/`, plus the embedded
 * `Implementation-Version` inside the universal jar's `MANIFEST.MF`.
 *
 * Reading them directly removes the manual bump as a class of bug.
 *
 * Returns null on any unrecoverable layout surprise; caller falls back
 * to baked-in defaults.
 */
internal class NeoForgeVersionDetector {
    private val log = LoggerFactory.getLogger(NeoForgeVersionDetector::class.java)

    data class FmlArgs(
        val neoForgeVersion: String,
        val fmlVersion: String,
        val mcVersion: String,
        val neoFormVersion: String,
    ) {
        fun toMap(): Map<String, String> = mapOf(
            "neoForgeVersion" to neoForgeVersion,
            "fmlVersion" to fmlVersion,
            "mcVersion" to mcVersion,
            "neoFormVersion" to neoFormVersion,
        )
    }

    fun detect(clientRoot: Path, mcVersion: String): FmlArgs? {
        val libDir = pickLibrariesDir(clientRoot, mcVersion) ?: return null

        val neoForgeVersion = highestVersionDir(libDir.resolve("net/neoforged/neoforge")) ?: run {
            log.warn("NeoForge auto-detect: no version dir under net/neoforged/neoforge")
            return null
        }
        val fmlVersion = highestVersionDir(libDir.resolve("net/neoforged/fancymodloader/loader")) ?: run {
            log.warn("NeoForge auto-detect: no version dir under net/neoforged/fancymodloader/loader")
            return null
        }

        val universalJar = libDir
            .resolve("net/neoforged/neoforge/$neoForgeVersion/neoforge-$neoForgeVersion-universal.jar")
        val neoFormVersion = readNeoFormVersion(universalJar, mcVersion) ?: run {
            log.warn("NeoForge auto-detect: failed to read neoForm version from $universalJar")
            return null
        }

        return FmlArgs(
            neoForgeVersion = neoForgeVersion,
            fmlVersion = fmlVersion,
            mcVersion = mcVersion,
            neoFormVersion = neoFormVersion,
        ).also { log.info("NeoForge auto-detected: {}", it) }
    }

    private fun pickLibrariesDir(clientRoot: Path, mcVersion: String): Path? {
        val custom = clientRoot.resolve("libraries-$mcVersion")
        val standard = clientRoot.resolve("libraries")
        return when {
            custom.resolve("net/neoforged").isDirectory() -> custom
            standard.resolve("net/neoforged").isDirectory() -> standard
            else -> null
        }
    }

    private fun highestVersionDir(parent: Path): String? {
        if (!parent.isDirectory()) return null
        return Files.list(parent).use { stream ->
            stream
                .filter { it.isDirectory() }
                .map { it.name }
                .sorted(VersionComparator.reversed())
                .findFirst()
                .orElse(null)
        }
    }

    private fun readNeoFormVersion(universalJar: Path, mcVersion: String): String? {
        if (!Files.isRegularFile(universalJar)) return null
        return runCatching {
            ZipFile(universalJar.toFile()).use { zip ->
                val entry = zip.getEntry("META-INF/MANIFEST.MF") ?: return@use null
                val manifest = zip.getInputStream(entry).use { Manifest(it) }
                val section = manifest.getAttributes("net/neoforged/neoforge/versions/neoform/")
                    ?: return@use null
                val impl = section.getValue("Implementation-Version") ?: return@use null
                impl.removePrefix("$mcVersion-").takeIf { it != impl }
            }
        }.getOrElse {
            log.debug("Failed to parse MANIFEST.MF in {}: {}", universalJar, it.message)
            null
        }
    }

    /**
     * Compares strings like `21.1.506`, `4.0.42`, `1.21.1` part-by-part as
     * integers when possible, alphabetically otherwise. Sufficient for the
     * NeoForge / FML version schemes — neither uses pre-release tags.
     */
    private object VersionComparator : Comparator<String> {
        override fun compare(a: String, b: String): Int {
            val pa = a.split('.')
            val pb = b.split('.')
            for (i in 0 until maxOf(pa.size, pb.size)) {
                val sa = pa.getOrNull(i) ?: "0"
                val sb = pb.getOrNull(i) ?: "0"
                val na = sa.toIntOrNull()
                val nb = sb.toIntOrNull()
                val cmp = if (na != null && nb != null) na.compareTo(nb) else sa.compareTo(sb)
                if (cmp != 0) return cmp
            }
            return 0
        }
    }
}

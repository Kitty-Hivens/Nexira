package hivens.ui.host

import hivens.core.data.ModuleStore
import hivens.core.data.NewerBuildData
import hivens.core.io.AtomicFiles
import hivens.widget.api.HostFiles
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * The launcher's data directory as the modules compiled in keep their own files there: written whole
 * through the launcher's one atomic write, and only inside the directory, saved by [save], the writer
 * the interface's other choices go through, and a file a newer build wrote put on record, under the
 * name its module gives it, for the notice that says what this session changes in it is not kept.
 */
internal class LauncherFiles(
    override val dataDir: Path,
    private val save: (what: String, block: () -> Unit) -> Unit = { _, block -> block() },
) : HostFiles {

    override fun publish(file: Path, content: String) = AtomicFiles.writeString(inside(file), content)

    override fun write(what: String, block: () -> Unit) = save(what, block)

    override fun newerBuild(file: Path, module: String, name: String) {
        log.warn("{} of the module '{}' was written by a newer build and is not written this session", file, module)
        NewerBuildData.record(ModuleStore(module, name))
    }

    /**
     * [file] where it is under [dataDir], or a refusal. A link on the way there, the file itself
     * among them, is followed, so one that leads out of the directory is refused as well.
     */
    private fun inside(file: Path): Path {
        val root = dataDir.toAbsolutePath().normalize()
        val target = file.toAbsolutePath().normalize()
        require(target.startsWith(root) && target != root) { "$file is not inside the data directory $root" }
        val realRoot = if (Files.exists(root)) root.toRealPath() else root
        var existing: Path = target
        while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) existing = existing.parent ?: return target
        val real = existing.toRealPath().resolve(existing.relativize(target)).normalize()
        require(real.startsWith(realRoot)) { "$file leads out of the data directory $root, to $real" }
        return target
    }

    private companion object {
        val log = LoggerFactory.getLogger(LauncherFiles::class.java)
    }
}

package hivens.launcher.smrt

import hivens.core.io.AtomicFiles
import hivens.core.io.fileOpRetry
import hivens.core.io.resolveWithinRoot
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * What a mod's two names on disk still owe the player's choice, kept beside the
 * instance until something can carry it out. A resource or shader pack the pack
 * lets the player switch is owed the same way, see [Op.dir].
 *
 * A mod switched off lives at `mods/<name>.disabled`, on at `mods/<name>`, and
 * getting from one to the other is a rename or a delete. Both fail while a process
 * holds the jar, which on Windows is exactly what a running game does. The choice
 * itself is saved in the registry, but turning it back into file names needs the
 * pack's manifest, and a launch has no manifest to hand and no sync to run. So a
 * move or a drop that did not happen is written here, and the next launch performs
 * it before the roster is checked: the game that held the file has exited by then.
 *
 * One entry per mod, keyed by its active name. Whatever last decided about that
 * mod replaces what an earlier attempt left, and a later success clears it, so an
 * entry never outlives the choice it stands for.
 *
 * Callers hold [hivens.core.io.InstanceMutationLock] for the instance.
 */
internal object PendingVariants {
    private val log = LoggerFactory.getLogger(PendingVariants::class.java)

    const val FILE_NAME = ".nexira-pending"
    private const val MODS = "mods"
    private const val DISABLED = ".disabled"

    /**
     * The folders an entry may name a file in. The game holds a selected resource or
     * shader pack's zip open the way it holds a jar, so a switch made while it runs
     * is refused there too, and nothing but a launch comes round again after it.
     */
    private val DIRS = setOf(MODS, "resourcepacks", "shaderpacks")

    /** What one file still needs, as names in [dir]. */
    sealed interface Op {
        /** The folder the names are in, `mods` unless said otherwise. */
        val dir: String

        /**
         * What keys the entry: a mod's active name, as the mod's callers name it, or
         * an asset's path under the instance, its `dest`.
         */
        val key: String

        /**
         * Put the file's bytes at [to]. From [from] when [to] is missing, since the
         * rename itself is what did not happen. When both exist, [from] is the one
         * the choice no longer wants.
         */
        data class Move(val from: String, val to: String, override val dir: String = MODS) : Op {
            override val key get() = keyOf(dir, to)
        }

        /**
         * Remove [name], a leftover of a file whose current bytes arrived under its
         * other name. Only while that other name is there: without it, this is the
         * only copy, and it is kept.
         */
        data class Drop(val name: String, override val dir: String = MODS) : Op {
            override val key get() = keyOf(dir, name)
        }
    }

    private fun keyOf(dir: String, name: String): String =
        if (dir == MODS) activeName(name) else "$dir/${activeName(name)}"

    fun activeName(name: String): String = name.removeSuffix(DISABLED)

    fun siblingOf(name: String): String =
        if (name.endsWith(DISABLED)) name.removeSuffix(DISABLED) else name + DISABLED

    fun read(clientDir: Path): Map<String, Op> {
        val file = clientDir.resolve(FILE_NAME)
        if (!Files.isRegularFile(file)) return emptyMap()
        return runCatching {
            Files.readAllLines(file).mapNotNull(::parse).associateBy { it.key }
        }.getOrElse {
            log.warn("pending variants: unreadable {}, ignoring it", file, it)
            emptyMap()
        }
    }

    /** Records [set] and forgets [cleared], both by key. The file goes when nothing is left. */
    fun update(clientDir: Path, set: Collection<Op> = emptyList(), cleared: Collection<String> = emptyList()) {
        if (set.isEmpty() && cleared.isEmpty()) return
        val current = read(clientDir).toMutableMap()
        val before = current.toMap()
        cleared.forEach { current.remove(activeName(it)) }
        set.forEach { current[it.key] = it }
        if (current == before) return
        write(clientDir, current.values)
    }

    /**
     * Carries out every entry that can be carried out now, and keeps the rest.
     *
     * @return the names still owed something, for the caller to report.
     */
    fun settle(clientDir: Path): List<String> {
        val ops = read(clientDir)
        if (ops.isEmpty()) return emptyList()
        val left = ops.values.filterNot { op -> runCatching { perform(clientDir.resolve(op.dir), op) }.getOrElse { false } }
        write(clientDir, left)
        if (left.size < ops.size) log.info("pending variants: settled {} of {} in {}", ops.size - left.size, ops.size, clientDir.fileName)
        return left.map { it.key }
    }

    /** @return true when the entry is done with, performed or no longer applicable. */
    private fun perform(dir: Path, op: Op): Boolean = when (op) {
        is Op.Move -> {
            val from = resolveWithinRoot(dir, op.from, "pending ${op.from}")
            val to = resolveWithinRoot(dir, op.to, "pending ${op.to}")
            when {
                !Files.exists(from) -> true
                Files.exists(to) -> { fileOpRetry("pending drop ${op.from}") { Files.delete(from) }; true }
                else -> { fileOpRetry("pending move ${op.from}") { Files.move(from, to) }; true }
            }
        }
        is Op.Drop -> {
            val stale = resolveWithinRoot(dir, op.name, "pending ${op.name}")
            val current = resolveWithinRoot(dir, siblingOf(op.name), "pending ${op.name}")
            if (Files.exists(stale) && Files.exists(current)) fileOpRetry("pending drop ${op.name}") { Files.delete(stale) }
            true
        }
    }

    private fun write(clientDir: Path, ops: Collection<Op>) {
        val file = clientDir.resolve(FILE_NAME)
        if (ops.isEmpty()) {
            runCatching { Files.deleteIfExists(file) }
            return
        }
        // Replaced whole, like the roster: a snapshot hardlinks it.
        AtomicFiles.writeString(file, ops.sortedBy { it.key }.joinToString("\n", transform = ::format))
    }

    private fun format(op: Op): String = when (op) {
        is Op.Move -> "move\t${op.dir}/${op.from}\t${op.dir}/${op.to}"
        is Op.Drop -> "drop\t${op.dir}/${op.name}"
    }

    private fun parse(line: String): Op? {
        val parts = line.trim().split('\t')
        // A folder this may name, and a bare file name in it: nothing deeper, nothing outside.
        fun path(i: Int): Pair<String, String>? {
            val raw = parts.getOrNull(i) ?: return null
            val dir = raw.substringBefore('/', "")
            val name = raw.substringAfter('/', "")
            return if (dir in DIRS && name.isNotEmpty() && '/' !in name && '\\' !in name) dir to name else null
        }
        return when (parts.firstOrNull()) {
            "move" -> {
                val (fromDir, from) = path(1) ?: return null
                val (toDir, to) = path(2) ?: return null
                // Only ever between one file's own two names.
                if (fromDir != toDir || siblingOf(from) != to) null else Op.Move(from, to, fromDir)
            }
            "drop" -> path(1)?.let { (dir, name) -> Op.Drop(name, dir) }
            else -> null
        }
    }
}

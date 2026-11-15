package hivens.launcher.sources

import hivens.core.api.catalogue.CataloguePack
import hivens.core.api.catalogue.CataloguePackVersion
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.core.net.RepairReport
import hivens.core.update.PackUpdater
import hivens.launcher.update.PackReattacher
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

/**
 * What a source does to the packs it installs, in the engine's own terms: installs one of
 * its builds as a new pack, moves a pack it installed between its builds, puts a pack
 * detached from it back on one of them, repairs a pack against the build it is on, and
 * names the cover it publishes for a pack.
 *
 * Every part is optional, and one a source leaves null is one it cannot do: the engine
 * offers nothing for it and refuses it with [SourceCannotException] when asked. Only a
 * source module compiled into the launcher brings it, on what its pack source opens
 * (`PackSourceContribution.open` in the catalogue API), which then answers for the source
 * as both its listing and this. What a jar's source opens is taken as a listing only,
 * whatever it implements. The engine reaches it through [SourceWorks] by the id every
 * pack record from the source carries.
 *
 * The machinery a source does this with is the engine's and stays generic: the apply
 * guard, the snapshots and the journal, the sync engine and file reconciling, the archive
 * format and its updater, the mod installers and the loaders.
 */
interface SourcePackWork {
    /** Installs a build as a new pack. Null where the source cannot install. */
    val installer: SourceInstaller? get() = null

    /** Moves a pack the source installed between its builds, and back to a snapshot. Null where it cannot. */
    val updater: PackUpdater? get() = null

    /** Puts a pack detached from the source back on one of its builds. Null where it cannot. */
    val reattacher: PackReattacher? get() = null

    /** Checks a pack against the build it is on and puts right what does not match. Null where it cannot. */
    val repairer: PackRepairer? get() = null

    /**
     * The cover the source publishes for its pack [packId]. Null where it publishes none.
     * Asked over the network where the source has to, and a failure is thrown.
     */
    suspend fun art(packId: String): PackArt? = null
}

/** What [SourcePackWork] holds of the four operations, which is all a source can be asked to do. */
val SourcePackWork.operations: Set<SourceOperation>
    get() = buildSet {
        if (installer != null) add(SourceOperation.Install)
        if (updater != null) add(SourceOperation.Update)
        if (reattacher != null) add(SourceOperation.Reattach)
        if (repairer != null) add(SourceOperation.Repair)
    }

/** An operation a source does to a pack, which it can do only where its work holds it. */
enum class SourceOperation {
    Install,
    Update,
    Reattach,
    Repair,
}

/** Installs one build of a source's pack as a new pack, see [SourcePackWork.installer]. */
fun interface SourceInstaller {
    /**
     * Installs [version] of [pack] as a new pack and registers it. [onReserveDir] is told
     * the instance directory before anything is written to it, so a cancelled install can
     * remove exactly what it left. Failures are thrown, and nothing is registered then.
     */
    suspend fun install(
        pack: CataloguePack,
        version: CataloguePackVersion,
        onReserveDir: (Path) -> Unit,
        progress: (current: Int, total: Int, filename: String) -> Unit,
    ): PackInstance
}

/** Repairs a pack against the build it is on, see [SourcePackWork.repairer]. */
fun interface PackRepairer {
    /** Measures [instance] against its build and puts right whatever does not match. */
    suspend fun repair(instance: PackInstance, progress: ((current: Int, total: Int, path: String) -> Unit)?): RepairReport
}

/** Where a pack's source stands for the engine. */
sealed interface SourceStatus {
    /**
     * The pack has no source to ask: one of the core's own origins, a pack made in the
     * launcher, imported or detached, or an old record from before sources were modules.
     */
    data object None : SourceStatus

    /** The pack's source is known by its id, and no module that is loaded brings it. */
    data class Missing(val origin: PackOrigin) : SourceStatus

    /** The pack's source is loaded, and [work] is what it can do. */
    data class Loaded(val work: SourcePackWork) : SourceStatus
}

/**
 * The sources the engine installs from, updates through and reattaches to, by the id the
 * pack records carry.
 *
 * What answers is handed in by the host once the sources are taken ([attach]), since the
 * modules that bring them load after the engine is made. Until then, and wherever the
 * host hands in nothing, every source is missing. A source answers each time it is asked,
 * so a module brought in, taken away or loaded again is what the next question reads.
 */
class SourceWorks(lookup: (PackOrigin) -> SourcePackWork? = { null }) {
    private val log = LoggerFactory.getLogger(SourceWorks::class.java)

    @Volatile
    private var lookup: (PackOrigin) -> SourcePackWork? = lookup

    /** Answers every later question with [lookup]: the work of a loaded source, null for one that is not. */
    fun attach(lookup: (PackOrigin) -> SourcePackWork?) {
        this.lookup = lookup
    }

    /**
     * Where the source of a pack from [origin] stands. A source whose own code fails while
     * it is looked up, an exception or a class its module cannot link, is logged and taken
     * as missing, so one broken module does not take the engine with it. Cancellation and
     * an error of the virtual machine are not the source's to answer for and go on.
     */
    fun status(origin: PackOrigin): SourceStatus {
        if (origin in PackOrigin.Reserved) return SourceStatus.None
        val work = try {
            lookup(origin)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("The source '{}' could not be looked up, and it is taken as missing", origin, e)
            null
        } catch (e: LinkageError) {
            log.warn("The source '{}' could not be linked, and it is taken as missing", origin, e)
            null
        }
        return work?.let(SourceStatus::Loaded) ?: SourceStatus.Missing(origin)
    }

    /** The work of the loaded source of [origin], or null where it is not loaded or the pack has none. */
    fun work(origin: PackOrigin): SourcePackWork? = (status(origin) as? SourceStatus.Loaded)?.work

    companion object {
        /** A loaded source that only lists, and does nothing to the packs it lists. */
        val ListingOnly: SourcePackWork = object : SourcePackWork {}

        /** Sources that answer with [works] and nothing else, for a composition that knows them up front. */
        fun of(vararg works: Pair<PackOrigin, SourcePackWork>): SourceWorks {
            val byOrigin = works.toMap()
            return SourceWorks { origin -> byOrigin[origin] }
        }
    }
}

/** Something was asked of a pack's source that it could not do, and nothing was done. */
sealed class SourceRefusedException(val origin: PackOrigin, message: String) : IllegalStateException(message)

/** The source of a pack from [origin] is known by its id, and no module that is loaded brings it. Nothing was done. */
class SourceMissingException(origin: PackOrigin) :
    SourceRefusedException(origin, "the source '$origin' this pack comes from is not loaded, so nothing was done")

/** The source [origin] is loaded and cannot do [operation]: its work holds none. Nothing was done. */
class SourceCannotException(origin: PackOrigin, val operation: SourceOperation) :
    SourceRefusedException(origin, "the source '$origin' cannot ${operation.name.lowercase()} its packs, so nothing was done")

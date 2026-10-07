package hivens.ui.legacy

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import hivens.core.diag.ActionRing
import hivens.launcher.legacy.RetiredClient
import hivens.launcher.legacy.RetiredClientAdopter
import hivens.launcher.legacy.RetiredClientScanner
import hivens.launcher.legacy.RetiredDataSweeper
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

/**
 * What the reader has decided about one leftover client.
 *
 * [Keep] is the default and is not a null choice: it is the answer for somebody
 * who wants to look at the folder themselves first, and the surface has to let
 * them leave without doing anything.
 */
enum class RetiredChoice { Keep, Adopt, Delete }

/**
 * One row of the leftover-clients surface.
 *
 * [mcVersion] and [loader] start at what the scanner read and are the reader's
 * to change, because the tree lies: one real client carries a Fabric loader log
 * beside a Forge-only mod, and adopting on the strength of that would provision
 * the wrong loader for a pack nobody could then launch.
 */
class RetiredRow(val client: RetiredClient) {
    var choice by mutableStateOf(RetiredChoice.Keep)
    var mcVersion by mutableStateOf(client.mcVersion.orEmpty())
    var loader by mutableStateOf(client.loader.orEmpty())

    /** Set while this row is being worked on, so the surface can name what it is doing. */
    var busy by mutableStateOf(false)

    /** Filled once the row is done with, successfully or not. */
    var outcome by mutableStateOf<RetiredOutcome?>(null)

    /**
     * Whether this row could be adopted as it stands.
     *
     * Not a gate on CHOOSING adoption. The version field only appears once the
     * row is set to adopt, so refusing the choice without a version left the one
     * folder that needs typing as the one folder nobody could type into.
     */
    val adoptable: Boolean get() = mcVersion.isNotBlank()
}

sealed interface RetiredOutcome {
    data class Adopted(val packName: String, val sourceKept: Boolean) : RetiredOutcome
    data object Deleted : RetiredOutcome

    /**
     * Some of the tree went and some did not. Its own outcome because the two
     * readings send a person to different places: nothing happened means try
     * again, half of it happened means go and look at what is left.
     */
    data object PartlyDeleted : RetiredOutcome
    data class Failed(val reason: String) : RetiredOutcome
}

/**
 * The leftover-clients chore, as state a surface renders and a test can drive.
 *
 * The order is the whole design. Adoption runs first and the sweep second, so a
 * tree is only ever removed after the thing that was supposed to inherit it
 * exists -- and an adoption that could not place every file keeps its source,
 * because the content is hardlinked and deleting the source of an incomplete
 * transfer turns a gap that could still be fixed into one that cannot.
 */
class RetiredClientsState(
    private val scanner: RetiredClientScanner,
    private val adopter: RetiredClientAdopter,
    private val sweeper: RetiredDataSweeper,
) {
    private val log = LoggerFactory.getLogger(RetiredClientsState::class.java)

    val rows = mutableStateListOf<RetiredRow>()

    var loading by mutableStateOf(true)
        private set

    var running by mutableStateOf(false)
        private set

    /** True once a pass has run, so the surface shows what happened rather than the list. */
    var finished by mutableStateOf(false)
        private set

    /** Bytes the last pass actually reclaimed, as the scan measured them. */
    var reclaimedBytes by mutableStateOf(0L)
        private set

    val totalBytes: Long get() = rows.sumOf { it.client.sizeBytes }

    /** Nothing chosen means the reader looked and left, which the surface must allow. */
    val anyChosen: Boolean get() = rows.any { it.choice != RetiredChoice.Keep }

    /**
     * Whether the pass can run. A row set to adopt with no version is the one
     * thing that blocks it, and the surface says so on that row rather than
     * leaving a dead button with no explanation.
     */
    val ready: Boolean get() = rows.none { it.choice == RetiredChoice.Adopt && !it.adoptable }

    suspend fun load() {
        loading = true
        rows.clear()
        runCatching { scanner.scan() }
            .onSuccess { found -> rows.addAll(found.map(::RetiredRow)) }
            .onFailure { if (it is CancellationException) throw it else log.warn("leftover clients: scan failed", it) }
        loading = false
    }

    /**
     * Sets every row to [choice].
     *
     * Including the rows that cannot be adopted yet: the version is typed on a row
     * that is already set to adopt, so skipping them here would put them out of
     * reach of the only control that fixes them. [ready] is what holds the pass
     * back until they are filled in.
     */
    fun chooseAll(choice: RetiredChoice) {
        rows.forEach { row -> row.choice = choice }
    }

    /**
     * Carries out what was chosen: adopt first, then sweep.
     *
     * A row that fails is recorded on the row and the pass goes on. One locked
     * file in one client must not leave the other six untouched with nothing said
     * about why.
     */
    suspend fun run() {
        if (running) return
        running = true
        try {
            val toSweep = mutableListOf<RetiredClient>()
            // Bytes an adopted source now shares with its instance. Removing the
            // source frees none of them, so they come off what the surface
            // reports as reclaimed.
            val shared = mutableMapOf<String, Long>()

            for (row in rows.filter { it.choice == RetiredChoice.Adopt }) {
                row.busy = true
                try {
                    val adopted = adopter.adopt(row.client, row.mcVersion, row.loader.ifBlank { null })
                    row.outcome = RetiredOutcome.Adopted(adopted.instance.displayName, sourceKept = !adopted.complete)
                    // The source is redundant only when every file made it across.
                    if (adopted.complete) {
                        toSweep += row.client
                        shared[row.client.dir.toString()] = adopted.sharedBytes
                    }
                    ActionRing.record(
                        "Leftover client '${row.client.name}' adopted as a local pack" +
                            if (adopted.complete) "" else " (source kept: ${adopted.failed} file(s) did not transfer)",
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.error("leftover clients: could not adopt {}", row.client.name, e)
                    row.outcome = RetiredOutcome.Failed(e.message ?: e::class.simpleName.orEmpty())
                    ActionRing.record("Leftover client '${row.client.name}' could not be adopted")
                } finally {
                    row.busy = false
                }
            }

            val deleting = rows.filter { it.choice == RetiredChoice.Delete }
            toSweep += deleting.map { it.client }

            if (toSweep.isNotEmpty()) {
                val swept = sweeper.sweep(toSweep) { shared[it.dir.toString()] ?: 0L }
                reclaimedBytes = swept.bytes
                val gone = swept.clients.toSet()
                val half = swept.partial.toSet()
                deleting.forEach { row ->
                    row.outcome = when (row.client.name) {
                        in gone -> RetiredOutcome.Deleted
                        in half -> RetiredOutcome.PartlyDeleted
                        else -> RetiredOutcome.Failed(FAILED_TO_REMOVE)
                    }
                }
                ActionRing.record("Leftover clients: removed ${swept.clients.size}, reclaimed ${swept.bytes} bytes")
            }
            finished = true
        } finally {
            running = false
        }
    }

    private companion object {
        /**
         * Not a message for the reader -- the surface turns this into its own
         * words. It is here so the outcome carries WHY a row is still listed.
         */
        const val FAILED_TO_REMOVE = "remove"
    }
}

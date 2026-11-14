package hivens.module.packsconsumer

import androidx.compose.runtime.Composable
import hivens.packs.api.PackKeys
import hivens.packs.api.PackLaunch
import hivens.packs.api.PackView
import hivens.widget.api.KeyContribution
import hivens.widget.api.ModuleKeys
import hivens.widget.api.flowSource
import hivens.widget.api.rememberScopedCommand
import hivens.widget.api.rememberScopedSource
import hivens.widget.api.valueOrNull
import hivens.widget.model.SourceKey
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** One line of the shelf: a pack and whether its game is running. */
data class ShelfLine(val packId: String, val name: String, val running: Boolean)

/**
 * The keys this module answers. A class with a public constructor, because a jar's
 * contributions are made by the service loader.
 */
class ShelfKeys : KeyContribution {
    override val id: String = "packs-consumer.keys"
    override val label: String = "Shelf"

    companion object {
        /**
         * The installed packs as shelf lines, newest played first, made from the packs
         * module's library and launches. Missing where the packs module is not.
         */
        val Shelf: SourceKey<List<ShelfLine>> = SourceKey("packs-consumer.shelf")
    }

    override fun declare(keys: ModuleKeys) {
        keys.source(Shelf, sample = { listOf(ShelfLine("sample", "A pack", running = false)) }) { app ->
            val library = checkNotNull(app.find(PackKeys.Library)) { "the packs module is not brought in" }.state
            val launches = checkNotNull(app.find(PackKeys.Launches)) { "the packs module is not brought in" }.state
            val lines = combine(library, launches, ::shelf).stateIn(app.coroutineScope, SharingStarted.Eagerly, shelf(library.value, launches.value))
            flowSource(lines)
        }
    }

    private fun shelf(packs: List<PackView>, launches: Map<String, PackLaunch>): List<ShelfLine> =
        packs.sortedByDescending { it.lastPlayedEpochSecond }.map { ShelfLine(it.id, it.name, launches[it.id] == PackLaunch.Running) }
}

/**
 * A widget body over the pack keys: the names of the packs, and Play for the first.
 * What it read and the Play it got are handed to [seen], so a test can see them.
 */
@Composable
fun PackShelf(seen: (names: List<String>?, play: (() -> Unit)?) -> Unit) {
    val packs = rememberScopedSource(PackKeys.Library).valueOrNull()
    val launch = rememberScopedCommand(PackKeys.LaunchPack)
    val first = packs?.firstOrNull()
    seen(packs?.map { it.name }, if (launch != null && first != null) ({ launch(first.id) }) else null)
}

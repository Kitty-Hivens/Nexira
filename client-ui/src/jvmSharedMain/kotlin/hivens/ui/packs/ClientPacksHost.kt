package hivens.ui.packs

import hivens.core.data.PackInstance
import hivens.packs.PacksHost
import hivens.packs.api.PackLaunch
import hivens.ui.notifications.CoreProblem
import hivens.ui.notifications.CoreProblems
import hivens.ui.notifications.IndicationCenter
import hivens.ui.notifications.IndicationCenter.LaunchIndication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * What the packs module asks of the launcher around it, over the launcher's own
 * parts: [onLaunched] hands a launch to the launch driver, which follows it with its
 * notices and keeps the per-pack launch state in [indications], and the core problems
 * say a missing pack out loud.
 */
class ClientPacksHost(
    private val onLaunched: (PackInstance) -> Unit,
    indications: IndicationCenter,
    private val problems: CoreProblems,
    scope: CoroutineScope,
) : PacksHost {

    override val launches: StateFlow<Map<String, PackLaunch>> =
        indications.launches
            .map { all -> all.mapValues { (_, it) -> it.toPackLaunch() } }
            .stateIn(scope, SharingStarted.Eagerly, indications.launches.value.mapValues { (_, it) -> it.toPackLaunch() })

    override fun launched(pack: PackInstance) = onLaunched(pack)

    override fun packMissing(packId: String) = problems.report(CoreProblem.PackNotInstalled(packId))

    override fun packFound(packId: String) = problems.withdraw(CoreProblem.PackNotInstalled(packId))
}

/** The launch driver's per-pack state as the packs API carries it. */
internal fun LaunchIndication.toPackLaunch(): PackLaunch = when (this) {
    LaunchIndication.Preparing -> PackLaunch.Preparing
    is LaunchIndication.Downloading -> PackLaunch.Downloading(progress)
    LaunchIndication.Running -> PackLaunch.Running
    LaunchIndication.Stopping -> PackLaunch.Stopping
    LaunchIndication.Failed -> PackLaunch.Failed
}

/**
 * Takes back the notice that a pack was not installed once a pack of that id is,
 * for as long as [scope] runs. The notice is sticky and says something that no
 * longer holds once the pack is there.
 */
fun withdrawNoticesOfInstalledPacks(packs: StateFlow<List<PackInstance>>, problems: CoreProblems, scope: CoroutineScope) {
    scope.launch {
        packs.collect { installed -> installed.forEach { problems.withdraw(CoreProblem.PackNotInstalled(it.id)) } }
    }
}

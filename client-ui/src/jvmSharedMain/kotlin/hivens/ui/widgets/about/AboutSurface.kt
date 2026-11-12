package hivens.ui.widgets.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import hivens.ui.i18n.LocalStrings
import hivens.ui.nx.AdaptiveWidth
import hivens.ui.nx.WidthClass
import hivens.ui.puppet.PuppetClick
import hivens.ui.puppet.PuppetScreen
import hivens.ui.platform.rememberHostActions
import hivens.widget.api.ProvideScoped
import hivens.widget.api.ProvideScopedCommand
import hivens.widget.api.SlotRenderer
import hivens.widget.api.rememberLatestCommand
import hivens.widget.model.SlotId
import hivens.widget.model.SurfaceId
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes

private const val SURFACE = "about"

// about surface. AppLayout routes Screen.About here. Two
// side-by-side slots: `left` (logo + credits) and `right` (update
// panel, system rows, links). Chrome stays surface-level (back +
// title) -- per the carve-out rule, removing them orphans the
// screen.
//
// Surface owns the per-screen state (updateState, showUpdateDialog)
// and the asynchronous check it runs on a timer. It puts the state and
// the way to open the dialog over its page level, where the update
// panel reads them. The full UpdateDialog modal lives at the surface
// level so it survives even if the user removes the update.panel widget
// via the editor.
//
// No verticalScroll on the slot containers; the credits widget
// self-scrolls its long body text. Lazy widgets the user may drop
// via the editor render with bounded constraints.
@Composable
fun AboutSurface(onBack: () -> Unit) {
    val s = LocalStrings.current
    val host = rememberHostActions()
    val scope = rememberCoroutineScope()

    val updateState       = remember { mutableStateOf<UpdateCheckState>(UpdateCheckState.Idle) }
    val showUpdateDialog  = remember { mutableStateOf(false) }
    val openDialog        = rememberLatestCommand<Unit> { showUpdateDialog.value = true }

    val unknownErrorText = s.updateErrorUnknown
    val triggerUpdateCheck: () -> Unit = remember(scope, host, unknownErrorText) {
        {
            updateState.value = UpdateCheckState.Checking
            scope.launch {
                updateState.value = try {
                    val update = host.checkForUpdate()
                    if (update != null) UpdateCheckState.Available(update)
                    else UpdateCheckState.UpToDate
                } catch (e: Exception) {
                    UpdateCheckState.Error(e.message ?: unknownErrorText)
                }
            }
        }
    }

    // Background auto-check every 5 minutes while the About screen is open,
    // stopping once an update is found (the panel then shows it). 12 checks/hour
    // sits comfortably under GitHub's ~60/hour unauthenticated API ceiling.
    LaunchedEffect(Unit) {
        if (!host.updatesSupported) return@LaunchedEffect
        while (true) {
            // Skip when a result is already shown (Available) or a check is
            // still running (Checking) -- a tick that lands on an in-flight or
            // manual check would otherwise launch a second concurrent
            // checkForUpdate that races to overwrite updateState.
            val state = updateState.value
            if (state !is UpdateCheckState.Available && state !is UpdateCheckState.Checking) {
                triggerUpdateCheck()
            }
            delay(5.minutes)
        }
    }

    // Modal update dialog -- mounted at surface level so it persists
    // even if the user removes the update.panel widget. Available
    // state is the only branch that can open it.
    val availableUpdate = (updateState.value as? UpdateCheckState.Available)?.update
    if (showUpdateDialog.value && availableUpdate != null) {
        UpdateDialogHost(
            update    = availableUpdate,
            onDismiss = { showUpdateDialog.value = false },
        )
    }

    PuppetScreen("About")
    PuppetClick("about.back") { onBack() }
    PuppetClick("about.checkUpdates", enabled = updateState.value is UpdateCheckState.Idle) {
        triggerUpdateCheck()
    }
    PuppetClick(
        id      = "about.checkAgain",
        enabled = updateState.value !is UpdateCheckState.Idle &&
                  updateState.value !is UpdateCheckState.Checking,
    ) {
        updateState.value = UpdateCheckState.Idle
    }
    PuppetClick("about.openUpdateDialog", enabled = updateState.value is UpdateCheckState.Available) {
        showUpdateDialog.value = true
    }

    ProvideScoped(AboutUpdateSource, updateState.value) {
    ProvideScopedCommand(OpenUpdateDialog, openDialog) {
        AdaptiveWidth(Modifier.fillMaxSize()) { widthClass, _ ->
            val pad = when (widthClass) {
                WidthClass.Expanded -> 24.dp
                WidthClass.Medium   -> 16.dp
                WidthClass.Compact  -> 12.dp
            }
            Column(Modifier.fillMaxSize().padding(pad)) {
                // Title lives in the top-bar breadcrumb now -- no in-screen duplicate.
                if (widthClass == WidthClass.Expanded) {
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        SlotRenderer(
                            SurfaceId(SURFACE),
                            SlotId("left"),
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            spacing  = 16.dp,
                        )
                        SlotRenderer(
                            SurfaceId(SURFACE),
                            SlotId("right"),
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            spacing  = 16.dp,
                        )
                    }
                } else {
                    // Too narrow for side-by-side: stack the slots, each weight-bounded
                    // so a self-scrolling widget has a height to scroll within. No
                    // verticalScroll on a slot -- it would collide with that inner scroll.
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SlotRenderer(
                            SurfaceId(SURFACE),
                            SlotId("left"),
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            spacing  = 16.dp,
                        )
                        SlotRenderer(
                            SurfaceId(SURFACE),
                            SlotId("right"),
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            spacing  = 16.dp,
                        )
                    }
                }
            }
        }
    }
    }
}

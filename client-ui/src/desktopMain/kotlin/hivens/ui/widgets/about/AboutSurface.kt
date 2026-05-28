package hivens.ui.widgets.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.launcher.update.UpdateService
import hivens.ui.components.UpdateDialog
import hivens.ui.i18n.LocalStrings
import hivens.ui.puppet.PuppetClick
import hivens.ui.puppet.PuppetScreen
import hivens.ui.theme.CelestiaTheme
import hivens.widget.api.SlotRenderer
import hivens.widget.model.SlotId
import hivens.widget.model.SurfaceId
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.lang.management.ManagementFactory

private const val SURFACE = "about"

// about surface. AppLayout routes Screen.About here. Two
// side-by-side slots: `left` (logo + credits) and `right` (update
// panel, system rows, links). Chrome stays surface-level (back +
// title) -- per the carve-out rule, removing them orphans the
// screen.
//
// Surface owns the per-screen state (updateState, showUpdateDialog)
// and the asynchronous triggerUpdateCheck closure; widgets observe
// MutableState holders in the context and re-render. The full
// UpdateDialog modal lives at the surface level so it survives even
// if the user removes the update.panel widget via the editor.
//
// Pre-computed values (systemRam reflection lookup, displayRes AWT
// toolkit call) happen once at surface mount and pass into the
// context -- per-widget recomputation would re-read these on every
// recomposition.
//
// No verticalScroll on the slot containers; the credits widget
// self-scrolls its long body text. Lazy widgets the user may drop
// via the editor render with bounded constraints.
@Composable
fun AboutSurface(onBack: () -> Unit) {
    val s = LocalStrings.current
    val updateService: UpdateService = koinInject()
    val scope = rememberCoroutineScope()

    val updateState      = remember { mutableStateOf<UpdateCheckState>(UpdateCheckState.Idle) }
    val showUpdateDialog = remember { mutableStateOf(false) }

    val systemRam = remember {
        runCatching {
            val osBean = ManagementFactory.getOperatingSystemMXBean()
            val method = osBean.javaClass.getMethod("getTotalPhysicalMemorySize")
            method.isAccessible = true
            ((method.invoke(osBean) as Long) / (1024 * 1024)).toInt()
        }.getOrDefault(0)
    }

    val displayRes = remember {
        runCatching {
            val size = java.awt.Toolkit.getDefaultToolkit().screenSize
            "${size.width}x${size.height}"
        }.getOrDefault("Unknown")
    }

    val unknownErrorText = s.updateErrorUnknown
    val triggerUpdateCheck: () -> Unit = remember(scope, updateService, unknownErrorText) {
        {
            updateState.value = UpdateCheckState.Checking
            scope.launch {
                updateState.value = try {
                    val update = updateService.checkForUpdate(force = true)
                    if (update != null) UpdateCheckState.Available(update)
                    else UpdateCheckState.UpToDate
                } catch (e: Exception) {
                    UpdateCheckState.Error(e.message ?: unknownErrorText)
                }
            }
        }
    }

    val ctx = remember(updateState, showUpdateDialog, triggerUpdateCheck, systemRam, displayRes) {
        AboutContext(
            updateState        = updateState,
            showUpdateDialog   = showUpdateDialog,
            triggerUpdateCheck = triggerUpdateCheck,
            systemRam          = systemRam,
            displayRes         = displayRes,
        )
    }

    // Modal update dialog -- mounted at surface level so it persists
    // even if the user removes the update.panel widget. Available
    // state is the only branch that can open it.
    val availableUpdate = (updateState.value as? UpdateCheckState.Available)?.update
    if (showUpdateDialog.value && availableUpdate != null) {
        UpdateDialog(
            update        = availableUpdate,
            updateService = updateService,
            onDismiss     = { showUpdateDialog.value = false },
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

    CompositionLocalProvider(LocalAboutContext provides ctx) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector        = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = s.navBack,
                        tint               = CelestiaTheme.colors.textPrimary,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text       = s.aboutTitle,
                    style      = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color      = CelestiaTheme.colors.textPrimary,
                )
            }
            Spacer(Modifier.height(20.dp))

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
        }
    }
}

package hivens.ui.editor.props

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.i18n.api.Strings
import hivens.ui.i18n.nexiraStrings
import hivens.ui.icons.NxIcon
import hivens.ui.nx.NxButton
import hivens.ui.nx.NxButtonStyle
import hivens.ui.nx.NxSwitch
import hivens.ui.puppet.PuppetClick
import hivens.ui.puppet.PuppetToggle
import hivens.ui.surface.NxSurface
import hivens.ui.surface.SurfaceKind
import hivens.ui.theme.NxColor
import hivens.ui.theme.NxInk
import hivens.ui.theme.Status
import hivens.ui.widgets.modules.WidgetModules
import hivens.widget.model.ModuleWait
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import hivens.ui.platform.SystemActions

/**
 * The widget modules, and the switches that change them while the launcher runs.
 *
 * Every line is one module or one jar: loaded with how many widgets it brought and
 * which modules stop with it, waiting for what it requires, switched off (and why,
 * when it crashed), refused (and why), or gone from the folder with its widgets
 * still kept. Nothing is refused to the person: a module others require can be
 * switched off, and they wait until it is back. Reading the folder again picks up a jar
 * added, replaced or taken away, without a restart.
 */
@Composable
internal fun WidgetModulesPanel(
    visible: Boolean,
    modules: WidgetModules,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = nexiraStrings()
    val state by modules.state.collectAsState()
    val scope = rememberCoroutineScope()
    // Off the UI thread: a scan opens and copies jars.
    fun inBackground(work: () -> Unit) {
        scope.launch { withContext(Dispatchers.IO) { work() } }
    }
    EditorSidePanel(visible = visible, title = s.text("modulesTitle"), icon = NxIcon.Folder, onDismiss = onDismiss, modifier = modifier) {
        if (state.entries.isEmpty()) {
            Text(s.text("modulesEmpty"), style = MaterialTheme.typography.bodySmall, color = NxInk.quiet)
        }
        state.entries.forEach { entry ->
            NxSurface(SurfaceKind.Field, Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    when (entry) {
                        is WidgetModules.Entry.Loaded -> {
                            ModuleHeader(entry.name, on = true) { inBackground { modules.setEnabled(entry.id, false) } }
                            PuppetToggle("modules.${entry.id}", true) { on -> inBackground { modules.setEnabled(entry.id, on) } }
                            Detail(s.text("moduleWidgets", entry.kinds))
                            if (entry.contributions > 0) Detail(s.text("moduleContributions", entry.contributions))
                            if (entry.stops.isNotEmpty()) Detail(s.text("moduleStops", entry.stops.joinToString(", ")))
                            Detail(entry.file.fileName.toString())
                        }
                        is WidgetModules.Entry.Waiting -> {
                            ModuleHeader(entry.name, on = true) { inBackground { modules.setEnabled(entry.id, false) } }
                            PuppetToggle("modules.${entry.id}", true) { on -> inBackground { modules.setEnabled(entry.id, on) } }
                            entry.reasons.forEach { reason ->
                                Text(
                                    words(s, reason, state::nameOf),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = NxColor.status(Status.Warning),
                                )
                            }
                            entry.reasons.filterIsInstance<ModuleWait.Off>().map { it.module }.distinct().forEach { id ->
                                NxButton(
                                    label   = s.text("moduleSwitchOn", state.nameOf(id)),
                                    onClick = { inBackground { modules.setEnabled(id, true) } },
                                    style   = NxButtonStyle.Secondary,
                                    compact = true,
                                )
                                PuppetClick("modules.switchOn.$id") { inBackground { modules.setEnabled(id, true) } }
                            }
                        }
                        is WidgetModules.Entry.Off -> {
                            ModuleHeader(entry.name, on = false) { inBackground { modules.setEnabled(entry.id, true) } }
                            PuppetToggle("modules.${entry.id}", false) { on -> inBackground { modules.setEnabled(entry.id, on) } }
                            if (entry.crash != null) {
                                Text(
                                    s.text("moduleOffAfterCrash", entry.crash),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = NxColor.status(Status.Warning),
                                )
                            } else {
                                Detail(s.text("moduleOff"))
                            }
                        }
                        is WidgetModules.Entry.Refused -> {
                            Text(entry.file.fileName.toString(), color = NxInk.main, fontWeight = FontWeight.SemiBold)
                            Text(
                                s.text("moduleRefused", entry.reason),
                                style = MaterialTheme.typography.bodySmall,
                                color = NxColor.status(Status.Error),
                            )
                        }
                        is WidgetModules.Entry.Gone -> {
                            Text(entry.id, color = NxInk.main, fontWeight = FontWeight.SemiBold)
                            Detail(s.text("moduleGone"))
                            NxButton(
                                label   = s.text("moduleForget"),
                                onClick = { inBackground { modules.forget(entry.id) } },
                                style   = NxButtonStyle.Secondary,
                                compact = true,
                            )
                            PuppetClick("modules.forget.${entry.id}") { inBackground { modules.forget(entry.id) } }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        NxButton(
            label    = s.text("modulesReload"),
            onClick  = { inBackground { modules.reload() } },
            icon     = NxIcon.Refresh,
            modifier = Modifier.fillMaxWidth(),
        )
        PuppetClick("modules.reload") { inBackground { modules.reload() } }
        NxButton(
            label    = s.text("modulesOpenFolder"),
            onClick  = {
                inBackground {
                    SystemActions.openFolder(modules.folder.toString())
                }
            },
            style    = NxButtonStyle.Secondary,
            icon     = NxIcon.FolderOpen,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Why a module waits, in the person's words, each module by the name the list gives it. */
internal fun words(s: Strings, reason: ModuleWait, nameOf: (id: String) -> String): String = when (reason) {
    is ModuleWait.Missing -> s.text("moduleWaitsMissing", reason.name, reason.needed.toString())
    is ModuleWait.OtherVersion -> s.text("moduleWaitsVersion", reason.name, reason.needed.toString(), reason.here.toString())
    is ModuleWait.Off -> s.text("moduleWaitsOff", nameOf(reason.module))
    is ModuleWait.Stopped -> s.text("moduleWaitsStopped", nameOf(reason.module))
    is ModuleWait.Cycle -> s.text("moduleWaitsCycle", reason.modules.joinToString(", ", transform = nameOf))
}

@Composable
private fun ModuleHeader(name: String, on: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(name, color = NxInk.main, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        NxSwitch(checked = on, onCheckedChange = { onToggle() })
    }
}

@Composable
private fun Detail(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = NxInk.quiet)
}

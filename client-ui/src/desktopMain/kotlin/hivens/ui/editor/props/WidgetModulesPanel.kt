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
import hivens.ui.i18n.LocalStrings
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.nio.file.Files

/**
 * The widget modules, and the switches that change them while the launcher runs.
 *
 * Every line is one module or one jar: loaded with how many widgets it brought,
 * switched off (and why, when it crashed), refused (and why), or gone from the
 * folder with its widgets still kept. Reading the folder again picks up a jar
 * added, replaced or taken away, without a restart.
 */
@Composable
internal fun WidgetModulesPanel(
    visible: Boolean,
    modules: WidgetModules,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    val state by modules.state.collectAsState()
    val scope = rememberCoroutineScope()
    // Off the UI thread: a scan opens and copies jars.
    fun inBackground(work: () -> Unit) {
        scope.launch { withContext(Dispatchers.IO) { work() } }
    }
    EditorSidePanel(visible = visible, title = s.modulesTitle, icon = NxIcon.Folder, onDismiss = onDismiss, modifier = modifier) {
        if (state.entries.isEmpty()) {
            Text(s.modulesEmpty, style = MaterialTheme.typography.bodySmall, color = NxInk.quiet)
        }
        state.entries.forEach { entry ->
            NxSurface(SurfaceKind.Field, Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    when (entry) {
                        is WidgetModules.Entry.Loaded -> {
                            ModuleHeader(entry.name, on = true) { inBackground { modules.setEnabled(entry.id, false) } }
                            PuppetToggle("modules.${entry.id}", true) { on -> inBackground { modules.setEnabled(entry.id, on) } }
                            Detail(s.moduleWidgets(entry.kinds))
                            Detail(entry.file.fileName.toString())
                        }
                        is WidgetModules.Entry.Off -> {
                            ModuleHeader(entry.name, on = false) { inBackground { modules.setEnabled(entry.id, true) } }
                            PuppetToggle("modules.${entry.id}", false) { on -> inBackground { modules.setEnabled(entry.id, on) } }
                            if (entry.crash != null) {
                                Text(
                                    s.moduleOffAfterCrash(entry.crash),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = NxColor.status(Status.Warning, text = true),
                                )
                            } else {
                                Detail(s.moduleOff)
                            }
                        }
                        is WidgetModules.Entry.Refused -> {
                            Text(entry.file.fileName.toString(), color = NxInk.main, fontWeight = FontWeight.SemiBold)
                            Text(
                                s.moduleRefused(entry.reason),
                                style = MaterialTheme.typography.bodySmall,
                                color = NxColor.status(Status.Error, text = true),
                            )
                        }
                        is WidgetModules.Entry.Gone -> {
                            Text(entry.id, color = NxInk.main, fontWeight = FontWeight.SemiBold)
                            Detail(s.moduleGone)
                            NxButton(
                                label   = s.moduleForget,
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
            label    = s.modulesReload,
            onClick  = { inBackground { modules.reload() } },
            icon     = NxIcon.Refresh,
            modifier = Modifier.fillMaxWidth(),
        )
        PuppetClick("modules.reload") { inBackground { modules.reload() } }
        NxButton(
            label    = s.modulesOpenFolder,
            onClick  = {
                inBackground {
                    runCatching {
                        val dir = modules.folder
                        Files.createDirectories(dir)
                        Desktop.getDesktop().open(dir.toFile())
                    }
                }
            },
            style    = NxButtonStyle.Secondary,
            icon     = NxIcon.FolderOpen,
            modifier = Modifier.fillMaxWidth(),
        )
    }
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

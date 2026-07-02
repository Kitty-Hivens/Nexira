package hivens.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import hivens.config.Branding
import hivens.ui.i18n.AppLocale
import hivens.ui.i18n.stringsFor
import java.util.Locale

/**
 * Standalone last-resort window shown when the shell crash loop latches safe
 * mode (see hivens.ui.diag.UiRecoverySignal + Main.runShellWithRecovery).
 *
 * Deliberately self-contained: it does NOT touch Koin, NxTheme, the
 * widget kernel, or any surface CompositionLocal. A crash anywhere in that
 * scaffolding is exactly what put us here, so re-running it would just crash
 * again -- this window depends only on raw Material3 + a pure stringsFor()
 * lookup + a quit button. Strings follow the OS locale (settings live behind
 * the Koin we are avoiding); fromTag falls back to the default on a miss.
 */
@Composable
fun SafeModeWindow(onQuit: () -> Unit) {
    val s = remember { stringsFor(AppLocale.fromTag(Locale.getDefault().language)) }
    val windowState = rememberWindowState(position = WindowPosition(Alignment.Center))
    Window(onCloseRequest = onQuit, state = windowState, title = Branding.TITLE) {
        MaterialTheme(colorScheme = darkColorScheme()) {
            Surface(modifier = Modifier.fillMaxSize()) {
                Box(contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.padding(32.dp),
                    ) {
                        Text(
                            text  = s.recoverySafeModeTitle,
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text      = s.recoverySafeModeBody,
                            style     = MaterialTheme.typography.bodyMedium,
                            color     = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier  = Modifier.widthIn(max = 440.dp),
                        )
                        Button(onClick = onQuit) { Text(s.recoverySafeModeQuit) }
                    }
                }
            }
        }
    }
}

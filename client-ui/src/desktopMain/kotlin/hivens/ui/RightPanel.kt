package hivens.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import hivens.core.data.SessionData
import hivens.ui.theme.CelestiaTheme

/**
 * Right-side panel. Orchestrator only -- the auth panel (Loading /
 * Unauthenticated / Authenticated) and news feed live in their own files
 * (`LoginPanel.kt`, `AccountPanel.kt`, `CompactNewsFeed.kt`) after the
 * B5 god-file split. Keeps this file under 50 LOC so reading the right-
 * panel layout doesn't require scrolling through 800 lines of widget
 * internals.
 */
@Composable
fun RightPanel(
    appState: AppState,
    onLogin: (SessionData) -> Unit,
    onLogout: () -> Unit,
    sslBypass: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.background(CelestiaTheme.colors.background)) {

        // ── Auth section (top) ────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(CelestiaTheme.colors.surface.copy(alpha = 0.22f))
        ) {
            when (appState) {
                AppState.Loading          -> AuthLoadingSlot()
                AppState.Unauthenticated  -> LoginPanel(onLogin = onLogin)
                is AppState.Authenticated -> AccountPanel(
                    session  = appState.session,
                    onLogout = onLogout,
                )
            }
        }

        HorizontalDivider(color = CelestiaTheme.colors.surface.copy(alpha = 0.7f))

        // ── News feed (bottom) ────────────────────────────────────────────────
        CompactNewsFeed(
            sslBypass = sslBypass,
            modifier  = Modifier.weight(1f).fillMaxWidth(),
        )
    }
}

package hivens.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import hivens.core.data.SessionData
import hivens.ui.customization.glassSurfaceAlpha
import hivens.ui.theme.CelestiaTheme

/**
 * Right-side panel. Orchestrator only -- the auth panel (Loading /
 * Unauthenticated / Authenticated) and news feed live in their own files
 * (`LoginPanel.kt`, `AccountPanel.kt`, `CompactNewsFeed.kt`).
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
                .background(glassSurfaceAlpha(0.22f))
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

        HorizontalDivider(color = glassSurfaceAlpha(0.7f))

        // ── News feed (bottom) ────────────────────────────────────────────────
        CompactNewsFeed(
            sslBypass = sslBypass,
            modifier  = Modifier.weight(1f).fillMaxWidth(),
        )
    }
}

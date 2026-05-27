package hivens.ui.widgets.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import hivens.ui.AccountPanel
import hivens.ui.AppState
import hivens.ui.AuthLoadingSlot
import hivens.ui.LoginPanel
import hivens.ui.customization.glassSurfaceAlpha
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance

@Widget(id = "appshell.rightrail.authpanel", displayName = "Auth panel")
@Composable
fun RightRailAuthPanel(instance: WidgetInstance) {
    val ctx = LocalRightRailContext.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(glassSurfaceAlpha(0.22f)),
    ) {
        when (val state = ctx.appState) {
            AppState.Loading          -> AuthLoadingSlot()
            AppState.Unauthenticated  -> LoginPanel(onLogin = ctx.onLogin)
            is AppState.Authenticated -> AccountPanel(
                session  = state.session,
                onLogout = ctx.onLogout,
            )
        }
    }
}

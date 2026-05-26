package hivens.ui.screens.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.PackInstance
import hivens.launcher.launch.LauncherController
import hivens.ui.AppState
import hivens.ui.Screen
import hivens.ui.puppet.PuppetScreen
import hivens.ui.theme.CelestiaTheme
import org.koin.compose.koinInject

/**
 * Library = user's collection of installed [PackInstance]s.
 * Renders [PackCard] rows for each instance the [IPackRepository]
 * is aware of. Card click navigates to [hivens.ui.Screen.PackDetail];
 * per-card Play launches via [LauncherController.launchPackInstance]
 * without the detail-screen hop; Settings / More still route to the
 * detail surface (the per-pack settings window lands in a follow-up
 * PR, [[project_pack_centric_direction]]).
 *
 * Empty state when the repository has nothing to show -- by design
 * this is the cold-start view for a fresh install (no packs yet);
 * the Browse screen is the entry point for installing.
 */
@Composable
fun LibraryScreen(
    appState: AppState,
    onScreenChange: (Screen) -> Unit,
) {
    PuppetScreen("Library")

    val repo: IPackRepository = koinInject()
    val controller: LauncherController = koinInject()
    val launchDriver: hivens.ui.notifications.drivers.PackLaunchDriver = koinInject()
    val gameConsole: hivens.ui.utils.GameConsoleService = koinInject()
    val instances by remember { repo.observe() }.collectAsState(initial = emptyList())
    val authedSession = (appState as? AppState.Authenticated)?.session

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp)) {
        Text(
            text       = "БИБЛИОТЕКА",
            style      = MaterialTheme.typography.headlineSmall,
            color      = CelestiaTheme.colors.textPrimary,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text  = "Установленные сборки",
            style = MaterialTheme.typography.bodyMedium,
            color = CelestiaTheme.colors.textSecondary,
        )
        Spacer(Modifier.height(16.dp))

        if (instances.isEmpty()) {
            LibraryEmpty(onBrowse = { onScreenChange(Screen.Browse) })
        } else {
            LibraryList(
                instances = instances,
                onOpenDetail = { onScreenChange(Screen.PackDetail(it.id)) },
                onPlay = { instance ->
                    // Unauthenticated state: defer to the detail screen
                    // which renders an explicit "Sign in to play" prompt
                    // instead of swallowing the click silently.
                    val session = authedSession
                    if (session == null) {
                        onScreenChange(Screen.PackDetail(instance.id))
                    } else {
                        // Same observer-then-launch + console-show
                        // sequence as PackDetail's PlayBar so the
                        // user gets identical feedback regardless of
                        // which Play affordance they used.
                        launchDriver.observe(instance)
                        gameConsole.show()
                        controller.launchPackInstance(session, instance)
                    }
                },
                onSettings = { onScreenChange(Screen.PackDetail(it.id)) },
                onMore = { onScreenChange(Screen.PackDetail(it.id)) },
            )
        }
    }
}

@Composable
private fun LibraryList(
    instances: List<PackInstance>,
    onOpenDetail: (PackInstance) -> Unit,
    onPlay: (PackInstance) -> Unit,
    onSettings: (PackInstance) -> Unit,
    onMore: (PackInstance) -> Unit,
) {
    LazyColumn(
        modifier              = Modifier.fillMaxSize(),
        contentPadding        = PaddingValues(bottom = 16.dp),
        verticalArrangement   = Arrangement.spacedBy(10.dp),
    ) {
        items(items = instances, key = { it.id }) { instance ->
            PackCard(
                instance      = instance,
                onOpenDetail  = { onOpenDetail(instance) },
                onPlay        = { onPlay(instance) },
                onSettings    = { onSettings(instance) },
                onMore        = { onMore(instance) },
            )
        }
    }
}

@Composable
private fun LibraryEmpty(onBrowse: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text       = "Пока пусто",
                style      = MaterialTheme.typography.titleLarge,
                color      = CelestiaTheme.colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text      = "Установите сборку через Browse — она появится здесь.",
                style     = MaterialTheme.typography.bodyMedium,
                color     = CelestiaTheme.colors.textSecondary,
                textAlign = TextAlign.Center,
                modifier  = Modifier.widthIn(max = 360.dp),
            )
            Button(
                onClick = onBrowse,
                shape   = RoundedCornerShape(10.dp),
                colors  = ButtonDefaults.buttonColors(
                    containerColor = CelestiaTheme.colors.primary,
                    contentColor   = Color.White,
                ),
            ) { Text("Открыть Browse", fontWeight = FontWeight.SemiBold) }
        }
    }
}


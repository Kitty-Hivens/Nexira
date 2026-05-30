package hivens.ui.widgets.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import hivens.ui.notifications.drivers.PackLaunchDriver
import hivens.ui.screens.library.PackCard
import hivens.ui.theme.CelestiaTheme
import hivens.widget.api.rememberProps
import hivens.widget.model.PropLabel
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

@Serializable
data class LibraryBodyProps(
    @PropLabel("Заголовок пустого состояния") val emptyTitle: String = "Пока пусто",
    @PropLabel("Текст пустого состояния")
    val emptyText: String = "Установите сборку через Browse — она появится здесь.",
)

// Single widget covers both populated list and empty state. Slot-level
// branching would force the layout graph to know about appState, which
// belongs to navigation, not layout. Self-gating keeps the slot stable
// across the empty -> populated transition.
@Widget(id = "library.body", displayName = "Library Body", propsClass = LibraryBodyProps::class)
@Composable
fun LibraryBody(instance: WidgetInstance) {
    val p = instance.rememberProps<LibraryBodyProps>()
    val ctx = LocalLibraryContext.current
    val repo: IPackRepository = koinInject()
    val controller: LauncherController = koinInject()
    val launchDriver: PackLaunchDriver = koinInject()
    val instances by remember { repo.observe() }.collectAsState(initial = emptyList())
    val authedSession = (ctx.appState as? AppState.Authenticated)?.session

    if (instances.isEmpty()) {
        LibraryEmpty(
            title    = p.emptyTitle,
            body     = p.emptyText,
            onBrowse = { ctx.onScreenChange(Screen.Browse) },
        )
    } else {
        LibraryList(
            instances    = instances,
            onOpenDetail = { ctx.onScreenChange(Screen.PackDetail(it.id)) },
            onPlay       = { pack ->
                // Unauthenticated state: defer to the detail screen
                // which renders an explicit "Sign in to play" prompt
                // instead of swallowing the click silently.
                val session = authedSession
                if (session == null) {
                    ctx.onScreenChange(Screen.PackDetail(pack.id))
                } else {
                    launchDriver.observe(pack)
                    controller.launchPackInstance(session, pack)
                }
            },
            onSettings = { ctx.onScreenChange(Screen.PackDetail(it.id)) },
            onMore     = { ctx.onScreenChange(Screen.PackDetail(it.id)) },
        )
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
        modifier            = Modifier.fillMaxSize(),
        contentPadding      = PaddingValues(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(items = instances, key = { it.id }) { instance ->
            PackCard(
                instance     = instance,
                onOpenDetail = { onOpenDetail(instance) },
                onPlay       = { onPlay(instance) },
                onSettings   = { onSettings(instance) },
                onMore       = { onMore(instance) },
            )
        }
    }
}

@Composable
private fun LibraryEmpty(title: String, body: String, onBrowse: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text       = title,
                style      = MaterialTheme.typography.titleLarge,
                color      = CelestiaTheme.colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text      = body,
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

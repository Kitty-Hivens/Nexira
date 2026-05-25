package hivens.ui.screens.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.ui.customization.glassSurfaceAlpha
import hivens.ui.puppet.PuppetScreen
import hivens.ui.screens.library.PackLoaderChip
import hivens.ui.screens.library.PackMetaChip
import hivens.ui.theme.CelestiaTheme
import kotlinx.coroutines.flow.firstOrNull
import org.koin.compose.koinInject

/**
 * Full-screen Modrinth-style detail page for one [PackInstance].
 * Banner-as-hero up top, identity + meta chips below, large Play
 * button, then placeholder sections for Mods / Servers / Runtime
 * settings that fill in once the manifest reader and per-instance
 * settings split land.
 *
 * Resolves the instance via [IPackRepository] from the
 * [Screen.PackDetail.instanceId] in the navigation entry, so the
 * Screen data class itself stays small (just a UUID string).
 * Renders a not-found placeholder for the brief race window where
 * the instance was deleted while the user was on its detail page.
 */
@Composable
fun PackDetailScreen(
    instanceId: String,
    onBack: () -> Unit,
) {
    PuppetScreen("PackDetail.$instanceId")

    val repo: IPackRepository = koinInject()
    var instance by remember { mutableStateOf<PackInstance?>(null) }
    var resolved by remember { mutableStateOf(false) }
    LaunchedEffect(instanceId) {
        instance = repo.observe().firstOrNull()?.firstOrNull { it.id == instanceId }
            ?: repo.get(instanceId)
        resolved = true
    }

    if (!resolved) {
        // Cold fetch -- usually <1 frame. Plain empty box is fine.
        Box(Modifier.fillMaxSize())
        return
    }
    val pack = instance
    if (pack == null) {
        NotFound(onBack = onBack)
        return
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Hero(pack = pack, onBack = onBack)

        Column(
            modifier            = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            MetaRow(pack)
            PlayBar(pack = pack, onPlay = { /* TODO pack-centric launch flow */ })
            Section(title = "Описание") {
                Text(
                    text  = pack.notes.ifBlank { "(описание появится когда manifest reader подтянет данные из источника)" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = CelestiaTheme.colors.textPrimary,
                )
            }
            Section(title = "Моды") {
                ComingSoon("Список модов с category / license / source-link рендерится " +
                    "после того как manifest reader появится в следующем PR.")
            }
            Section(title = "Серверы") {
                ComingSoon("Привязанные серверы (autoConnect + быстрый join) " +
                    "появятся когда mirror server-list flow дойдёт до UI.")
            }
            Section(title = "Запуск") {
                ComingSoon("Per-instance runtime overrides (RAM, JVM args, Java path, " +
                    "window size) рендерятся в отдельной вкладке после миграции " +
                    "ServerSettingsScreen на pack-centric.")
            }
        }
    }
}

@Composable
private fun Hero(pack: PackInstance, onBack: () -> Unit) {
    val bg = originGradient(pack.packRef.origin)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .background(bg),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))

        IconButton(
            onClick  = onBack,
            modifier = Modifier.padding(12.dp),
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад", tint = Color.White)
        }

        Column(
            modifier              = Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 20.dp),
            verticalArrangement   = Arrangement.Bottom,
        ) {
            Text(
                text       = pack.displayName,
                style      = MaterialTheme.typography.headlineLarge,
                color      = Color.White,
                fontWeight = FontWeight.Bold,
            )
            pack.forkedFrom?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    text  = "Форк: ${it.origin.name} / ${it.id}" + (it.version?.let { v -> " @ $v" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.85f),
                )
            }
        }
    }
}

@Composable
private fun MetaRow(pack: PackInstance) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment     = Alignment.CenterVertically,
    ) {
        PackMetaChip(pack.packRef.origin.name)
        PackMetaChip(pack.packRef.version ?: "—")
        // pack.packRef doesn't carry MC / loader / requiredJava on its
        // own (those live on Pack, not PackInstance). Once the
        // catalogue service can lookup Pack by ref we surface them
        // here; for now the instance-side fields are what we have.
        PackMetaChip(pack.instanceDirName, emphasis = false)
    }
}

@Composable
private fun PlayBar(pack: PackInstance, onPlay: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(glassSurfaceAlpha(0.7f))
            .padding(16.dp),
    ) {
        Row(
            modifier              = Modifier.fillMaxWidth(),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(
                    text       = "Готов к запуску",
                    style      = MaterialTheme.typography.titleMedium,
                    color      = CelestiaTheme.colors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text  = "Папка экземпляра: instances/${pack.instanceDirName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = CelestiaTheme.colors.textSecondary,
                )
            }
            Button(
                onClick        = onPlay,
                shape          = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                colors         = ButtonDefaults.buttonColors(
                    containerColor = CelestiaTheme.colors.primary,
                    contentColor   = Color.White,
                ),
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(22.dp))
                Spacer(Modifier.size(8.dp))
                Text("ИГРАТЬ", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text       = title,
            style      = MaterialTheme.typography.titleSmall,
            color      = CelestiaTheme.colors.primary,
            fontWeight = FontWeight.Bold,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(glassSurfaceAlpha(0.6f))
                .padding(16.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun ComingSoon(text: String) {
    Text(
        text  = text,
        style = MaterialTheme.typography.bodySmall,
        color = CelestiaTheme.colors.textSecondary,
    )
}

@Composable
private fun NotFound(onBack: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Экземпляр не найден", style = MaterialTheme.typography.titleLarge, color = CelestiaTheme.colors.textPrimary)
            Text("Возможно, удалён в другой вкладке.", style = MaterialTheme.typography.bodyMedium, color = CelestiaTheme.colors.textSecondary)
            Button(onClick = onBack) { Text("Назад в библиотеку") }
        }
    }
}

private fun originGradient(origin: PackOrigin): Brush {
    val pair = when (origin) {
        PackOrigin.Smartycraft -> Color(0xFF4C1D95) to Color(0xFF6D28D9)
        PackOrigin.Mirror      -> Color(0xFF1E3A8A) to Color(0xFF1D4ED8)
        PackOrigin.Modrinth    -> Color(0xFF14532D) to Color(0xFF15803D)
        PackOrigin.Local       -> Color(0xFF374151) to Color(0xFF4B5563)
    }
    return Brush.linearGradient(listOf(pair.first, pair.second))
}

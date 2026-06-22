package hivens.ui.widgets.serverdetails

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.core.api.model.ServerProfile
import hivens.launcher.platform.PlatformPaths
import hivens.ui.nx.GlassCard
import hivens.ui.customization.glassSurfaceAlpha
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.puppet.PuppetClick
import hivens.ui.puppet.PuppetScreen
import hivens.ui.theme.NxTheme
import hivens.widget.api.SlotRenderer
import hivens.widget.model.SlotId
import hivens.widget.model.SurfaceId
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

private const val SURFACE = "server.details"

// server.details surface. AppLayout routes Screen.ServerDetails
// here. Two slots: `text` (weight 1.5, holds title + tagbar +
// description widgets) and `image` (weight 1, holds banner widget).
// Chrome stays on the surface: back button + screen title; both are
// per-screen invariants the user cannot meaningfully remove.
//
// description.txt + banner.png are loaded asynchronously from the
// pack assets dir; widgets observe the MutableState holders in the
// context and re-render when load completes. While loading, the
// slot row is suppressed in favor of a CircularProgressIndicator --
// rendering empty widgets during the brief load window would flash
// "Missing description" warnings before the file lands.
//
// No verticalScroll on the slot containers -- a scroll wrapper
// hands children maxHeight = Infinity and Lazy-list widgets the
// user may drop via the editor abort their measure pass.
@Composable
fun ServerDetailsSurface(
    server: ServerProfile,
    onBack: () -> Unit,
) {
    val s = LocalStrings.current
    val paths: PlatformPaths = koinInject()

    val assetsPath = remember(server) { paths.clientDir(server.assetDir).toFile() }
    val description = remember(server) { mutableStateOf<String?>(null) }
    val bannerImage = remember(server) { mutableStateOf<ImageBitmap?>(null) }
    var isLoading by remember(server) { mutableStateOf(true) }

    LaunchedEffect(server) {
        withContext(Dispatchers.IO) {
            val descFile = File(assetsPath, "description.txt")
            if (descFile.exists()) description.value = descFile.readText()

            val imgFile = File(assetsPath, "banner.png")
            if (imgFile.exists()) {
                runCatching {
                    bannerImage.value = ImageIO.read(imgFile)?.toComposeImageBitmap()
                }
            }
            isLoading = false
        }
    }

    val ctx = remember(server, assetsPath, description, bannerImage) {
        ServerDetailsContext(
            server      = server,
            assetsPath  = assetsPath,
            description = description,
            bannerImage = bannerImage,
        )
    }

    PuppetScreen("ServerDetail")
    PuppetClick("serverDetail.back") { onBack() }

    CompositionLocalProvider(LocalServerDetailsContext provides ctx) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            // Chrome header: back + title.
            Row(
                modifier          = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Symbol(icon = NxIcon.ArrowBack,
                        contentDescription = s.navBack,
                        tint               = NxTheme.colors.textPrimary,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text       = s.serverDetailTitle,
                    style      = MaterialTheme.typography.titleLarge,
                    color      = NxTheme.colors.textSecondary,
                    fontWeight = FontWeight.Bold,
                )
            }

            GlassCard(
                modifier        = Modifier.weight(1f).fillMaxWidth(),
                backgroundColor = glassSurfaceAlpha(0.7f),
            ) {
                if (isLoading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = NxTheme.colors.primary)
                    }
                } else {
                    Row(Modifier.fillMaxSize()) {
                        SlotRenderer(SurfaceId(SURFACE), SlotId("text"), Modifier.weight(1.5f).padding(32.dp))
                        SlotRenderer(
                            SurfaceId(SURFACE),
                            SlotId("image"),
                            Modifier.weight(1f).fillMaxHeight().padding(16.dp),
                        )
                    }
                }
            }
        }
    }
}

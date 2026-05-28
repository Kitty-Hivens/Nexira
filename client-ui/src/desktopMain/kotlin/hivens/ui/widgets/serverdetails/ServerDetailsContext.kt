package hivens.ui.widgets.serverdetails

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import hivens.core.api.model.ServerProfile
import java.io.File

// Surface-scoped state the server-details widgets share. server +
// assetsPath are constant for the surface's lifetime. description
// and bannerImage are MutableState because the surface composable
// fills them asynchronously in a LaunchedEffect, and the
// description / banner widgets observe the change to re-render.
// Plain class, not data class -- MutableState fields are
// reference-equality, so generated equals / hashCode would lie.
class ServerDetailsContext(
    val server: ServerProfile,
    val assetsPath: File,
    val description: MutableState<String?>,
    val bannerImage: MutableState<ImageBitmap?>,
)

val LocalServerDetailsContext: ProvidableCompositionLocal<ServerDetailsContext> =
    staticCompositionLocalOf {
        error("LocalServerDetailsContext not provided -- render inside ServerDetailsSurface")
    }

internal val STUB_SERVER_DETAILS: ServerDetailsContext = ServerDetailsContext(
    server      = ServerProfile(),
    assetsPath  = File(""),
    description = mutableStateOf(null),
    bannerImage = mutableStateOf(null),
)

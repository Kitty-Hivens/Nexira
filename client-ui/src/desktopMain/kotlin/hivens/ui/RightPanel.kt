package hivens.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ImageRequest
import coil3.request.crossfade
import hivens.config.AppConfig
import hivens.core.api.interfaces.IAuthService
import hivens.core.api.interfaces.IServerListService
import hivens.core.data.NewsItem
import hivens.core.data.SessionData
import hivens.launcher.CredentialsManager
import hivens.launcher.ProfileManager
import hivens.ui.i18n.LocalStrings
import hivens.ui.theme.CelestiaTheme
import hivens.ui.utils.SkinManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.koin.compose.koinInject

// ─── Right Panel ─────────────────────────────────────────────────────────────

@Composable
fun RightPanel(
    appState: AppState,
    onLogin: (SessionData) -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.background(CelestiaTheme.colors.background)) {

        // ── Auth section (top) ────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(CelestiaTheme.colors.surface.copy(alpha = 0.22f))
        ) {
            when (appState) {
                AppState.Loading         -> AuthLoadingSlot()
                AppState.Unauthenticated -> LoginPanel(onLogin = onLogin)
                is AppState.Authenticated -> AccountPanel(
                    session  = appState.session,
                    onLogout = onLogout
                )
            }
        }

        Divider(
            color     = CelestiaTheme.colors.surface.copy(alpha = 0.7f),
            thickness = 1.dp
        )

        // ── News feed (bottom) ────────────────────────────────────────────────
        CompactNewsFeed(modifier = Modifier.weight(1f).fillMaxWidth())
    }
}

// ─── Login Panel ─────────────────────────────────────────────────────────────

@Composable
fun LoginPanel(onLogin: (SessionData) -> Unit) {
    val authService: IAuthService          = koinInject()
    val credentialsManager: CredentialsManager = koinInject()
    val profileManager: ProfileManager     = koinInject()
    val s     = LocalStrings.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    var login        by remember { mutableStateOf("") }
    var password     by remember { mutableStateOf("") }
    var rememberMe   by remember { mutableStateOf(true) }
    var isLoading    by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val inputColors = TextFieldDefaults.outlinedTextFieldColors(
        textColor            = CelestiaTheme.colors.textPrimary,
        focusedBorderColor   = CelestiaTheme.colors.primary,
        unfocusedBorderColor = CelestiaTheme.colors.textSecondary.copy(alpha = 0.22f),
        focusedLabelColor    = CelestiaTheme.colors.primary,
        unfocusedLabelColor  = CelestiaTheme.colors.textSecondary,
        cursorColor          = CelestiaTheme.colors.primary,
        backgroundColor      = Color.Transparent
    )

    fun doLogin() {
        if (login.isBlank() || password.isBlank()) { errorMessage = s.loginErrorEmpty; return }
        focusManager.clearFocus()
        isLoading = true
        errorMessage = null
        scope.launch {
            try {
                val session = withContext(Dispatchers.IO) {
                    val lastServer = profileManager.lastServerId ?: AppConfig.DEFAULT_SERVER_ID
                    val sess = authService.login(login, password, lastServer)
                    if (rememberMe) credentialsManager.save(sess)
                    sess
                }
                onLogin(session)
            } catch (e: Exception) {
                isLoading = false
                errorMessage = e.message
                    ?.replace("java.lang.Exception: ", "")
                    ?.substringAfter("API: ")
                    ?: s.loginErrorGeneric
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text       = s.loginTitle,
            style      = MaterialTheme.typography.subtitle1,
            fontWeight = FontWeight.Bold,
            color      = CelestiaTheme.colors.textPrimary
        )

        AnimatedVisibility(visible = errorMessage != null) {
            Text(
                text     = errorMessage ?: "",
                style    = MaterialTheme.typography.caption,
                color    = CelestiaTheme.colors.error,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = CelestiaTheme.colors.error.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(6.dp)
                    )
                    .padding(8.dp)
            )
        }

        OutlinedTextField(
            value         = login,
            onValueChange = { login = it; errorMessage = null },
            label         = { Text(s.loginUsername) },
            modifier      = Modifier.fillMaxWidth(),
            singleLine    = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
            shape  = RoundedCornerShape(8.dp),
            colors = inputColors
        )

        OutlinedTextField(
            value                  = password,
            onValueChange          = { password = it; errorMessage = null },
            label                  = { Text(s.loginPassword) },
            modifier               = Modifier.fillMaxWidth(),
            singleLine             = true,
            visualTransformation   = PasswordVisualTransformation(),
            keyboardOptions        = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions        = KeyboardActions(onDone = { doLogin() }),
            shape  = RoundedCornerShape(8.dp),
            colors = inputColors
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked         = rememberMe,
                onCheckedChange = { rememberMe = it },
                colors          = CheckboxDefaults.colors(
                    checkedColor   = CelestiaTheme.colors.primary,
                    uncheckedColor = CelestiaTheme.colors.textSecondary.copy(alpha = 0.4f)
                )
            )
            Text(
                text  = s.loginRemember,
                style = MaterialTheme.typography.caption,
                color = CelestiaTheme.colors.textSecondary
            )
        }

        Button(
            onClick  = { doLogin() },
            enabled  = !isLoading,
            modifier = Modifier.fillMaxWidth().height(42.dp),
            shape    = RoundedCornerShape(8.dp),
            colors   = ButtonDefaults.buttonColors(
                backgroundColor         = CelestiaTheme.colors.primary,
                disabledBackgroundColor = CelestiaTheme.colors.primary.copy(alpha = 0.5f)
            )
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    color       = Color.White,
                    modifier    = Modifier.size(18.dp),
                    strokeWidth = 2.dp
                )
            } else {
                Text(s.loginButton, color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ─── Account Panel ────────────────────────────────────────────────────────────

@Composable
fun AccountPanel(session: SessionData, onLogout: () -> Unit) {
    val s = LocalStrings.current
    var faceBitmap by remember { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(session.playerName) {
        faceBitmap = SkinManager.getSkinFront(session.playerName)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Face
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(CelestiaTheme.colors.surface),
            contentAlignment = Alignment.TopCenter
        ) {
            if (faceBitmap != null) {
                Image(
                    painter           = BitmapPainter(faceBitmap!!),
                    contentDescription = null,
                    modifier          = Modifier.size(38.dp),
                    contentScale      = ContentScale.Crop,
                    alignment         = Alignment.TopCenter
                )
            } else {
                Text(
                    text     = session.playerName.take(1).uppercase(),
                    color    = CelestiaTheme.colors.primary,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text       = session.playerName,
                style      = MaterialTheme.typography.subtitle2,
                fontWeight = FontWeight.Bold,
                color      = CelestiaTheme.colors.textPrimary,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis
            )
            Text(
                text  = s.profileStatusOnline,
                style = MaterialTheme.typography.caption,
                color = CelestiaTheme.colors.success
            )
        }

        TextButton(
            onClick  = onLogout,
            modifier = Modifier.height(30.dp)
        ) {
            Text(
                text  = s.navLogout,
                style = MaterialTheme.typography.caption,
                color = CelestiaTheme.colors.error.copy(alpha = 0.65f)
            )
        }
    }
}

// ─── Compact News Feed ────────────────────────────────────────────────────────

@Composable
fun CompactNewsFeed(modifier: Modifier = Modifier) {
    val serverListService: IServerListService = koinInject()
    val httpClient: OkHttpClient              = koinInject()
    val s       = LocalStrings.current
    val context = LocalPlatformContext.current

    val imageLoader = remember {
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { httpClient })) }
            .build()
    }

    var news    by remember { mutableStateOf<List<NewsItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        try {
            val data = withContext(Dispatchers.IO) { serverListService.fetchDashboardData().get() }
            news = data.news
        } catch (_: Exception) {}
        loading = false
    }

    Column(modifier = modifier) {
        // Section header
        Text(
            text     = s.newsTitle,
            style    = MaterialTheme.typography.caption,
            fontWeight = FontWeight.Bold,
            color    = CelestiaTheme.colors.textSecondary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
        )

        Divider(
            color     = CelestiaTheme.colors.surface.copy(alpha = 0.6f),
            thickness = 1.dp
        )

        when {
            loading -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    color       = CelestiaTheme.colors.primary.copy(alpha = 0.4f),
                    modifier    = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
            }

            news.isEmpty() -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text  = s.newsEmpty,
                    style = MaterialTheme.typography.caption,
                    color = CelestiaTheme.colors.textSecondary
                )
            }

            else -> LazyColumn(contentPadding = PaddingValues(vertical = 4.dp)) {
                items(news) { item ->
                    CompactNewsItem(item = item, imageLoader = imageLoader)
                    Divider(
                        color     = CelestiaTheme.colors.surface.copy(alpha = 0.4f),
                        thickness = 1.dp,
                        modifier  = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactNewsItem(item: NewsItem, imageLoader: ImageLoader) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {}   // TODO: open URL when NewsItem gets one
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Thumbnail
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(CelestiaTheme.colors.surface)
        ) {
            if (item.imageUrl != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalPlatformContext.current)
                        .data(item.imageUrl)
                        .crossfade(true)
                        .build(),
                    imageLoader        = imageLoader,
                    contentDescription = null,
                    contentScale       = ContentScale.Crop,
                    modifier           = Modifier.fillMaxSize()
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text       = item.title,
                style      = MaterialTheme.typography.caption,
                fontWeight = FontWeight.Medium,
                color      = CelestiaTheme.colors.textPrimary,
                maxLines   = 2,
                overflow   = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text  = item.date,
                style = MaterialTheme.typography.overline,
                color = CelestiaTheme.colors.primary.copy(alpha = 0.7f)
            )
        }
    }
}

// ─── Auth loading slot ────────────────────────────────────────────────────────

@Composable
private fun AuthLoadingSlot() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(100.dp),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            color       = CelestiaTheme.colors.primary.copy(alpha = 0.35f),
            modifier    = Modifier.size(22.dp),
            strokeWidth = 2.dp
        )
    }
}
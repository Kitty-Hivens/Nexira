package hivens.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
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
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import hivens.config.Protocol
import hivens.core.api.AuthException
import hivens.core.api.TwoFactorRequiredException
import hivens.core.data.AuthStatus
import hivens.core.api.interfaces.IAuthService
import hivens.core.api.interfaces.IServerListService
import hivens.core.data.NewsItem
import hivens.core.data.SessionData
import hivens.launcher.CredentialsManager
import hivens.launcher.NetworkState
import hivens.launcher.ProfileManager
import hivens.launcher.network.ServerProtocolConfig
import hivens.ui.components.ConfirmCodeDialog
import hivens.ui.easter.AprilFoolsButton
import hivens.ui.i18n.LocalStrings
import hivens.ui.puppet.PuppetClick
import hivens.ui.puppet.PuppetField
import hivens.ui.puppet.PuppetScreen
import hivens.ui.puppet.PuppetToggle
import hivens.ui.theme.CelestiaTheme
import hivens.ui.utils.SkinManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import org.koin.core.qualifier.named
import org.slf4j.LoggerFactory
import java.awt.Desktop
import java.net.URI

private val log = LoggerFactory.getLogger("RightPanel")

// ─── Right Panel ─────────────────────────────────────────────────────────────

@Composable
fun RightPanel(
    appState: AppState,
    onLogin: (SessionData) -> Unit,
    onLogout: () -> Unit,
    sslBypass: Boolean,
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
                AppState.Loading          -> AuthLoadingSlot()
                AppState.Unauthenticated  -> LoginPanel(onLogin = onLogin)
                is AppState.Authenticated -> AccountPanel(
                    session  = appState.session,
                    onLogout = onLogout
                )
            }
        }

        HorizontalDivider(color = CelestiaTheme.colors.surface.copy(alpha = 0.7f))

        // ── News feed (bottom) ────────────────────────────────────────────────
        CompactNewsFeed(
            sslBypass = sslBypass,
            modifier  = Modifier.weight(1f).fillMaxWidth()
        )
    }
}

// ─── Login Panel ─────────────────────────────────────────────────────────────

@Composable
fun LoginPanel(onLogin: (SessionData) -> Unit) {
    val authService: IAuthService              = koinInject()
    val insecureAuthService: IAuthService      = koinInject(named("insecure"))
    val credentialsManager: CredentialsManager = koinInject()
    val profileManager: ProfileManager         = koinInject()
    val protocolConfig: ServerProtocolConfig   = koinInject()
    val s            = LocalStrings.current
    val scope        = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    var login        by remember { mutableStateOf("") }
    var password     by remember { mutableStateOf("") }
    var rememberMe   by remember { mutableStateOf(true) }
    var isLoading    by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var sslWarning   by remember { mutableStateOf(false) }

    // 2FA flow state (#159). twoFactorPending is non-null while the
    // ConfirmCodeDialog is open -- it stores the originating credentials
    // plus the uid the server returned in the TWOAUTH login response so
    // completeTwoFactor() can sign the follow-up. Cleared on dismiss /
    // success / TWO_FACTOR_EXPIRED.
    data class TwoFactorPending(val uid: String, val username: String, val password: String, val serverId: String)
    var twoFactorPending  by remember { mutableStateOf<TwoFactorPending?>(null) }
    var twoFactorError    by remember { mutableStateOf<String?>(null) }
    var twoFactorBusy     by remember { mutableStateOf(false) }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor        = CelestiaTheme.colors.textPrimary,
        unfocusedTextColor      = CelestiaTheme.colors.textPrimary,
        focusedBorderColor      = CelestiaTheme.colors.primary,
        unfocusedBorderColor    = CelestiaTheme.colors.textSecondary.copy(alpha = 0.22f),
        focusedLabelColor       = CelestiaTheme.colors.primary,
        unfocusedLabelColor     = CelestiaTheme.colors.textSecondary,
        cursorColor             = CelestiaTheme.colors.primary,
        focusedContainerColor   = Color.Transparent,
        unfocusedContainerColor = Color.Transparent
    )

    fun doLogin(service: IAuthService = authService) {
        if (login.isBlank() || password.isBlank()) { errorMessage = s.loginErrorEmpty; return }
        focusManager.clearFocus()
        isLoading    = true
        sslWarning   = false
        errorMessage = null
        hivens.core.diag.ActionRing.record("Login attempt: user=$login")
        scope.launch {
            try {
                val session = withContext(Dispatchers.IO) {
                    val lastServer = profileManager.lastServerId ?: Protocol.DEFAULT_SERVER_ID
                    val sess = service.login(login, password, lastServer)
                    if (rememberMe) credentialsManager.save(sess)
                    sess
                }
                hivens.core.diag.ActionRing.record("Login OK: user=$login")
                onLogin(session)
            } catch (e: TwoFactorRequiredException) {
                // Account has 2FA -- open the code dialog instead of treating
                // it as a hard error. uid may be null when the server omits
                // it from the TWOAUTH response (spec quirk); surface clearly
                // so user knows to retry the full login.
                isLoading = false
                hivens.core.diag.ActionRing.record("Login: 2FA required, user=$login uid=${e.uid?.take(8) ?: "<missing>"}")
                val pendingUid = e.uid
                if (pendingUid.isNullOrBlank()) {
                    errorMessage = s.auth2faExpired
                } else {
                    val lastServer = profileManager.lastServerId ?: Protocol.DEFAULT_SERVER_ID
                    twoFactorError = null
                    twoFactorPending = TwoFactorPending(
                        uid = pendingUid, username = login, password = password, serverId = lastServer,
                    )
                }
            } catch (e: AuthException) {
                isLoading = false
                hivens.core.diag.ActionRing.record(
                    "Login failed (auth): user=$login ssl=${e.isSslError} msg=${e.message?.take(80)}"
                )
                when {
                    e.isSslError -> sslWarning = true
                    else         -> errorMessage = e.message
                        ?.replace("java.lang.Exception: ", "")
                        ?.substringAfter("API: ")
                        ?: s.loginErrorGeneric
                }
            } catch (e: Exception) {
                isLoading    = false
                hivens.core.diag.ActionRing.record("Login failed (generic): user=$login msg=${e.message?.take(80)}")
                errorMessage = e.message ?: s.loginErrorGeneric
            }
        }
    }

    fun submitTwoFactor(code: String) {
        val pending = twoFactorPending ?: return
        twoFactorBusy = true
        twoFactorError = null
        scope.launch {
            try {
                val session = withContext(Dispatchers.IO) {
                    val sess = authService.completeTwoFactor(
                        username = pending.username, password = pending.password,
                        serverId = pending.serverId, uid = pending.uid, code = code,
                    )
                    if (rememberMe) credentialsManager.save(sess)
                    sess
                }
                hivens.core.diag.ActionRing.record("Login OK after 2FA: user=${pending.username}")
                twoFactorBusy = false
                twoFactorPending = null
                onLogin(session)
            } catch (e: AuthException) {
                twoFactorBusy = false
                hivens.core.diag.ActionRing.record(
                    "2FA verify failed: user=${pending.username} status=${e.status}"
                )
                when (e.status) {
                    AuthStatus.WRONG_CODE -> twoFactorError = s.auth2faInvalid
                    AuthStatus.TWO_FACTOR_EXPIRED -> {
                        // Session is gone server-side -- close the dialog and
                        // surface in the main login form so the user retries
                        // from scratch.
                        twoFactorPending = null
                        errorMessage = s.auth2faExpired
                    }
                    else -> twoFactorError = e.message ?: s.loginErrorGeneric
                }
            } catch (e: Exception) {
                twoFactorBusy = false
                twoFactorError = e.message ?: s.loginErrorGeneric
            }
        }
    }

    // 2FA prompt -- renders only while we're awaiting a code. Decoupled
    // from the login form below so the form retains its state (username,
    // password, rememberMe) for the resume path. Dismissal cancels the
    // 2FA flow without clearing the form, letting the user retry.
    twoFactorPending?.let {
        // Puppet: this dialog overrides the screen marker while open so
        // /screen returns "Login_2FA" -- drivers can detect the modal and
        // pivot to the 2FA-specific element ids instead of the form ones.
        PuppetScreen("Login_2FA")
        ConfirmCodeDialog(
            onDismiss = {
                twoFactorPending = null
                twoFactorError = null
                twoFactorBusy = false
            },
            onSubmit = { code -> submitTwoFactor(code) },
            errorMessage = twoFactorError,
            isSubmitting = twoFactorBusy,
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        PuppetScreen("Login")
        Text(
            text       = s.loginTitle,
            style      = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color      = CelestiaTheme.colors.textPrimary
        )

        // ── SSL warning banner ────────────────────────────────────────────
        if (sslWarning) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = Color(0xFFF59E0B).copy(alpha = 0.12f),
                        shape = RoundedCornerShape(8.dp)
                    )
                    .border(
                        width = 1.dp,
                        color = Color(0xFFF59E0B).copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp)
                    )
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text       = "⚠ ${s.sslWarningTitle}",
                    style      = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color      = Color(0xFFF59E0B)
                )
                Text(
                    text  = s.sslWarningBody,
                    style = MaterialTheme.typography.bodySmall,
                    color = CelestiaTheme.colors.textPrimary.copy(alpha = 0.85f)
                )
                // Trust-duration prompt + 3 grant buttons. Each click both
                // grants the bypass for that duration AND retries login --
                // single-click UX. Cancel button is on its own row above so
                // the dangerous actions don't accidentally read as the same
                // affordance as the safe one.
                OutlinedButton(
                    onClick  = { sslWarning = false },
                    modifier = Modifier.fillMaxWidth(),
                    shape    = RoundedCornerShape(6.dp)
                ) {
                    Text(s.sslWarningCancel, color = CelestiaTheme.colors.textSecondary)
                }
                Text(
                    text  = s.sslWarningTrustPrompt,
                    style = MaterialTheme.typography.bodySmall,
                    color = CelestiaTheme.colors.textSecondary,
                )
                val acceptColors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF59E0B))
                fun acceptFor(unit: java.time.temporal.ChronoUnit, amount: Long, label: String) {
                    // 100-year future for "always" -- long enough that no user
                    // will outlive it, short enough to not overflow ISO-8601
                    // formatting that a far-future Instant.MAX would.
                    val until = java.time.Instant.now().plus(amount, unit)
                    hivens.core.diag.ActionRing.record(
                        "SSL bypass accepted by user (login retry) -- granted: $label",
                    )
                    NetworkState.grantBypass(protocolConfig.sslBypassHost, until)
                    doLogin(insecureAuthService)
                }
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick  = { acceptFor(java.time.temporal.ChronoUnit.HOURS, 1, "1 hour") },
                        modifier = Modifier.weight(1f),
                        shape    = RoundedCornerShape(6.dp),
                        colors   = acceptColors,
                    ) { Text(s.sslWarningTrustHour, color = Color.Black) }
                    Button(
                        onClick  = { acceptFor(java.time.temporal.ChronoUnit.DAYS, 30, "30 days") },
                        modifier = Modifier.weight(1f),
                        shape    = RoundedCornerShape(6.dp),
                        colors   = acceptColors,
                    ) { Text(s.sslWarningTrust30Days, color = Color.Black) }
                    Button(
                        onClick  = { acceptFor(java.time.temporal.ChronoUnit.DAYS, 36500, "always (100y)") },
                        modifier = Modifier.weight(1f),
                        shape    = RoundedCornerShape(6.dp),
                        colors   = acceptColors,
                    ) { Text(s.sslWarningTrustAlways, color = Color.Black) }
                }
            }
        }

        // ── Regular error ─────────────────────────────────────────────────
        if (errorMessage != null) {
            Text(
                text     = errorMessage ?: "",
                style    = MaterialTheme.typography.bodySmall,
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

        // ── Fields ────────────────────────────────────────────────────────
        OutlinedTextField(
            value         = login,
            onValueChange = { login = it; errorMessage = null; sslWarning = false },
            label         = { Text(s.loginUsername) },
            modifier      = Modifier.fillMaxWidth(),
            singleLine    = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
            shape          = RoundedCornerShape(8.dp),
            colors         = fieldColors
        )
        PuppetField("login.username", login) {
            login = it
            errorMessage = null
            sslWarning = false
        }

        OutlinedTextField(
            value                = password,
            onValueChange        = { password = it; errorMessage = null; sslWarning = false },
            label                = { Text(s.loginPassword) },
            modifier             = Modifier.fillMaxWidth(),
            singleLine           = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions      = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction    = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(onDone = { doLogin() }),
            shape   = RoundedCornerShape(8.dp),
            colors  = fieldColors
        )
        PuppetField("login.password", password) {
            password = it
            errorMessage = null
            sslWarning = false
        }

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
                style = MaterialTheme.typography.bodySmall,
                color = CelestiaTheme.colors.textSecondary
            )
        }
        PuppetToggle("login.rememberMe", rememberMe) { rememberMe = it }

        // LOG IN -- chaos target (only when not loading, loading state stays reliable)
        if (isLoading) {
            Button(
                onClick   = {},
                enabled   = false,
                modifier  = Modifier.fillMaxWidth().height(42.dp),
                shape     = RoundedCornerShape(8.dp),
                colors    = ButtonDefaults.buttonColors(
                    disabledContainerColor = CelestiaTheme.colors.primary.copy(alpha = 0.5f)
                ),
                elevation = ButtonDefaults.buttonElevation(0.dp)
            ) {
                CircularProgressIndicator(
                    color       = Color.White,
                    modifier    = Modifier.size(18.dp),
                    strokeWidth = 2.dp
                )
            }
        } else {
            AprilFoolsButton(
                id       = "login_submit_btn",
                text     = s.loginButton,
                onClick  = { doLogin() },
                modifier = Modifier.fillMaxWidth().height(42.dp),
                colors   = ButtonDefaults.buttonColors(
                    containerColor = CelestiaTheme.colors.primary,
                ),
            )
        }
        PuppetClick("login.submit", enabled = !isLoading) { doLogin() }

        // REGISTER -- chaos target (#105)
        AprilFoolsButton(
            id      = "login_register_btn",
            text    = s.loginRegister,
            onClick = {
                runCatching {
                    val url = "${protocolConfig.baseUrl}/register"
                    if (Desktop.isDesktopSupported() &&
                        Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)
                    ) {
                        Desktop.getDesktop().browse(URI(url))
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(42.dp),
            colors   = ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                contentColor   = CelestiaTheme.colors.primary,
            ),
        )
        PuppetClick("login.register") {
            runCatching {
                val url = "${protocolConfig.baseUrl}/register"
                if (Desktop.isDesktopSupported() &&
                    Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)
                ) {
                    Desktop.getDesktop().browse(URI(url))
                }
            }
        }
    }
}

// ─── Account Panel ────────────────────────────────────────────────────────────

@Composable
fun AccountPanel(session: SessionData, onLogout: () -> Unit) {
    val skinManager: SkinManager = koinInject()
    val s = LocalStrings.current
    var faceBitmap by remember { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(session.playerName) {
        faceBitmap = skinManager.getSkinFront(session.playerName)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment     = Alignment.CenterVertically,
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
                    painter            = BitmapPainter(faceBitmap!!),
                    contentDescription = null,
                    modifier           = Modifier.size(38.dp),
                    contentScale       = ContentScale.Crop,
                    alignment          = Alignment.TopCenter
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
                style      = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color      = CelestiaTheme.colors.textPrimary,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis
            )
            Text(
                text  = s.profileStatusOnline,
                style = MaterialTheme.typography.bodySmall,
                color = CelestiaTheme.colors.success
            )
        }

        // Logout is NOT chaos-wrapped -- user must always be able to log out
        TextButton(
            onClick  = onLogout,
            modifier = Modifier.height(30.dp)
        ) {
            Text(
                text  = s.navLogout,
                style = MaterialTheme.typography.bodySmall,
                color = CelestiaTheme.colors.error.copy(alpha = 0.65f)
            )
        }
        // Puppet: secondary logout entry-point (the other is the sidebar
        // ExitToApp icon, which is `nav.logout`). Kept distinct because a
        // regression could hit just one of them -- e.g. a layout change
        // that detaches the right-panel logout but leaves the sidebar.
        PuppetClick("account.logout") { onLogout() }
    }
}

// ─── Compact News Feed ────────────────────────────────────────────────────────

@Composable
fun CompactNewsFeed(
    sslBypass: Boolean = false,
    modifier: Modifier = Modifier
) {
    val serverListService: IServerListService = koinInject()
    val s       = LocalStrings.current

    var news    by remember { mutableStateOf<List<NewsItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        try {
            val data = withContext(Dispatchers.IO) { serverListService.fetchDashboardData().get() }
            news = data.news
        } catch (e: Exception) {
            log.warn("Initial news fetch failed", e)
        }
        loading = false
    }

    LaunchedEffect(sslBypass) {
        if (sslBypass && news.isEmpty()) {
            try {
                val data = withContext(Dispatchers.IO) { serverListService.fetchDashboardData().get() }
                news = data.news
            } catch (e: Exception) {
                log.warn("News fetch after SSL bypass failed", e)
            }
            loading = false
        }
    }

    Column(modifier = modifier) {
        // Section header
        Text(
            text       = s.newsTitle,
            style      = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color      = CelestiaTheme.colors.textSecondary,
            modifier   = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
        )

        HorizontalDivider(color = CelestiaTheme.colors.surface.copy(alpha = 0.6f))

        when {
            loading -> NewsSkeleton()

            news.isEmpty() -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text  = s.newsEmpty,
                    style = MaterialTheme.typography.bodySmall,
                    color = CelestiaTheme.colors.textSecondary
                )
            }

            else -> LazyColumn(contentPadding = PaddingValues(vertical = 4.dp)) {
                items(news) { item ->
                    CompactNewsItem(item = item)
                    HorizontalDivider(
                        color    = CelestiaTheme.colors.surface.copy(alpha = 0.4f),
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }
        }
    }
}

// ─── Skeleton loader ──────────────────────────────────────────────────────────

@Composable
private fun NewsSkeleton() {
    val shimmerColors = listOf(
        CelestiaTheme.colors.surface.copy(alpha = 0.6f),
        CelestiaTheme.colors.surface.copy(alpha = 0.25f),
        CelestiaTheme.colors.surface.copy(alpha = 0.6f),
    )

    val transition = rememberInfiniteTransition(label = "skeleton")
    val translateAnim by transition.animateFloat(
        initialValue  = 0f,
        targetValue   = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer"
    )

    val brush = Brush.linearGradient(
        colors = shimmerColors,
        start  = Offset(translateAnim - 300f, 0f),
        end    = Offset(translateAnim, 0f)
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        repeat(4) {
            SkeletonNewsItem(brush)
            HorizontalDivider(
                color    = CelestiaTheme.colors.surface.copy(alpha = 0.4f),
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
    }
}

@Composable
private fun SkeletonNewsItem(brush: Brush) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Thumbnail placeholder
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(brush)
        )

        Column(
            modifier            = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Title line
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .height(12.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(brush)
            )
            // Second title line (shorter)
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.55f)
                    .height(10.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(brush)
            )
            // Date line
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.35f)
                    .height(9.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(brush)
            )
        }
    }
}

// ─── News item ────────────────────────────────────────────────────────────────

@Composable
private fun CompactNewsItem(item: NewsItem) {
    val protocolConfig: ServerProtocolConfig = koinInject()
    // Try to open a URL if the NewsItem has one (currently description holds "Views: N",
    // but we keep the click hook ready for when the backend sends real URLs)
    val canOpenUrl = item.imageUrl != null  // reuse as proxy; swap for item.url when available

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = canOpenUrl) {
                // Build a best-effort URL from the image URL pattern:
                // https://smartycraft.ru/images/news/mini/news1.jpg  ->  https://smartycraft.ru/news{id}
                try {
                    val url = "${protocolConfig.baseUrl}/news${item.id}"
                    if (Desktop.isDesktopSupported() &&
                        Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)
                    ) {
                        Desktop.getDesktop().browse(URI(url))
                    }
                } catch (e: Exception) {
                    log.warn("Could not open news link for item ${item.id}", e)
                }
            }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment     = Alignment.CenterVertically,
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
                    contentDescription = null,
                    contentScale       = ContentScale.Crop,
                    modifier           = Modifier.fillMaxSize()
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text       = item.title,
                style      = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color      = CelestiaTheme.colors.textPrimary,
                maxLines   = 2,
                overflow   = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text  = item.date,
                style = MaterialTheme.typography.labelSmall,
                color = CelestiaTheme.colors.primary.copy(alpha = 0.7f)
            )
        }

        // Subtle arrow hint that item is clickable
        if (canOpenUrl) {
            Text(
                text  = "›",
                style = MaterialTheme.typography.bodyMedium,
                color = CelestiaTheme.colors.textSecondary.copy(alpha = 0.4f)
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

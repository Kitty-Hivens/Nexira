package hivens.ui.widgets.profile
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import hivens.core.data.SessionData
import hivens.ui.components.GlassCard
import hivens.ui.customization.glassSurfaceAlpha
import hivens.ui.i18n.LocalStrings
import hivens.ui.identity.SkinManager
import hivens.ui.platform.SystemActions
import hivens.ui.puppet.PuppetClick
import hivens.ui.puppet.PuppetScreen
import hivens.ui.theme.NxTheme
import hivens.widget.api.SlotRenderer
import hivens.widget.model.SlotId
import hivens.widget.model.SurfaceId
import org.koin.compose.koinInject

private const val SURFACE = "profile"

// profile surface composable. AppLayout routes Screen.Profile here.
// Slots: `nav` (left rail, fixed width), `signin` (right pane, the login
// form when signed out / credential management when signed in), `account`
// (right pane, the skin-forward identity hero when signed in). The `skin`
// slot stays seeded but unrendered -- its widget is dormant until a
// dedicated skin screen is built; the skin now leads the Account tab.
// Header title stays in surface chrome -- a per-screen invariant the user
// cannot meaningfully remove without losing the screen's identity. Inner
// glass frame opts out of style.cardSurface so the screen stays glassy
// under Brut, matching the Settings frame.
//
// Only one of `signin` / `account` renders at a time; the inactive slot is
// unmounted so the editor's chrome decorator does not paint phantom chrome
// around the hidden section.
//
// While startup auto-login is still resolving ([authResolving]) the card
// shows a spinner instead of mounting the sign-in form -- the same Loading
// treatment Home gives, so the ~1s credential check doesn't flash a full
// login form at an already signed-in user.
//
// No verticalScroll on the right pane. A scroll modifier hands
// children a maxHeight = Infinity constraint, and LazyList-based
// widgets (CompactNewsFeed, LibraryBody, ...) abort with an
// `infinity maximum height` IllegalStateException. Without scroll,
// the parent Column distributes its bounded height among non-
// weighted children, so any widget the user drops via the editor
// renders. Trade-off: a stack of fixed-height widgets exceeding
// the pane overflows off-screen; the user resets the surface to
// recover.
@Composable
fun ProfileSurface(
    session: SessionData?,
    authResolving: Boolean,
    onLogin: (SessionData) -> Unit,
    onLogout: () -> Unit,
) {
    val s = LocalStrings.current
    val skinManager: SkinManager = koinInject()
    // Per-provider sections each own their signed-in/out state, so the category
    // no longer flips with the session; default to the SmartyCraft section.
    val selectedCategory = remember { mutableStateOf(ProfileCategory.SmartyCraft) }

    val ctx = remember(session, selectedCategory) {
        ProfileContext(
            session          = session,
            selectedCategory = selectedCategory,
            onLogin          = onLogin,
            onLogout         = onLogout,
        )
    }

    // Puppet handlers stay at surface scope so automation drivers can invoke
    // them regardless of the active tab. Skin refresh / top-up are signed-in
    // only, so they no-op without a session.
    PuppetScreen("Profile")
    PuppetClick("profile.refreshSkin") { session?.let { skinManager.invalidate(it.playerName) } }
    PuppetClick("profile.topUp")       { SystemActions.openUrl("http://smartycraft.ru/cabinet") }

    CompositionLocalProvider(LocalProfileContext provides ctx) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            // Title lives in the top-bar breadcrumb now -- no in-screen duplicate.
            GlassCard(
                modifier        = Modifier.weight(1f).fillMaxWidth(),
                backgroundColor = glassSurfaceAlpha(0.7f),
            ) {
                if (authResolving && session == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            color       = NxTheme.colors.primary.copy(alpha = 0.35f),
                            modifier    = Modifier.size(28.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                } else {
                    Row(Modifier.fillMaxSize().padding(16.dp)) {
                        SlotRenderer(SurfaceId(SURFACE), SlotId("nav"), Modifier.width(200.dp).fillMaxHeight())
                        // The slot ids are historical: "account" hosts the SmartyCraft
                        // section, "signin" the Microsoft one. Reused as-is so the
                        // bundled layout + reconcile stay untouched.
                        when (selectedCategory.value) {
                            ProfileCategory.SmartyCraft ->
                                SlotRenderer(SurfaceId(SURFACE), SlotId("account"), Modifier.weight(1f).fillMaxHeight())
                            ProfileCategory.Microsoft ->
                                SlotRenderer(SurfaceId(SURFACE), SlotId("signin"), Modifier.weight(1f).fillMaxHeight())
                        }
                    }
                }
            }
        }
    }
}

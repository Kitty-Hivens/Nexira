package hivens.ui.widgets.profile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.core.data.SessionData
import hivens.ui.components.GlassCard
import hivens.ui.customization.glassSurfaceAlpha
import hivens.ui.i18n.LocalStrings
import hivens.ui.identity.SkinManager
import hivens.ui.platform.SystemActions
import hivens.ui.puppet.PuppetClick
import hivens.ui.puppet.PuppetScreen
import hivens.ui.theme.CelestiaTheme
import hivens.widget.api.SlotRenderer
import hivens.widget.model.SlotId
import hivens.widget.model.SurfaceId
import org.koin.compose.koinInject

private const val SURFACE = "profile"

// profile surface composable. AppLayout routes Screen.Profile here.
// Three slots: `nav` (left rail, fixed width), `skin` (right pane,
// shown when category = Skin), `account` (right pane, shown when
// category = Account). Header title stays in surface chrome -- the
// title is a per-screen invariant the user cannot meaningfully
// remove without losing the screen's identity. Inner glass frame
// opted out of style.cardSurface so the screen stays glassy under
// Brut, matching the Settings frame.
//
// Only one of `skin` / `account` slots renders at a time; the
// inactive slot is unmounted entirely so the editor's chrome
// decorator does not paint phantom chrome around the hidden section.
// To edit the inactive section the user switches tabs via the nav.
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
fun ProfileSurface(session: SessionData) {
    val s = LocalStrings.current
    val skinManager: SkinManager = koinInject()
    val selectedCategory = remember { mutableStateOf(ProfileCategory.Skin) }

    val ctx = remember(session, selectedCategory) {
        ProfileContext(
            session          = session,
            selectedCategory = selectedCategory,
        )
    }

    // Puppet handlers stay at surface scope so automation drivers
    // can invoke them regardless of which tab is active. The legacy
    // ProfileScreen registered both at the screen body and the
    // refresh / top-up effects are session-global -- not tab-bound.
    // The visible buttons inside the per-tab widgets still call the
    // same actions; this just keeps the automation contract stable.
    PuppetScreen("Profile")
    PuppetClick("profile.refreshSkin") { skinManager.invalidate(session.playerName) }
    PuppetClick("profile.topUp")       { SystemActions.openUrl("http://smartycraft.ru/cabinet") }

    CompositionLocalProvider(LocalProfileContext provides ctx) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text(
                text       = s.profileTitle,
                style      = MaterialTheme.typography.headlineSmall,
                color      = CelestiaTheme.colors.textPrimary,
                fontWeight = FontWeight.Bold,
            )

            Spacer(Modifier.height(24.dp))

            GlassCard(
                modifier        = Modifier.weight(1f).fillMaxWidth(),
                backgroundColor = glassSurfaceAlpha(0.7f),
            ) {
                Row(Modifier.fillMaxSize().padding(16.dp)) {
                    Box(modifier = Modifier.width(200.dp).fillMaxHeight()) {
                        SlotRenderer(SurfaceId(SURFACE), SlotId("nav"))
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    ) {
                        when (selectedCategory.value) {
                            ProfileCategory.Skin    -> SlotRenderer(SurfaceId(SURFACE), SlotId("skin"))
                            ProfileCategory.Account -> SlotRenderer(SurfaceId(SURFACE), SlotId("account"))
                        }
                    }
                }
            }
        }
    }
}

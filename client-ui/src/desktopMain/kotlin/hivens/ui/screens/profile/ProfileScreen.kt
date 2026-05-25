package hivens.ui.screens.profile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.core.api.SkinRepository
import hivens.core.data.SessionData
import hivens.ui.components.GlassCard
import hivens.ui.customization.glassSurfaceAlpha
import hivens.ui.i18n.LocalStrings
import hivens.ui.identity.SkinManager
import hivens.ui.puppet.PuppetClick
import hivens.ui.puppet.PuppetScreen
import hivens.ui.theme.CelestiaTheme
import org.koin.compose.koinInject

/**
 * Profile orchestrator. Same two-column shape as SettingsScreen:
 * vertical category nav on the left, selected category's content on
 * the right. Sections ([SkinSection], [AccountSection]) own their
 * own state -- the orchestrator only routes between them.
 */
@Composable
fun ProfileScreen(session: SessionData, skinRepository: SkinRepository) {
    val skinManager: SkinManager = koinInject()
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()

    var selectedCategory by remember { mutableStateOf(ProfileCategory.Skin) }

    PuppetScreen("Profile")
    PuppetClick("profile.refreshSkin") {
        skinManager.invalidate(session.playerName)
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            text       = s.profileTitle,
            style      = MaterialTheme.typography.headlineSmall,
            color      = CelestiaTheme.colors.textPrimary,
            fontWeight = FontWeight.Bold,
        )

        Spacer(Modifier.height(24.dp))

        // Outer frame opted out of style.cardSurface so Profile stays
        // glassy under Brut, same rule as the Settings frame.
        GlassCard(
            modifier        = Modifier.weight(1f).fillMaxWidth(),
            backgroundColor = glassSurfaceAlpha(0.7f),
        ) {
            Row(Modifier.fillMaxSize().padding(16.dp)) {
                ProfileCategoryNav(
                    current  = selectedCategory,
                    onSelect = { selectedCategory = it },
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                ) {
                    when (selectedCategory) {
                        ProfileCategory.Skin -> SkinSection(
                            session        = session,
                            skinRepository = skinRepository,
                            skinManager    = skinManager,
                            scope          = scope,
                        )
                        ProfileCategory.Account -> AccountSection(session = session)
                    }
                }
            }
        }
    }
}

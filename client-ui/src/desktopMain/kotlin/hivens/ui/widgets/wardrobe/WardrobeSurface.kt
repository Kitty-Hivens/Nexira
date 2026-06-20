package hivens.ui.widgets.wardrobe

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.core.data.SessionData
import hivens.ui.components.GlassCard
import hivens.ui.customization.glassSurfaceAlpha
import hivens.ui.i18n.LocalStrings
import hivens.ui.puppet.PuppetScreen
import hivens.ui.theme.CelestiaTheme
import hivens.ui.widgets.profile.SkinHero

// Wardrobe screen -- the dedicated skins + capes workspace. This first pass is
// the live 3D preview of the current look; the local library, default skins,
// per-provider apply (SmartyCraft + Mojang) and capes land on top of it.
@Composable
fun WardrobeSurface(session: SessionData?, onBack: () -> Unit) {
    val s = LocalStrings.current
    PuppetScreen("Wardrobe")

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            text = s.wardrobeTitle,
            style = MaterialTheme.typography.headlineSmall,
            color = CelestiaTheme.colors.textPrimary,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(24.dp))

        GlassCard(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            backgroundColor = glassSurfaceAlpha(0.7f),
        ) {
            if (session == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = s.wardrobeSignedOut,
                        style = MaterialTheme.typography.bodyMedium,
                        color = CelestiaTheme.colors.textSecondary,
                    )
                }
            } else {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        SkinHero(
                            session.playerName,
                            0,
                            Modifier.width(260.dp).height(420.dp),
                            interactive = true,
                            autoSpin = true,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = session.playerName,
                            style = MaterialTheme.typography.titleMedium,
                            color = CelestiaTheme.colors.textPrimary,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

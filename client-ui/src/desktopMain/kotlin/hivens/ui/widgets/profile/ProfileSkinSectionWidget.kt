package hivens.ui.widgets.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import hivens.widget.api.rememberProps
import hivens.widget.model.PropLabel
import hivens.widget.model.PropRange
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance
import kotlinx.serialization.Serializable

// Skin studio: a tall interactive 3D skin + upload/refresh controls. Currently
// dormant -- the Skin nav tab is disabled and the skin leads the Account tab
// instead (see ProfileAccountSectionWidget) -- but the widget stays registered
// and seeded in the `skin` slot so the editor palette and a future dedicated
// skin screen can re-home it without a layout migration. Reads session from
// LocalProfileContext.
@Serializable
data class ProfileSkinProps(
    @PropLabel("widget.profile.skin.section.previewHeight") @PropRange(200.0, 480.0) val previewHeight: Int = 360,
)

@Widget(id = "profile.skin.section", displayName = "widget.profile.skin.section", propsClass = ProfileSkinProps::class)
@Composable
fun ProfileSkinSectionWidget(instance: WidgetInstance) {
    val p = instance.rememberProps<ProfileSkinProps>()
    val ctx = LocalProfileContext.current
    val session = ctx.session ?: return

    var refreshKey by remember { mutableIntStateOf(0) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SkinHero(session.playerName, refreshKey, Modifier.fillMaxWidth().height(p.previewHeight.dp))
        SkinControls(session = session, onSkinChanged = { refreshKey++ })
    }
}

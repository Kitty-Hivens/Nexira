package hivens.ui.widgets.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.auth.AccountStore
import hivens.core.api.interfaces.ISettingsService
import hivens.core.data.SessionData
import hivens.ui.i18n.LocalStrings
import hivens.ui.puppet.PuppetClick
import hivens.ui.theme.LocalStyle
import hivens.ui.theme.NxTheme
import org.koin.compose.koinInject

/**
 * The face the shell should wear right now: the user's choice first, licence
 * priority behind it.
 *
 * Every site that recomputes the face after an account is added or removed goes
 * through here. Calling [AccountStore.primarySession] bare re-decides by
 * priority alone, which silently discards the choice the moment the user signs
 * anything in or out.
 */
internal fun AccountStore.faceSession(settingsService: ISettingsService): SessionData? =
    primarySession(settingsService.getSettings().preferredFaceProvider)

/**
 * Which signed-in account fronts the shell, when more than one is signed in.
 *
 * The store has always resolved this properly -- a named provider wins, and a
 * provider with no account signed in falls back to licence priority -- but
 * nothing ever named one, so the setting stayed null and the fallback was the
 * whole behaviour. This is the act of choosing.
 *
 * Hidden below two accounts: with one account the choice has a single outcome,
 * and with none there is nothing to front the shell with.
 *
 * Applied immediately rather than on the next start. The face is on screen
 * while the user picks, so a choice that visibly does nothing until a restart
 * reads as a control that does not work.
 */
@Composable
internal fun FacePicker(modifier: Modifier = Modifier) {
    val ctx = LocalProfileContext.current
    val credentials: AccountStore = koinInject()
    val settingsService: ISettingsService = koinInject()
    val s = LocalStrings.current

    // Keyed on the session so signing a provider in or out re-reads the list:
    // the picker's whole job is to name one of the accounts that exist now.
    val accounts = remember(ctx.session) { credentials.listAccounts() }
    if (accounts.size < 2) return

    var preferred by remember(ctx.session) {
        mutableStateOf(settingsService.getSettings().preferredFaceProvider)
    }

    fun choose(providerKey: String?) {
        settingsService.saveSettings(
            settingsService.getSettings().copy(preferredFaceProvider = providerKey),
        )
        preferred = providerKey
        // Re-resolve through the store rather than loading the named account
        // directly: naming a provider whose account has since gone must land on
        // the same fallback the shell uses at startup.
        credentials.faceSession(settingsService)?.let { ctx.onLogin(it) }
    }

    // Auto first: it is the default and the state a user returns to, so it reads
    // as the top of the list rather than as one more provider.
    val options = buildList {
        add(null to s.accountFaceAuto)
        ProfileCategory.entries
            .filter { category -> accounts.any { it.providerId == category.providerKey } }
            .forEach { add(it.providerKey to it.label(s)) }
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text     = s.accountFaceLabel,
            style    = MaterialTheme.typography.labelSmall,
            color    = NxTheme.colors.textSecondary,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
        options.forEach { (key, label) ->
            FaceOption(label = label, selected = key == preferred) { choose(key) }
            PuppetClick("profile.face.${key ?: "auto"}") { choose(key) }
        }
    }
}

@Composable
private fun FaceOption(label: String, selected: Boolean, onClick: () -> Unit) {
    val style = LocalStyle.current
    Text(
        text       = label,
        style      = MaterialTheme.typography.bodySmall,
        color      = if (selected) NxTheme.colors.primary else NxTheme.colors.textSecondary,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        modifier   = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(style.cardCorner))
            .background(
                if (selected) NxTheme.colors.primary.copy(alpha = 0.12f)
                else NxTheme.colors.background.copy(alpha = 0f),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

package hivens.ui.widgets.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.accounts.api.AccountKeys
import hivens.accounts.api.FaceChoice
import hivens.accounts.api.SessionState
import hivens.accounts.api.SessionView
import hivens.ui.i18n.nexiraStrings
import hivens.ui.press.clickable
import hivens.ui.puppet.PuppetClick
import hivens.widget.api.rememberScopedCommand
import hivens.widget.api.rememberScopedSource
import hivens.widget.api.valueOrNull
import hivens.ui.theme.NxInk
import hivens.ui.theme.NxColor

/** The session the composition is signed in as, null when nobody is or nothing says. */
@Composable
internal fun rememberSession(): SessionView? =
    (rememberScopedSource(AccountKeys.Session).valueOrNull() as? SessionState.SignedIn)?.session

/**
 * Which signed-in account fronts the shell, when more than one is signed in.
 *
 * The accounts module resolves this properly (a named provider wins, and a provider
 * with no account signed in falls back to licence priority), and this is the act of
 * choosing.
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
    // The list the store holds now, which every sign-in and sign-out reads again: the
    // picker's whole job is to name one of the accounts that exist, and offering one
    // that had just been removed wrote the preference for it.
    // Missing and not read yet alike leave nothing to choose from.
    val accounts = rememberScopedSource(AccountKeys.Accounts).valueOrNull() ?: return
    val face =rememberScopedSource(AccountKeys.Face).valueOrNull() ?: return
    val setFace = rememberScopedCommand(AccountKeys.SetFace) ?: return
    val s = nexiraStrings()
    if (accounts.size < 2) return

    // The module re-resolves through the store rather than loading the named account
    // directly: naming a provider whose account has since gone must land on the same
    // fallback the shell uses at startup.
    fun choose(providerKey: String?) = setFace(FaceChoice(providerKey))

    // Auto first: it is the default and the state a user returns to, so it reads
    // as the top of the list rather than as one more provider.
    val options = buildList {
        add(null to s.text("accountFaceAuto"))
        ProfileCategory.entries
            .filter { category -> accounts.any { it.providerKey == category.providerKey } }
            .forEach { add(it.providerKey to it.label(s)) }
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text     = s.text("accountFaceLabel"),
            style    = MaterialTheme.typography.labelSmall,
            color    = NxInk.quiet,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
        options.forEach { (key, label) ->
            FaceOption(label = label, selected = key == face.providerKey) { choose(key) }
            PuppetClick("profile.face.${key ?: "auto"}") { choose(key) }
        }
    }
}

@Composable
private fun FaceOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text       = label,
        style      = MaterialTheme.typography.bodySmall,
        color      = if (selected) NxColor.lead() else NxInk.quiet,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        modifier   = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(
                if (selected) NxColor.lead().copy(alpha = 0.12f)
                else NxColor.page.copy(alpha = 0f),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

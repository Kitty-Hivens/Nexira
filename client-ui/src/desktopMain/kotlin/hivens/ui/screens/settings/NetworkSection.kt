package hivens.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.core.diag.ActionRing
import hivens.launcher.network.NetworkState
import hivens.ui.nx.NxSwitch
import hivens.ui.i18n.LocalStrings
import hivens.ui.puppet.PuppetClick
import hivens.ui.puppet.PuppetToggle
import hivens.ui.theme.NxTheme
import hivens.ui.theme.LocalStyle
import kotlinx.coroutines.delay
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.time.Duration.Companion.milliseconds

/**
 * Network controls -- SSL bypass entries (live list with revoke), and
 * the force-proxy toggle. Single section grouping for "things that
 * affect how Nexira talks to the network" so users have one place to
 * look when networking misbehaves.
 */
@Composable
internal fun NetworkSection(
    form: SettingsFormState,
    save: () -> Unit,
) {
    val s = LocalStrings.current
    val style = LocalStyle.current

    SettingsSectionTitle(s.settingsSectionNetwork)

    // Live snapshot -- re-reads every 1s. Sufficient for a settings
    // screen (no rapid-fire updates expected). Avoids setting up a
    // Flow purely for this single read site.
    val bypasses = produceState(initialValue = NetworkState.listBypasses()) {
        while (true) {
            value = NetworkState.listBypasses()
            delay(1_000.milliseconds)
        }
    }.value
    val dateFormatter = DateTimeFormatter
        .ofLocalizedDateTime(FormatStyle.MEDIUM)
        .withZone(java.time.ZoneId.systemDefault())

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(style.cardCorner))
            .background(settingsRowBackground())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text       = s.sslBypassListTitle,
            color      = NxTheme.colors.textPrimary,
            fontWeight = FontWeight.Bold,
        )
        if (bypasses.isEmpty()) {
            Text(
                text  = s.sslBypassNoEntries,
                style = MaterialTheme.typography.bodySmall,
                color = NxTheme.colors.textSecondary,
            )
        } else {
            bypasses.forEach { entry ->
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text       = entry.host,
                            color      = NxTheme.colors.textPrimary,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text  = s.sslBypassExpiresAt(
                                dateFormatter.format(java.time.Instant.parse(entry.expiresAt)),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = NxTheme.colors.textSecondary,
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            ActionRing.record(
                                "SSL bypass revoked by user from Settings: ${entry.host}",
                            )
                            NetworkState.revokeBypass(entry.host)
                        },
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(s.sslBypassRevoke, color = NxTheme.colors.textSecondary)
                    }
                    // Puppet: per-host revoke. Driver picks the host by its
                    // actual hostname string.
                    PuppetClick("settings.sslBypass.revoke.${entry.host}") {
                        ActionRing.record(
                            "SSL bypass revoked by puppet driver: ${entry.host}",
                        )
                        NetworkState.revokeBypass(entry.host)
                    }
                }
            }
        }

        // ── Force proxy mode ──────────────────────────────────────────
        Spacer(Modifier.height(12.dp))
        Row(
            modifier              = Modifier.fillMaxWidth(),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text(
                    text       = s.settingsForceProxyTitle,
                    color      = NxTheme.colors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text  = s.settingsForceProxyDesc,
                    style = MaterialTheme.typography.bodySmall,
                    color = NxTheme.colors.textSecondary,
                )
            }
            NxSwitch(
                checked         = form.forceProxyMode,
                onCheckedChange = { form.forceProxyMode = it; save() },
            )
        }
        PuppetToggle("settings.forceProxyMode", form.forceProxyMode) { form.forceProxyMode = it; save() }
    }
}

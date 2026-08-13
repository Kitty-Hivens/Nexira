package hivens.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.core.diag.ActionRing
import hivens.core.security.SslBypassStore
import hivens.ui.i18n.LocalStrings
import hivens.ui.nx.NxButton
import hivens.ui.nx.NxButtonStyle
import hivens.ui.nx.NxSection
import hivens.ui.puppet.PuppetClick
import hivens.ui.theme.NxTheme
import org.koin.compose.koinInject
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Network controls -- the SSL-bypass entries, as a live list with revoke. One
 * section for "things that affect how Nexira talks to the network" so users have
 * one place to look when networking misbehaves.
 */
@Composable
internal fun NetworkSection() {
    val s = LocalStrings.current
    val bypassStore: SslBypassStore = koinInject()

    // The store publishes its grants, so the list follows a revoke made from
    // anywhere -- this section used to re-read a snapshot once a second, which
    // is a poll for state that changes on the scale of days.
    val bypasses by bypassStore.bypasses.collectAsState()
    val dateFormatter = DateTimeFormatter
        .ofLocalizedDateTime(FormatStyle.MEDIUM)
        .withZone(java.time.ZoneId.systemDefault())

    NxSection(s.settingsSectionNetwork) {
        Text(s.sslBypassListTitle, color = NxTheme.colors.textPrimary, fontWeight = FontWeight.Bold)
        if (bypasses.isEmpty()) {
            Text(s.sslBypassNoEntries, style = MaterialTheme.typography.bodySmall, color = NxTheme.colors.textSecondary)
        } else {
            bypasses.forEach { entry ->
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(entry.host, color = NxTheme.colors.textPrimary, fontWeight = FontWeight.SemiBold)
                        Text(
                            text  = s.sslBypassExpiresAt(dateFormatter.format(java.time.Instant.parse(entry.expiresAt))),
                            style = MaterialTheme.typography.bodySmall,
                            color = NxTheme.colors.textSecondary,
                        )
                    }
                    NxButton(
                        label   = s.sslBypassRevoke,
                        onClick = {
                            ActionRing.record("SSL bypass revoked by user from Settings: ${entry.host}")
                            bypassStore.revoke(entry.host)
                        },
                        style   = NxButtonStyle.Secondary,
                        compact = true,
                    )
                    PuppetClick("settings.sslBypass.revoke.${entry.host}") {
                        ActionRing.record("SSL bypass revoked by puppet driver: ${entry.host}")
                        bypassStore.revoke(entry.host)
                    }
                }
            }
        }
    }
}

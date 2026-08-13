package hivens.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import hivens.core.diag.ActionRing
import hivens.launcher.network.CertificateTrustGate
import hivens.ui.i18n.LocalStrings
import hivens.ui.puppet.PuppetClick
import hivens.ui.theme.NxTheme
import org.koin.compose.koinInject
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Answers a read that was refused by a certificate.
 *
 * The decision is the user's and it is the same one the login form used to own: trust
 * this host for an hour, for a month, or until they say otherwise. What changed is
 * who may ask -- anything that reads the host now can, so the server roster and the
 * news are readable without signing in, which is what they never needed in the first
 * place.
 *
 * MUST be composed inside [hivens.ui.theme.NxTheme]: the prompt is a `Dialog`, which
 * on desktop gets its own composition, and one raised from outside the theme finds no
 * `LocalNxColors` and takes the shell down with it.
 */
@Composable
fun CertificatePromptHost() {
    val gate: CertificateTrustGate = koinInject()
    val s = LocalStrings.current
    val pending by gate.pending.collectAsState()

    fun accept(unit: ChronoUnit, amount: Long, label: String) {
        ActionRing.record("SSL bypass accepted by user -- granted: $label")
        gate.accept(Instant.now().plus(amount, unit))
    }

    // Automation reaches the decision without the dialog: a scenario that cannot get
    // past an untrusted certificate cannot check anything behind it.
    PuppetClick("certificate.trust.always", enabled = pending != null) {
        accept(ChronoUnit.DAYS, ALWAYS_DAYS, "always (100y)")
    }
    PuppetClick("certificate.trust.dismiss", enabled = pending != null) { gate.dismiss() }

    val request = pending ?: return
    AlertDialog(
        onDismissRequest = { gate.dismiss() },
        title = { Text(s.sslWarningTitle, color = NxTheme.colors.textPrimary, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(s.sslWarningBody, style = MaterialTheme.typography.bodyMedium, color = NxTheme.colors.textSecondary)
                Text(request.host, style = MaterialTheme.typography.labelMedium, color = NxTheme.colors.textPrimary)
                Text(s.sslWarningTrustPrompt, style = MaterialTheme.typography.bodySmall, color = NxTheme.colors.textSecondary)
            }
        },
        confirmButton = {
            // The three durations read as one row of equal choices, the way they do in
            // the login form: the risk is the same whichever is picked, only its length
            // differs.
            val colors = ButtonDefaults.buttonColors(containerColor = NxTheme.colors.warnAccent)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { accept(ChronoUnit.HOURS, 1, "1 hour") },
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.small,
                    colors = colors,
                ) { Text(s.sslWarningTrustHour, color = Color.Black) }
                Button(
                    onClick = { accept(ChronoUnit.DAYS, 30, "30 days") },
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.small,
                    colors = colors,
                ) { Text(s.sslWarningTrust30Days, color = Color.Black) }
                Button(
                    onClick = { accept(ChronoUnit.DAYS, ALWAYS_DAYS, "always (100y)") },
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.small,
                    colors = colors,
                ) { Text(s.sslWarningTrustAlways, color = Color.Black) }
            }
        },
        dismissButton = {
            TextButton(onClick = { gate.dismiss() }) {
                Text(s.sslWarningCancel, color = NxTheme.colors.textSecondary)
            }
        },
        containerColor = NxTheme.colors.surface,
    )
}

/**
 * A hundred years for "always": far enough that nobody outlives it, near enough that
 * the stored instant still formats as ISO-8601, which `Instant.MAX` does not.
 */
private const val ALWAYS_DAYS = 36500L

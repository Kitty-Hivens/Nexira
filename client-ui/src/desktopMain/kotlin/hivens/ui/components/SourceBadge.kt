package hivens.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import hivens.core.api.dto.smrt.SmrtSource
import hivens.core.data.PackOrigin
import hivens.ui.nx.NxSourceBadge
import hivens.ui.theme.NxTheme
import hivens.ui.theme.origin
import hivens.ui.theme.source

// Provenance pill wrappers: map the two domain types (a pack's [PackOrigin]
// and a mirror entry's [SmrtSource]) onto the neutral [NxSourceBadge], so the
// label table and brand colour live in one place instead of three.

@Composable
fun SourceBadge(origin: PackOrigin, modifier: Modifier = Modifier) {
    val label = when (origin) {
        PackOrigin.Smartycraft -> "SmartyCraft"
        PackOrigin.Mirror      -> "Mirror"
        PackOrigin.Modrinth    -> "Modrinth"
        PackOrigin.Local       -> "Local"
        PackOrigin.Unknown     -> "Other"
    }
    NxSourceBadge(label = label, color = NxTheme.colors.origin(origin), modifier = modifier)
}

@Composable
fun SourceBadge(source: SmrtSource, modifier: Modifier = Modifier) {
    val label = when (source) {
        is SmrtSource.Modrinth   -> "Modrinth"
        is SmrtSource.SmrtCache  -> "Mirror"
        is SmrtSource.SmrtStatic -> "Static"
        is SmrtSource.Unknown    -> "Unknown"
    }
    NxSourceBadge(label = label, color = NxTheme.colors.source(source), modifier = modifier)
}

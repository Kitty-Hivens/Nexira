package hivens.ui.widgets.themepicker

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.nx.NxButton
import hivens.ui.nx.NxButtonStyle
import hivens.ui.nx.NxSwitch
import hivens.ui.surface.NxCard
import hivens.ui.surface.NxSurface
import hivens.ui.surface.SurfaceKind
import hivens.ui.theme.NxColor
import hivens.ui.theme.NxInk
import hivens.ui.theme.NxTheme
import hivens.ui.theme.Theme
import hivens.widget.model.Widget

// Every theme, each drawn by itself. A card is not a list of the theme's colours: it
// is a small piece of interface rendered inside that theme, by the same code the
// launcher uses, so what a card shows is what choosing it will give and the two
// cannot disagree. The old cards painted the hex values a theme declared, which the
// launcher then did not apply.
//
// The ceiling is load-bearing: a LazyVerticalGrid cannot be measured against an
// unbounded height, and the editor can put this in a slot that has none.
@Widget(
    id = "theme.picker.grid",
    displayName = "widget.theme.picker.grid",
    drawsOwnSurface = true,
    minWidth = 260, minHeight = 220,
    maxWidth = 960, maxHeight = 1600,
)
@Composable
fun ThemePickerGridWidget() {
    val ctx = LocalThemePickerContext.current
    val selected by ctx.selected

    NxCard(modifier = Modifier.fillMaxSize(), shape = MaterialTheme.shapes.large) {
        LazyVerticalGrid(
            columns               = GridCells.Fixed(2),
            contentPadding        = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement   = Arrangement.spacedBy(16.dp),
        ) {
            items(ctx.themes, key = { it.id }) { theme ->
                ThemeCard(
                    theme      = theme,
                    dark       = ctx.isDark,
                    isSelected = theme.id == selected.id,
                    onClick    = { ctx.selected.value = theme },
                )
            }
        }
    }
}

@Composable
private fun ThemeCard(
    theme: Theme,
    dark: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val s = LocalStrings.current
    val scale by animateFloatAsState(
        targetValue   = if (isSelected) 1.03f else 1f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 300f),
    )
    val shape = MaterialTheme.shapes.medium
    // The frame is the launcher's own, outside the theme being shown: it marks the
    // choice in the colours the person is looking at the picker in.
    val frame = if (isSelected) NxColor.lead() else NxInk.line
    val face = MaterialTheme.typography.bodyMedium.fontFamily
    val darkOnly = !dark && !theme.hasLight

    Box(
        modifier = Modifier
            .aspectRatio(1.2f)
            .scale(scale)
            .clip(shape)
            .border(if (isSelected) 2.dp else 1.dp, frame, shape),
    ) {
        NxTheme(theme, dark = dark, uiFamily = face) {
            Column(
                modifier            = Modifier.fillMaxSize().background(NxColor.page).padding(12.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Row {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text       = theme.label(),
                            style      = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color      = NxInk.main,
                            maxLines   = 1,
                            overflow   = TextOverflow.Ellipsis,
                        )
                        if (darkOnly) {
                            Text(s.themePickerDarkOnly, style = MaterialTheme.typography.labelSmall, color = NxInk.quiet)
                        }
                    }
                    if (isSelected) {
                        Symbol(NxIcon.Check, contentDescription = s.themePickerSelected, tint = NxColor.lead(), modifier = Modifier.size(18.dp))
                    }
                }
                NxSurface(SurfaceKind.Panel, Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            theme.scheme(dark).colors.take(6).forEach { colour ->
                                Box(Modifier.size(14.dp).clip(CircleShape).background(colour))
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            NxButton(label = s.themePickerBtnSample, onClick = {}, style = NxButtonStyle.Primary, compact = true)
                            NxSwitch(checked = true, onCheckedChange = null)
                        }
                    }
                }
            }
        }
        // Over the sample, so a click on the sample button picks the theme rather
        // than pressing a button that does nothing.
        Box(Modifier.matchParentSize().clickable(onClick = onClick))
    }
}

package hivens.ui.theme

import androidx.compose.foundation.LocalIndication
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import hivens.ui.customization.LocalCustomization
import hivens.ui.nx.ThemeStateLayer

// --- COLOR PALETTES ---
data class NxColors(
    val primary: Color,
    val primaryVariant: Color,
    val secondary: Color,
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val error: Color,
    val onPrimary: Color,
    val onSecondary: Color,
    val onBackground: Color,
    val onSurface: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val glassBackground: Color,
    val glassAlpha: Float,
    val success: Color,
    val outline: Color,
    // Severity accents for notifications. `success` already doubles as
    // Severity.Success; the other three are new tokens so each accent can
    // drift per palette without dragging unrelated UI.
    val progressAccent: Color,
    val warnAccent: Color,
    val criticalAccent: Color,
    // Source/origin brand colors -- themeable so customization reaches the most
    // visible cards. The per-origin gradient + avatar shades are derived from
    // these in code (theme/BrandColors.kt), not stored as separate literals.
    val originSmartycraft: Color,
    val originMirror: Color,
    val originModrinth: Color,
    val originLocal: Color,
    // --- Material-3 tonal expansion ---
    // A third accent plus muted "container" fills and layered surface tiers, so UI
    // reads as more than primary-or-glass. Containers + [tertiary] are DERIVED per
    // theme from the resolved accents in the builder (so presets stay one-line and
    // any accent override propagates); the surface tiers come from the base palette.
    val tertiary: Color,
    val onTertiary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val tertiaryContainer: Color,
    val onTertiaryContainer: Color,
    val surfaceContainerLow: Color,
    val surfaceContainer: Color,
    val surfaceContainerHigh: Color,
    // Decorative hash-assigned ramp for per-server / per-mod avatars: one themed
    // list the hash indexes into (unifies the old SERVER_PALETTES + AVATAR_PALETTE).
    // Not a semantic token and not customization-overridable -- just stable,
    // in-theme variety; the dark and light presets carry their own ramp.
    val decorativeRamp: List<Color>,
)

internal val DarkColorPalette = NxColors(
    primary = Color(0xFFBB86FC), // Soft purple
    primaryVariant = Color(0xFF3700B3),
    secondary = Color(0xFF03DAC6),
    background = Color(0xFF121212), // Almost black, but not #000
    surface = Color(0xFF1E1E1E), // A little lighter for cards
    surfaceVariant = Color(0xFF2C2C2C),
    error = Color(0xFFCF6679),
    onPrimary = Color.Black,
    onSecondary = Color.Black,
    onBackground = Color.White,
    onSurface = Color.White,
    textPrimary = Color(0xFFEEEEEE),
    textSecondary = Color(0xFFB0B0B0),
    glassBackground = Color(0xFF000000), // Base for glass
    glassAlpha = 0.60f, // Glass transparency (dark)
    success = Color(0xFF4CAF50),
    outline = Color(0xFF444444),
    progressAccent = Color(0xFF6A84FF),
    warnAccent     = Color(0xFFE0B341),
    criticalAccent = Color(0xFFD8484A),
    originSmartycraft = Color(0xFF8B5CF6), // violet
    originMirror      = Color(0xFF3B82F6), // blue
    originModrinth    = Color(0xFF22C55E), // green
    originLocal       = Color(0xFF9CA3AF), // grey
    // Tonal expansion -- placeholders; the builder re-derives containers + tertiary
    // from the live accents. Surface tiers below are the real dark-ground values.
    tertiary = Color(0xFFF0A66B),
    onTertiary = Color.Black,
    primaryContainer = Color(0xFF332A4A),
    onPrimaryContainer = Color(0xFFE6DBFF),
    secondaryContainer = Color(0xFF14352F),
    onSecondaryContainer = Color(0xFFB9F5EA),
    tertiaryContainer = Color(0xFF3A2E20),
    onTertiaryContainer = Color(0xFFFFE2C7),
    surfaceContainerLow  = Color(0xFF181818),
    surfaceContainer     = Color(0xFF222222),
    surfaceContainerHigh = Color(0xFF2A2A2A),
    decorativeRamp = listOf(
        Color(0xFF7C3AED), Color(0xFF0EA5E9), Color(0xFF10B981), Color(0xFFF59E0B),
        Color(0xFFEC4899), Color(0xFF14B8A6), Color(0xFFF97316), Color(0xFF6366F1),
    ),
)

internal val LightColorPalette = NxColors(
    primary = Color(0xFF5E68C0),       // Soft indigo (instead of harsh purple)
    primaryVariant = Color(0xFF3F51B5),
    secondary = Color(0xFF26A69A),     // Calm teal
    // The page is the extreme of the ladder, and every plane descends from it --
    // the mirror of the dark theme, where the page is the darkest thing on screen
    // and planes rise away from it. It used to sit in the MIDDLE: `surface` was
    // white and therefore lighter than the page, `surfaceContainer` darker, so
    // depth had no direction and a card cleared the page by 2.84 L* in one
    // direction while a panel cleared it by 2.84 in the other. See the tiers below.
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFECEEF2),
    surfaceVariant = Color(0xFFE8EAF0),
    error = Color(0xFFD32F2F),
    onPrimary = Color.White,
    onSecondary = Color.Black,
    onBackground = Color(0xFF212121),
    onSurface = Color(0xFF212121),
    textPrimary = Color(0xFF263238),   // Dark blue-gray (softer than black)
    // Dark enough to be read on every plane of the light ladder: 6.4 against a
    // white Base, 5.1 against Floating, the darkest of them. The tone it replaced
    // was picked to sit quietly and did -- 3.35 and 2.66, under the body floor
    // everywhere and under the large-text one on half the ladder. Same hue and
    // saturation, lower lightness.
    textSecondary = Color(0xFF4F626B),
    glassBackground = Color(0xFFFFFFFF),
    glassAlpha = 0.65f,                // Slightly more opacity for readability
    // Darker than the dark theme's #4CAF50, like every other severity accent
    // here. Lightening it for a light ground put it at 1.9:1 against the
    // container plane -- under even the large-text floor -- while the same token
    // measured 5.2:1 on dark. Weighted to match criticalAccent's 6.1 / 5.2.
    success = Color(0xFF256B2B),
    outline = Color(0xFFCCCCCC),
    progressAccent = Color(0xFF3C5BD9),
    // 2.9:1 against surfaceContainerHigh before, i.e. below the large-text floor
    // on the plane the meta chips and callouts actually sit on. Weighted to match
    // progressAccent's 5.3 / 4.5.
    warnAccent     = Color(0xFF8A5E08),
    criticalAccent = Color(0xFFB3262A),
    originSmartycraft = Color(0xFF6D28D9), // deeper violet for light bg
    originMirror      = Color(0xFF2563EB), // deeper blue
    originModrinth    = Color(0xFF16A34A), // deeper green
    originLocal       = Color(0xFF64748B), // slate
    // Tonal expansion -- placeholders re-derived by the builder; surface tiers real.
    tertiary = Color(0xFFB5651D),
    onTertiary = Color.White,
    primaryContainer = Color(0xFFE3E0F7),
    onPrimaryContainer = Color(0xFF1A1740),
    secondaryContainer = Color(0xFFD6F0EC),
    onSecondaryContainer = Color(0xFF0A3B33),
    tertiaryContainer = Color(0xFFF6E2CE),
    onTertiaryContainer = Color(0xFF4A2A09),
    // L* 100 / 96.99 / 94.05 / 91.30 / 87.82 counting `background` and `surface`
    // above. Spread over 12.2 L* where it used to cover 9.1 with the page in the
    // middle of it, so the pairs that actually sit next to each other clear the
    // threshold at which two flat fields read as separate planes: page to a widget
    // is 5.95 (was 2.84), page to a card is 8.70 (was 3.76).
    //
    // The floor is `textSecondary`, which needs 4.5:1 against any plane it is read
    // on and therefore caps the deepest rung at L* 86.5. The bottom rung sits 1.32
    // above that, so this ladder cannot go deeper without moving the text with it.
    surfaceContainerLow  = Color(0xFFF6F6FA),
    surfaceContainer     = Color(0xFFE4E6EC),
    surfaceContainerHigh = Color(0xFFDADCE4),
    decorativeRamp = listOf(
        Color(0xFF6D28D9), Color(0xFF0284C7), Color(0xFF059669), Color(0xFFD97706),
        Color(0xFFDB2777), Color(0xFF0D9488), Color(0xFFEA580C), Color(0xFF4F46E5),
    ),
)

val LocalNxColors = staticCompositionLocalOf<NxColors> {
    error("No NxColors provided")
}

// --- THEME WITH ANIMATION AND SUPPORT FOR CUSTOM THEMES ---

/**
 * The palette a theme starts from, before presets, accent overrides and the tonal
 * expansion land on top.
 *
 * There is one per side and no third source. Wallpaper seeding used to substitute a
 * generated palette here whenever a background image was set, which meant the
 * surface ladder the app was designed against only applied while no wallpaper was
 * -- two different light themes depending on a setting, and the difference read as
 * a bug rather than a feature. The tinted-from-the-wallpaper look it produced is
 * not the one this launcher wants.
 */
internal fun resolveBasePalette(dark: Boolean): NxColors =
    if (dark) DarkColorPalette else LightColorPalette

@Composable
fun NxTheme(
    useDarkTheme: Boolean = true,
    customTheme: CustomTheme? = null,
    style: StyleSpec = CelestiaStyle,
    content: @Composable () -> Unit
) {
    val customization = LocalCustomization.current

    val baseColors = resolveBasePalette(useDarkTheme)

    val themedColors = if (customTheme != null) {
        baseColors.copy(
            primary        = CustomTheme.parseHexColor(customTheme.primary),
            primaryVariant = CustomTheme.parseHexColor(customTheme.primary).copy(alpha = 0.8f),
            secondary      = CustomTheme.parseHexColor(customTheme.secondary),
            success        = CustomTheme.parseHexColor(customTheme.success),
            error          = CustomTheme.parseHexColor(customTheme.error),
        )
    } else {
        baseColors
    }

    // The accent override lands on top of the active theme, re-seeding the
    // primary accent.
    val targetColors = run {
        var c = themedColors
        customization.accentOverride?.let { hex ->
            parseHexColorOrNull(hex)?.let { col ->
                c = c.copy(primary = col, primaryVariant = col.copy(alpha = 0.8f))
            }
        }
        // Derive the tonal expansion from the resolved accents (after theme +
        // accent + experimental overrides), so containers and the third accent
        // always track the live primary/secondary. Surface tiers stay from base.
        val tert = deriveTertiary(c.primary, c.secondary)
        c = c.copy(
            tertiary             = tert,
            onTertiary           = contentOn(tert),
            primaryContainer     = tonalContainer(c.primary, c.surface, useDarkTheme),
            onPrimaryContainer   = onTonalContainer(c.primary, useDarkTheme),
            secondaryContainer   = tonalContainer(c.secondary, c.surface, useDarkTheme),
            onSecondaryContainer = onTonalContainer(c.secondary, useDarkTheme),
            tertiaryContainer    = tonalContainer(tert, c.surface, useDarkTheme),
            onTertiaryContainer  = onTonalContainer(tert, useDarkTheme),
        )
        c
    }

    // Palette swaps in ONE recomposition -- deliberately not animated per token.
    // Feeding per-frame animateColorAsState values into LocalNxColors (a
    // staticCompositionLocalOf) re-invalidated every reader of it ~30 times per
    // switch, recomposing + redrawing the whole tree on the single UI thread --
    // that was the freeze on toggle. The light<->dark transition is carried by
    // the circular reveal instead (theme/ThemeReveal.kt); Brut had no color
    // animation to begin with.
    val activePalette = targetColors

    // M3 ColorScheme
    val colorScheme = if (useDarkTheme) {
        darkColorScheme(
            primary = activePalette.primary,
            secondary = activePalette.secondary,
            background = activePalette.background,
            surface = activePalette.surface,
            surfaceVariant = activePalette.surfaceVariant,
            error = activePalette.error,
            onPrimary = activePalette.onPrimary,
            onSecondary = activePalette.onSecondary,
            onBackground = activePalette.onBackground,
            onSurface = activePalette.onSurface,
            outline = activePalette.outline
        )
    } else {
        lightColorScheme(
            primary = activePalette.primary,
            secondary = activePalette.secondary,
            background = activePalette.background,
            surface = activePalette.surface,
            surfaceVariant = activePalette.surfaceVariant,
            error = activePalette.error,
            onPrimary = activePalette.onPrimary,
            onSecondary = activePalette.onSecondary,
            onBackground = activePalette.onBackground,
            onSurface = activePalette.onSurface,
            outline = activePalette.outline
        )
    }

    // Provide LocalStyle alongside the palette so child composables can
    // read StyleSpec tokens without a separate CompositionLocalProvider
    // chain at every entry point.
    CompositionLocalProvider(
        LocalNxColors provides activePalette,
        LocalStyle          provides style,
        LocalMonoFamily     provides nexiraMonoFamily(),
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            shapes      = style.toMaterialShapes(),
            typography  = nexiraTypography(),
        ) {
            // Inside MaterialTheme's lambda on purpose: MaterialTheme provides its
            // default ripple into LocalIndication, so the app-wide state layer must
            // be provided beneath it to win. Placed outside, this is a silent no-op.
            // LocalContentColor is anchored to the palette because with no root M3
            // Surface it defaults to Black, leaving the ambient state-layer wash
            // near-invisible on dark; M3 components still override it inside.
            CompositionLocalProvider(
                LocalIndication provides ThemeStateLayer,
                LocalContentColor provides activePalette.textPrimary,
            ) {
                content()
            }
        }
    }
}

// Easy access via NxTheme.colors
object NxTheme {
    val colors: NxColors
        @Composable
        get() = LocalNxColors.current
}

private fun parseHexColorOrNull(hex: String): Color? = try {
    val clean = hex.trim().removePrefix("#")
    val full = if (clean.length == 6) "FF$clean" else clean
    Color(full.toLong(16))
} catch (_: Exception) { null }

// --- Tonal derivation for the Material-3 expansion ---
// container = a muted fill of the accent (dark: pulled toward the surface; light:
// toward white). onContainer = a readable tone of the accent for content on that
// fill. [deriveTertiary] is a third hue between the two accents, so it always
// stays inside the active theme's family instead of clashing with it.
private fun tonalContainer(accent: Color, surface: Color, dark: Boolean): Color =
    if (dark) lerp(accent, surface, 0.70f) else lerp(accent, Color.White, 0.82f)

private fun onTonalContainer(accent: Color, dark: Boolean): Color =
    if (dark) lerp(accent, Color.White, 0.74f) else lerp(accent, Color.Black, 0.58f)

private fun contentOn(accent: Color): Color =
    if (accent.luminance() > 0.5f) Color.Black else Color.White

private fun deriveTertiary(primary: Color, secondary: Color): Color = lerp(secondary, primary, 0.5f)

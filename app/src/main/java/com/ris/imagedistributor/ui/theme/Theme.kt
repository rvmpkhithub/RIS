package com.ris.imagedistributor.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

// Tokens from DESIGN.md#colors frontmatter — do not hand-tune, edit the spec first.
// Warm "paper/ink/WhatsApp teal-green" palette, carried over from the project's brainstorm
// keepsake (brainstorm.html) at the operator's request — see DESIGN.md#Brand & Style.
private val Primary = Color(0xFF128C7E)
private val OnPrimary = Color(0xFFFFFFFF)
private val PrimaryDark = Color(0xFF25D366)
private val OnPrimaryDark = Color(0xFF0E1B18)
private val Surface = Color(0xFFFBF6EC)
private val SurfaceDark = Color(0xFF1C1712)
private val OnSurface = Color(0xFF2B2620)
private val OnSurfaceDark = Color(0xFFF0E9D8)
private val SurfaceVariant = Color(0xFFEFE6D2)
private val SurfaceVariantDark = Color(0xFF2B2620)
private val Outline = Color(0xFFCBB98A)
private val OutlineDark = Color(0xFF5C5346)
private val Error = Color(0xFFC2452C)
private val OnError = Color(0xFFFFFFFF)

/** Structural card-frame color, echoing the keepsake's `.meta-card`/`.plaque` borders. Never used for anything tappable. */
val GoldBorder = Color(0xFFC9A13B)

/**
 * DESIGN.md's `success-muted` token — a single fixed value with no `-dark` variant, explicitly
 * "quiet" and deliberately decoupled from whatever `colorScheme.primary` resolves to per theme
 * (`primary`/`primary-dark` differ; this doesn't). Used once, for the Dashboard's "Sent" text tint.
 */
val SuccessMuted = Color(0xFF128C7E)

// Tonal surface steps (Material 3 uses these for NavigationBar, TopAppBar, elevated cards,
// etc. by default) — derived from the same warm palette so every stock component stays
// consistent instead of falling back to Material 3's baseline cool-neutral defaults.
private val SurfaceContainerLowest = Color(0xFFFFFDF7)
private val SurfaceContainerLow = Color(0xFFF6F0E4)
private val SurfaceContainer = Color(0xFFEFE6D2)
private val SurfaceContainerHigh = Color(0xFFE8DCC4)
private val SurfaceContainerHighest = Color(0xFFE1D2B6)

private val SurfaceContainerLowestDark = Color(0xFF141110)
private val SurfaceContainerLowDark = Color(0xFF221D17)
private val SurfaceContainerDark = Color(0xFF2B2620)
private val SurfaceContainerHighDark = Color(0xFF342C24)
private val SurfaceContainerHighestDark = Color(0xFF3D3329)

// Selected-state indicators (e.g. NavigationBarItem's pill) default to Material 3's cool
// secondaryContainer — replaced with a pale tint of the one accent color instead of a second hue.
private val SecondaryContainer = Color(0xFFD6E9E5)
private val SecondaryContainerDark = Color(0xFF1F3B36)

private val LightColors = lightColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    background = Surface,
    onBackground = OnSurface,
    surface = Surface,
    onSurface = OnSurface,
    surfaceVariant = SurfaceVariant,
    outline = Outline,
    error = Error,
    onError = OnError,
    surfaceContainerLowest = SurfaceContainerLowest,
    surfaceContainerLow = SurfaceContainerLow,
    surfaceContainer = SurfaceContainer,
    surfaceContainerHigh = SurfaceContainerHigh,
    surfaceContainerHighest = SurfaceContainerHighest,
    secondaryContainer = SecondaryContainer,
    onSecondaryContainer = OnSurface,
)

private val DarkColors = darkColorScheme(
    primary = PrimaryDark,
    onPrimary = OnPrimaryDark,
    background = SurfaceDark,
    onBackground = OnSurfaceDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    outline = OutlineDark,
    error = Error,
    onError = OnError,
    surfaceContainerLowest = SurfaceContainerLowestDark,
    surfaceContainerLow = SurfaceContainerLowDark,
    surfaceContainer = SurfaceContainerDark,
    surfaceContainerHigh = SurfaceContainerHighDark,
    surfaceContainerHighest = SurfaceContainerHighestDark,
    secondaryContainer = SecondaryContainerDark,
    onSecondaryContainer = OnSurfaceDark,
)

// DESIGN.md#Typography — serif headlines only (screen titles), Roboto (Material 3 default)
// everywhere else. Both are system-resolved generic families, no bundled font files.
private val SerifHeadline = FontFamily.Serif

private val AppTypography = Typography().let { base ->
    base.copy(
        headlineSmall = base.headlineSmall.copy(fontFamily = SerifHeadline, fontWeight = FontWeight.Bold),
    )
}

/**
 * DESIGN.md#Elevation & Depth — subtle warm radial gradient background instead of a flat fill,
 * echoing the keepsake's hero gradient without competing with content. Use as the top-level
 * background brush; individual cards still sit on a solid surfaceContainer color on top of it.
 */
@Composable
fun appBackgroundBrush(): Brush {
    val (center, edge) = if (isSystemInDarkTheme()) {
        SurfaceContainerLowDark to SurfaceDark
    } else {
        SurfaceContainerLowest to Surface
    }
    return Brush.radialGradient(colors = listOf(center, edge))
}

@Composable
fun ImageDropTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, typography = AppTypography, content = content)
}

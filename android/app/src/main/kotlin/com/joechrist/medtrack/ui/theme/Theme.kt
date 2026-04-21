package com.joechrist.medtrack.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.joechrist.medtrack.R

// =============================================================================
// MedTrack – Material 3 Design System
// Aesthetic: Clinical precision meets warmth.
// Palette: Deep navy (#0F3460) primary · Teal (#1DB98A) accent · Warm off-white surface
// Typography: System default (can swap to a custom font via res/font)
// =============================================================================

// ── Colours ───────────────────────────────────────────────────────────────────

private val Navy900     = Color(0xFF0F3460)
private val Navy700     = Color(0xFF16508A)
private val Navy100     = Color(0xFFD6E4F7)
private val Teal500     = Color(0xFF1DB98A)
private val Teal200     = Color(0xFF9FE1CB)
private val Teal900     = Color(0xFF085041)
private val Coral500    = Color(0xFFE8593C)
private val Neutral50   = Color(0xFFF6F8FC)
private val Neutral100  = Color(0xFFECEFF5)
private val Neutral200  = Color(0xFFD4D8E4)
private val Neutral700  = Color(0xFF444466)
private val Neutral900  = Color(0xFF1A1A2E)
private val White       = Color(0xFFFFFFFF)
private val ErrorRed    = Color(0xFFD13030)

private val LightScheme = lightColorScheme(
    primary              = Navy900,
    onPrimary            = White,
    primaryContainer     = Navy100,
    onPrimaryContainer   = Navy900,
    secondary            = Teal500,
    onSecondary          = White,
    secondaryContainer   = Teal200,
    onSecondaryContainer = Teal900,
    tertiary             = Coral500,
    background           = Neutral50,
    onBackground         = Neutral900,
    surface              = White,
    onSurface            = Neutral900,
    surfaceVariant       = Neutral100,
    onSurfaceVariant     = Neutral700,
    outline              = Neutral200,
    error                = ErrorRed,
    onError              = White
)

private val DarkScheme = darkColorScheme(
    primary              = Navy100,
    onPrimary            = Navy900,
    primaryContainer     = Navy700,
    onPrimaryContainer   = Navy100,
    secondary            = Teal200,
    onSecondary          = Teal900,
    secondaryContainer   = Teal900,
    onSecondaryContainer = Teal200,
    background           = Neutral900,
    onBackground         = Neutral50,
    surface              = Color(0xFF1E2035),
    onSurface            = Neutral100,
    surfaceVariant       = Color(0xFF272944),
    onSurfaceVariant     = Neutral200,
    outline              = Neutral700,
    error                = Color(0xFFF08080),
    onError              = Color(0xFF600000)
)

// ── Typography ────────────────────────────────────────────────────────────────

val MedTrackTypography = Typography(
    displayLarge = TextStyle(
        fontWeight = FontWeight.W300,
        fontSize   = 57.sp,
        lineHeight = 64.sp,
        letterSpacing = (-0.25).sp
    ),
    headlineLarge = TextStyle(
        fontWeight = FontWeight.W600,
        fontSize   = 32.sp,
        lineHeight = 40.sp
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.W600,
        fontSize   = 24.sp,
        lineHeight = 32.sp
    ),
    headlineSmall = TextStyle(
        fontWeight = FontWeight.W500,
        fontSize   = 20.sp,
        lineHeight = 28.sp
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.W600,
        fontSize   = 18.sp,
        lineHeight = 24.sp
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.W500,
        fontSize   = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.1.sp
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.W400,
        fontSize   = 16.sp,
        lineHeight = 24.sp
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.W400,
        fontSize   = 14.sp,
        lineHeight = 20.sp
    ),
    bodySmall = TextStyle(
        fontWeight = FontWeight.W400,
        fontSize   = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.W600,
        fontSize   = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    )
)

// ── Shapes ────────────────────────────────────────────────────────────────────

val MedTrackShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
    small      = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    medium     = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    large      = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)
)

// ── Theme composable ──────────────────────────────────────────────────────────

@Composable
fun MedTrackTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography  = MedTrackTypography,
        shapes      = MedTrackShapes,
        content     = content
    )
}

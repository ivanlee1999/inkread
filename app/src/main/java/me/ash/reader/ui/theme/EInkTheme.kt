package me.ash.reader.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val EInkColorScheme = lightColorScheme(
    background = Color.White,
    surface = Color.White,
    surfaceVariant = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color.White,
    surfaceContainerHighest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainerLowest = Color.White,
    surfaceBright = Color.White,
    surfaceDim = Color.White,
    onBackground = Color.Black,
    onSurface = Color.Black,
    onSurfaceVariant = Color.Black,
    primary = Color.Black,
    onPrimary = Color.White,
    primaryContainer = Color.White,
    onPrimaryContainer = Color.Black,
    secondary = Color.Black,
    onSecondary = Color.White,
    secondaryContainer = Color.White,
    onSecondaryContainer = Color.Black,
    tertiary = Color.Black,
    onTertiary = Color.White,
    tertiaryContainer = Color.White,
    onTertiaryContainer = Color.Black,
    error = Color.Black,
    onError = Color.White,
    errorContainer = Color.White,
    onErrorContainer = Color.Black,
    outline = Color.Black,
    outlineVariant = Color.Black,
    inverseSurface = Color.Black,
    inverseOnSurface = Color.White,
    inversePrimary = Color.White,
    scrim = Color.Black,
)

val EInkTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.Serif,
        fontSize = 32.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 40.sp,
        color = Color.Black,
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.Serif,
        fontSize = 28.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 36.sp,
        color = Color.Black,
    ),
    displaySmall = TextStyle(
        fontFamily = FontFamily.Serif,
        fontSize = 24.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 32.sp,
        color = Color.Black,
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Serif,
        fontSize = 24.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 32.sp,
        color = Color.Black,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Serif,
        fontSize = 22.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 30.sp,
        color = Color.Black,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Serif,
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 28.sp,
        color = Color.Black,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Serif,
        fontSize = 24.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 32.sp,
        color = Color.Black,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Serif,
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 28.sp,
        color = Color.Black,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Serif,
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 26.sp,
        color = Color.Black,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Serif,
        fontSize = 18.sp,
        lineHeight = 28.sp,
        color = Color.Black,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Serif,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        color = Color.Black,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Serif,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        color = Color.Black,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Serif,
        fontSize = 16.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 24.sp,
        color = Color.Black,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Serif,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 20.sp,
        color = Color.Black,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Serif,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 16.sp,
        color = Color.Black,
    ),
)

/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Brand-semantic colors Material's ColorScheme has no slot for.
 * Nothing language: the accent is red; success/warning stay functional but muted.
 */
data class CvBrandColors(
    val success: Color,
    val warning: Color,
    val info: Color,
    val accent: Color,
    val highlight: Color,
)

val LocalCvBrand = staticCompositionLocalOf {
    CvBrandColors(success = Success, warning = Warning, info = InfoGray, accent = NothingRed, highlight = NothingRed)
}

private val DarkColors = darkColorScheme(
    primary = NothingRed,
    onPrimary = Color.White,
    primaryContainer = NothingRedDeep,
    onPrimaryContainer = NothingRedBright,
    secondary = Color(0xFFB3B3B3),
    onSecondary = Color(0xFF0A0A0A),
    secondaryContainer = NothingSurfaceHi,
    onSecondaryContainer = NothingWhite,
    tertiary = NothingGray,
    onTertiary = NothingWhite,
    tertiaryContainer = NothingSurfaceHi,
    onTertiaryContainer = NothingWhite,
    background = NothingBlack,
    onBackground = NothingWhite,
    surface = NothingSurface,
    onSurface = NothingWhite,
    surfaceVariant = NothingSurfaceHi,
    onSurfaceVariant = NothingGray,
    surfaceContainerLowest = NothingBlack,
    surfaceContainerLow = NothingSurfaceLow,
    surfaceContainer = NothingSurface,
    surfaceContainerHigh = NothingSurfaceHi,
    surfaceContainerHighest = NothingSurfaceHi2,
    outline = NothingLine,
    outlineVariant = NothingLineDim,
    error = ErrorDark,
    onError = OnErrorDark,
    errorContainer = ErrorCtrDark,
    onErrorContainer = OnErrorCtrDark,
    scrim = Color(0xCC000000),
)

private val LightColors = lightColorScheme(
    primary = NothingRed,
    onPrimary = Color.White,
    primaryContainer = NothingRedDeep,
    onPrimaryContainer = Color.White,
    secondary = Color(0xFF6B6B6B),
    onSecondary = Color.White,
    secondaryContainer = NothingPaperVar,
    onSecondaryContainer = NothingInk,
    tertiary = NothingInkMuted,
    onTertiary = Color.White,
    tertiaryContainer = NothingPaperVar,
    onTertiaryContainer = NothingInk,
    background = NothingPaper,
    onBackground = NothingInk,
    surface = NothingPaperSurf,
    onSurface = NothingInk,
    surfaceVariant = NothingPaperVar,
    onSurfaceVariant = NothingInkMuted,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFAFAFA),
    surfaceContainer = Color(0xFFF3F3F3),
    surfaceContainerHigh = Color(0xFFEDEDED),
    surfaceContainerHighest = Color(0xFFE4E4E4),
    outline = NothingLineLight,
    outlineVariant = NothingLineLightDim,
    error = ErrorLight,
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD7),
    onErrorContainer = Color(0xFF410005),
)

private val DarkBrand = CvBrandColors(success = Success, warning = Warning, info = InfoGray, accent = NothingRed, highlight = NothingRed)
private val LightBrand = CvBrandColors(success = Color(0xFF0E9F6E), warning = Color(0xFFB7791F), info = Color(0xFF374151), accent = NothingRed, highlight = NothingRed)

@Composable
fun CallVaultTheme(
    // Nothing is a black-first design: dark by default.
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    val brand = if (darkTheme) DarkBrand else LightBrand

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val lightBars = colorScheme.background.luminance() > 0.5f
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = lightBars
                isAppearanceLightNavigationBars = lightBars
            }
        }
    }

    CompositionLocalProvider(LocalCvBrand provides brand) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = CvShapes,
            content = content,
        )
    }
}

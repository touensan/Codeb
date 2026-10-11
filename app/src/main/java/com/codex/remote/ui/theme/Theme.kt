package com.codex.remote.ui.theme

import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.codex.remote.ui.AppAppearance

val CodexGreen = Color(0xFF0E7A53)
val CodexRed = Color(0xFFB93A32)
val Ink = Color(0xFF171716)
val WarmBackground = Color(0xFFFFFFFF)
val Panel = Color(0xFFF2F2F2)
val Stroke = Color(0xFF747474)

private val LightColors = lightColorScheme(
    primary = Ink,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6E6E2),
    onPrimaryContainer = Ink,
    secondary = CodexGreen,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6E6E6),
    onSecondaryContainer = Ink,
    tertiary = Color(0xFF795900),
    onTertiary = Color.White,
    error = CodexRed,
    background = WarmBackground,
    onBackground = Ink,
    surface = Color(0xFFFCFCFC),
    surfaceTint = Color.Transparent,
    surfaceDim = Color(0xFFDDDDDD),
    surfaceBright = Color.White,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF8F8F8),
    surfaceContainer = Color(0xFFF5F5F5),
    surfaceContainerHigh = Color(0xFFECECEC),
    surfaceContainerHighest = Color(0xFFE5E5E5),
    onSurface = Ink,
    surfaceVariant = Panel,
    onSurfaceVariant = Color(0xFF565650),
    outline = Stroke,
    outlineVariant = Color(0xFFDDDDDD),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFF1F1ED),
    onPrimary = Color(0xFF181817),
    primaryContainer = Color(0xFF353532),
    onPrimaryContainer = Color(0xFFF1F1ED),
    secondary = Color(0xFF67C99B),
    onSecondary = Color(0xFF072A1C),
    secondaryContainer = Color(0xFF353535),
    onSecondaryContainer = Color(0xFFF1F1ED),
    tertiary = Color(0xFFE5C572),
    onTertiary = Color(0xFF332600),
    error = Color(0xFFFFB4AC),
    background = Color.Black,
    onBackground = Color(0xFFF0F0EC),
    surface = Color(0xFF151515),
    surfaceTint = Color.Transparent,
    surfaceDim = Color(0xFF151515),
    surfaceBright = Color(0xFF393939),
    surfaceContainerLowest = Color(0xFF0D0D0D),
    surfaceContainerLow = Color(0xFF171717),
    surfaceContainer = Color(0xFF191919),
    surfaceContainerHigh = Color(0xFF242424),
    surfaceContainerHighest = Color(0xFF303030),
    onSurface = Color(0xFFF0F0EC),
    surfaceVariant = Color(0xFF292929),
    onSurfaceVariant = Color(0xFFB9B9B1),
    outline = Color(0xFF8A8A8A),
    outlineVariant = Color(0xFF414141),
)

private val CodexTypography = Typography(
    displaySmall = TextStyle(fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    titleLarge = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    titleMedium = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp, letterSpacing = 0.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, letterSpacing = 0.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
)

val MonoText = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 12.sp,
    lineHeight = 18.sp,
    letterSpacing = 0.sp,
)

@Composable
fun CodexRemoteTheme(
    darkTheme: Boolean = AppAppearance.isDark(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context as ComponentActivity
            // Match gesture/three-button navigation scrims to the app override as well as the icons.
            activity.enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { darkTheme },
                navigationBarStyle = SystemBarStyle.auto(LightColors.surface.toArgb(), DarkColors.surface.toArgb()) { darkTheme },
            )
            val window = activity.window
            window.setBackgroundDrawableResource(if (darkTheme) android.R.color.black else android.R.color.white)
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
    MaterialTheme(colorScheme = colors, typography = CodexTypography,
        shapes = Shapes(extraSmall = RoundedCornerShape(12.dp), small = RoundedCornerShape(16.dp),
            medium = RoundedCornerShape(24.dp), large = RoundedCornerShape(28.dp), extraLarge = RoundedCornerShape(32.dp))) {
        // MaterialTheme alone does not provide LocalContentColor to unwrapped Text/Icon nodes.
        Surface(Modifier.fillMaxSize(), color = colors.background, contentColor = colors.onBackground, content = content)
    }
}

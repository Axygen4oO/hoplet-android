package com.wdtt.client

import android.app.Activity
import android.os.Build
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

val InterFontFamily = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

val HopletTypography = Typography(
    displayLarge = TextStyle(fontFamily = InterFontFamily, fontWeight = FontWeight.Bold, fontSize = 52.sp, lineHeight = 58.sp, letterSpacing = (-0.4).sp),
    displayMedium = TextStyle(fontFamily = InterFontFamily, fontWeight = FontWeight.Bold, fontSize = 42.sp, lineHeight = 48.sp, letterSpacing = (-0.3).sp),
    displaySmall = TextStyle(fontFamily = InterFontFamily, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.2).sp),
    headlineLarge = TextStyle(fontFamily = InterFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 36.sp),
    headlineMedium = TextStyle(fontFamily = InterFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 32.sp),
    headlineSmall = TextStyle(fontFamily = InterFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontFamily = InterFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = InterFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 0.1.sp),
    titleSmall = TextStyle(fontFamily = InterFontFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    bodyLarge = TextStyle(fontFamily = InterFontFamily, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.2.sp),
    bodyMedium = TextStyle(fontFamily = InterFontFamily, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.15.sp),
    bodySmall = TextStyle(fontFamily = InterFontFamily, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 18.sp, letterSpacing = 0.1.sp),
    labelLarge = TextStyle(fontFamily = InterFontFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 18.sp, letterSpacing = 0.2.sp),
    labelMedium = TextStyle(fontFamily = InterFontFamily, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.2.sp),
    labelSmall = TextStyle(fontFamily = InterFontFamily, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.2.sp),
)

val WDTTTypography = HopletTypography

private val HopletMaterialColors = darkColorScheme(
    primary = Color(0xFF29C39A),
    onPrimary = Color(0xFF04110D),
    primaryContainer = Color(0xFF11352D),
    onPrimaryContainer = Color(0xFFC3F4E5),
    secondary = Color(0xFF5CC8FF),
    onSecondary = Color(0xFF03111A),
    secondaryContainer = Color(0xFF123244),
    onSecondaryContainer = Color(0xFFC9EEFF),
    tertiary = Color(0xFF97D8C3),
    onTertiary = Color(0xFF07110D),
    tertiaryContainer = Color(0xFF17362E),
    onTertiaryContainer = Color(0xFFD2F5EA),
    background = Color(0xFF050608),
    onBackground = Color(0xFFF4FBF8),
    surface = Color(0xFF0B1413),
    onSurface = Color(0xFFE7F3EF),
    surfaceVariant = Color(0xFF111D1B),
    onSurfaceVariant = Color(0xFF9EB2AC),
    outline = Color(0xFF29403A),
    outlineVariant = Color(0xFF182825),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF240606),
    errorContainer = Color(0xFF4D1717),
    onErrorContainer = Color(0xFFFFD9D9),
    inverseSurface = Color(0xFFE7F3EF),
    inverseOnSurface = Color(0xFF0D1614),
    inversePrimary = Color(0xFF0F3F34),
    surfaceTint = Color(0xFF29C39A),
)

@Immutable
data class HopletSemanticColors(
    val card: Color,
    val cardMuted: Color,
    val cardElevated: Color,
    val border: Color,
    val borderStrong: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val accent: Color,
    val accentSoft: Color,
    val info: Color,
    val infoSoft: Color,
    val success: Color,
    val successSoft: Color,
    val warning: Color,
    val warningSoft: Color,
    val danger: Color,
    val dangerSoft: Color,
    val terminalBackground: Color,
    val terminalText: Color,
    val overlay: Color,
)

@Immutable
data class HopletSpacing(
    val xxs: Dp = 4.dp,
    val xs: Dp = 8.dp,
    val sm: Dp = 12.dp,
    val md: Dp = 16.dp,
    val lg: Dp = 20.dp,
    val xl: Dp = 24.dp,
    val xxl: Dp = 32.dp,
)

@Immutable
data class HopletElevation(
    val flat: Dp = 0.dp,
    val low: Dp = 2.dp,
    val medium: Dp = 8.dp,
    val high: Dp = 16.dp,
)

@Immutable
data class HopletDimensions(
    val topBarHeight: Dp = 64.dp,
    val navigationHeight: Dp = 72.dp,
    val compactCardMinHeight: Dp = 56.dp,
    val regularCardMinHeight: Dp = 72.dp,
    val touchTarget: Dp = 48.dp,
)

@Immutable
data class HopletIcons(
    val dns: ImageVector = Icons.Rounded.Dns,
    val vk: ImageVector = Icons.Rounded.Public,
    val wrap: ImageVector = Icons.Rounded.Link,
    val turn: ImageVector = Icons.Rounded.Hub,
    val dtls: ImageVector = Icons.Rounded.Security,
    val streams: ImageVector = Icons.Rounded.Bolt,
    val vpn: ImageVector = Icons.Rounded.Shield,
)

val HopletShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(30.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(36.dp),
)

private val HopletColors = HopletSemanticColors(
    card = Color(0xFF101A18),
    cardMuted = Color(0xFF0C1413),
    cardElevated = Color(0xFF14211F),
    border = Color(0x1FFFFFFF),
    borderStrong = Color(0x3329C39A),
    textPrimary = Color(0xFFF4FBF8),
    textSecondary = Color(0xFFC8D7D2),
    textMuted = Color(0xFF8DA19A),
    accent = Color(0xFF29C39A),
    accentSoft = Color(0x1F29C39A),
    info = Color(0xFF5CC8FF),
    infoSoft = Color(0x1F5CC8FF),
    success = Color(0xFF39D98A),
    successSoft = Color(0x1F39D98A),
    warning = Color(0xFFFFB85C),
    warningSoft = Color(0x26FFB85C),
    danger = Color(0xFFFF6B6B),
    dangerSoft = Color(0x26FF6B6B),
    terminalBackground = Color(0xFF07100F),
    terminalText = Color(0xFFE2F1EC),
    overlay = Color(0xBF030807),
)

private val LocalHopletColors = staticCompositionLocalOf { HopletColors }
private val LocalHopletSpacing = staticCompositionLocalOf { HopletSpacing() }
private val LocalHopletElevation = staticCompositionLocalOf { HopletElevation() }
private val LocalHopletDimensions = staticCompositionLocalOf { HopletDimensions() }
private val LocalHopletIcons = staticCompositionLocalOf { HopletIcons() }

object HopletTheme {
    val colors: HopletSemanticColors
        @Composable get() = LocalHopletColors.current

    val spacing: HopletSpacing
        @Composable get() = LocalHopletSpacing.current

    val elevation: HopletElevation
        @Composable get() = LocalHopletElevation.current

    val dimensions: HopletDimensions
        @Composable get() = LocalHopletDimensions.current

    val icons: HopletIcons
        @Composable get() = LocalHopletIcons.current

    val typography: Typography
        @Composable get() = MaterialTheme.typography

    val materialColors: ColorScheme
        @Composable get() = MaterialTheme.colorScheme

    val shapes: Shapes
        @Composable get() = MaterialTheme.shapes

    @Composable
    operator fun invoke(content: @Composable () -> Unit) {
        val view = LocalView.current
        if (!view.isInEditMode) {
            SideEffect {
                val window = (view.context as Activity).window
                window.statusBarColor = Color.Transparent.toArgb()
                window.navigationBarColor = HopletMaterialColors.background.toArgb()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    window.isNavigationBarContrastEnforced = false
                    window.isStatusBarContrastEnforced = false
                }
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = false
                    isAppearanceLightNavigationBars = false
                }
            }
        }

        androidx.compose.runtime.CompositionLocalProvider(
            LocalHopletColors provides HopletColors,
            LocalHopletSpacing provides HopletSpacing(),
            LocalHopletElevation provides HopletElevation(),
            LocalHopletDimensions provides HopletDimensions(),
            LocalHopletIcons provides HopletIcons(),
        ) {
            MaterialTheme(
                colorScheme = HopletMaterialColors,
                typography = HopletTypography,
                shapes = HopletShapes,
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    AuroraBackground(modifier = Modifier.fillMaxSize())
                    content()
                }
            }
        }
    }
}

object WDTTColors {
    val connected = HopletColors.success
    val connectedContainer = HopletColors.successSoft
    val onConnected = HopletColors.textPrimary
    val connectedDark = HopletColors.success
    val connectedContainerDark = HopletColors.successSoft
    val onConnectedDark = HopletColors.textPrimary
    val warning = HopletColors.warning
    val warningDark = HopletColors.warning
    val terminalBg = HopletColors.terminalBackground
    val terminalBgDark = HopletColors.terminalBackground
    val terminalText = HopletColors.terminalText
    val terminalGreen = HopletColors.success
    val terminalBlue = HopletColors.info
    val terminalRed = HopletColors.danger
    val terminalYellow = HopletColors.warning
    val terminalCounter = HopletColors.info
    val github = Color(0xFF0E1715)
    val githubDark = Color(0xFF172321)
    val donate = HopletColors.accent
}

@Composable
fun WDTTTheme(
    themeMode: String = "dark",
    dynamicColor: Boolean = false,
    themePalette: String = "hoplet",
    content: @Composable () -> Unit
) {
    HopletTheme(content)
}

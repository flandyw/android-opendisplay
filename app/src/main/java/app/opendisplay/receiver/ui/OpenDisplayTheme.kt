package app.opendisplay.receiver.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight

private val LightColors = lightColorScheme(
    primary = Color(0xFF365E9D),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8E3FF),
    onPrimaryContainer = Color(0xFF102F60),
    secondary = Color(0xFF535F75),
    secondaryContainer = Color(0xFFD7E3FA),
    onSecondaryContainer = Color(0xFF152238),
    tertiary = Color(0xFF366751),
    tertiaryContainer = Color(0xFFB9EDD0),
    onTertiaryContainer = Color(0xFF002115),
    background = Color(0xFFF8F9FF),
    surface = Color(0xFFF8F9FF),
    onSurface = Color(0xFF191C23),
    onSurfaceVariant = Color(0xFF434752),
    surfaceContainerLow = Color(0xFFF1F3FA),
    surfaceContainer = Color(0xFFEBEEF5),
    surfaceContainerHigh = Color(0xFFE5E8EF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFAEC6FF),
    onPrimary = Color(0xFF003063),
    primaryContainer = Color(0xFF244777),
    onPrimaryContainer = Color(0xFFD8E3FF),
    secondary = Color(0xFFBBC7DE),
    secondaryContainer = Color(0xFF3B475C),
    onSecondaryContainer = Color(0xFFD7E3FA),
    tertiary = Color(0xFF9DD1B5),
    tertiaryContainer = Color(0xFF1D4F3A),
    onTertiaryContainer = Color(0xFFB9EDD0),
    background = Color(0xFF10131A),
    surface = Color(0xFF10131A),
    onSurface = Color(0xFFE1E3ED),
    onSurfaceVariant = Color(0xFFC3C6D2),
    surfaceContainerLow = Color(0xFF191C23),
    surfaceContainer = Color(0xFF1D2028),
    surfaceContainerHigh = Color(0xFF282B33),
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private val OpenDisplayTypography = Typography().run {
    copy(
        displaySmallEmphasized = displaySmallEmphasized.copy(fontWeight = FontWeight.Bold),
        displayMediumEmphasized = displayMediumEmphasized.copy(fontWeight = FontWeight.Bold),
        headlineSmallEmphasized = headlineSmallEmphasized.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun OpenDisplayTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialExpressiveTheme(
        colorScheme = colors,
        typography = OpenDisplayTypography,
        content = content,
    )
}

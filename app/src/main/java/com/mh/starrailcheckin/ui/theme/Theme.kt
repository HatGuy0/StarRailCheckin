package com.mh.starrailcheckin.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.mh.starrailcheckin.util.Prefs
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.lightColorScheme as miuixLightColorScheme
import top.yukonga.miuix.kmp.theme.darkColorScheme as miuixDarkColorScheme

/** 应用内当前是否为深色（跟随手动主题选择，供卡片阴影色、底栏文字色取明暗） */
val LocalAppDarkTheme = staticCompositionLocalOf { false }

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF3482FF),
    secondary = Color(0xFF00BCD4),
    background = Color(0xFFF5F5FA),
    surface = Color(0xFFFFFFFF),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = Color(0xFF1C1B1F),
    onSurface = Color(0xFF1C1B1F),
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF6EA8FF),
    secondary = Color(0xFF4DD0E1),
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E1E),
    onPrimary = Color.Black,
    onSecondary = Color.Black,
    onBackground = Color(0xFFE6E1E5),
    onSurface = Color(0xFFE6E1E5),
)

/**
 * 主页根背景渐变（同时被录进 backdrop 供底栏玻璃采样）。
 * 低饱和、贴近原纯色背景，给 blur/lens 提供可作用的明暗变化，对齐官方壁纸式录制源。
 */
val LightBackgroundGradient = listOf(
    Color(0xFFF5F5FA),
    Color(0xFFECEFFB),
    Color(0xFFF0ECFA)
)

val DarkBackgroundGradient = listOf(
    Color(0xFF121212),
    Color(0xFF16181F),
    Color(0xFF1A1B26)
)

/** AMOLED 纯黑深色背景（开关开启且处于深色主题时使用）。 */
val AmoledBackgroundGradient = listOf(
    Color(0xFF000000),
    Color(0xFF000000),
    Color(0xFF000000)
)

@Composable
fun StarRailCheckinTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    forceDark: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    // 0 跟随系统 / 1 浅色 / 2 深色，与设置页主题选择器一致
    val themeMode = Prefs.themeMode(context)
    val schemeMode = when {
        forceDark -> ColorSchemeMode.Dark
        themeMode == 1 -> ColorSchemeMode.Light
        themeMode == 2 -> ColorSchemeMode.Dark
        else -> ColorSchemeMode.System
    }
    val resolvedDarkTheme = when {
        forceDark -> true
        themeMode == 1 -> false
        themeMode == 2 -> true
        else -> darkTheme
    }
    val amoled = Prefs.amoledDark(context)
    val baseScheme = if (resolvedDarkTheme) DarkColorScheme else LightColorScheme
    val colorScheme = if (resolvedDarkTheme && amoled) {
        baseScheme.copy(background = Color.Black, surface = Color.Black)
    } else baseScheme
    // 显式传入 isDark：Miuix 与内层 M3 使用同一套明暗判断，避免 System 模式下两处漂移
    val controller = remember(schemeMode, resolvedDarkTheme) {
        ThemeController(
            schemeMode,
            lightColors = miuixLightColorScheme(),
            darkColors = miuixDarkColorScheme(),
            isDark = resolvedDarkTheme
        )
    }

    // 外层 Miuix 主题：供后续替换的 Miuix 组件取色。
    // 内层 M3 主题：供现有 M3 组件与底栏 Tab 槽位（Icon/Text）兜底隔离，避免被 Miuix 主题改色。
    MiuixTheme(controller = controller) {
        CompositionLocalProvider(LocalAppDarkTheme provides resolvedDarkTheme) {
            MaterialTheme(
                colorScheme = colorScheme,
                content = content
            )
        }
    }
}

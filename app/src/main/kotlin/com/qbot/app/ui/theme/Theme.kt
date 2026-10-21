package com.qbot.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 状态色：与常见运维面板一致，绿=正常、黄=异常但可恢复、红=致命
val StatusOnline = Color(0xFF1B8E3E)
val StatusWarning = Color(0xFFB26A00)
val StatusError = Color(0xFFC62828)
val StatusIdle = Color(0xFF6B7280)

private val LightColors =
    lightColorScheme(
        primary = Color(0xFF1A73E8),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFD6E4FF),
        secondary = Color(0xFF00639B),
        surface = Color(0xFFFDFCFF),
        background = Color(0xFFF7F9FC),
    )

private val DarkColors =
    darkColorScheme(
        primary = Color(0xFF9EC3FF),
        onPrimary = Color(0xFF003060),
        primaryContainer = Color(0xFF00468B),
        secondary = Color(0xFF8ECDFF),
    )

@Composable
fun QBotMobileTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography(),
        content = content,
    )
}

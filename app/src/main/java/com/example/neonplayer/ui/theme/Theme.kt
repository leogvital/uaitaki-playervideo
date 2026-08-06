package com.example.neonplayer.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Tema escuro fixo inspirado no VLC para Android (fundo quase preto + laranja como cor de
 * destaque) — o app não segue o tema claro/escuro do sistema nem cor dinâmica, é a identidade
 * visual própria do NeonPlayer.
 */
private val NeonDarkColorScheme = darkColorScheme(
    primary = NeonOrange,
    onPrimary = Color.Black,
    secondary = NeonOrangeVariant,
    onSecondary = Color.Black,
    tertiary = NeonOrangeVariant,
    background = NeonBackground,
    onBackground = NeonOnBackground,
    surface = NeonSurface,
    onSurface = NeonOnBackground,
    surfaceVariant = NeonSurfaceVariant,
    onSurfaceVariant = NeonOnSurfaceVariant,
    outline = NeonOutline,
)

@Composable
fun NeonPlayerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NeonDarkColorScheme,
        typography = Typography,
        content = content,
    )
}

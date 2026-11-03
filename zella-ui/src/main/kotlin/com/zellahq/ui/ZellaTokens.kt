package com.zellahq.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Design tokens. Nothing outside this module may hard-code a colour, size or radius. */
object ZellaColors {
    val Accent = Color(0xFF7C6CFF)
    val Ink = Color(0xFF14121F)
    val Night = Color(0xFF0E0D16)
    val NightCard = Color(0xFF1C1A2B)
    val Paper = Color(0xFFF6F5FB)
    val CardViolet = Color(0xFF7C6CFF)
    val CardMint = Color(0xFF5EE0B0)
    val CardSun = Color(0xFFFFC857)
    val CardCoral = Color(0xFFFF7A6B)
}

object ZellaSpacing {
    val Xs = 4.dp
    val S = 8.dp
    val M = 16.dp
    val L = 24.dp
    val Xl = 32.dp

    /** Smallest allowed tap target. */
    val MinTap = 48.dp
}

object ZellaRadius {
    val Chip = 999.dp
    val Card = 20.dp
}

@Composable
fun ZellaTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val scheme =
        if (dark) {
            darkColorScheme(
                primary = ZellaColors.Accent,
                background = ZellaColors.Night,
                surface = ZellaColors.NightCard,
            )
        } else {
            lightColorScheme(primary = ZellaColors.Accent, background = ZellaColors.Paper, surface = Color.White)
        }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** Saturated card with dark ink, as the iPhone app does. Tinted washes read as boring. */
@Composable
fun ZellaCard(
    color: Color,
    text: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .heightIn(min = ZellaSpacing.MinTap)
                .background(color, RoundedCornerShape(ZellaRadius.Card))
                .padding(ZellaSpacing.M),
        contentAlignment = Alignment.CenterStart,
    ) { Text(text, color = ZellaColors.Ink) }
}

/** Small pill, used for the Pro Week chip in the home header. */
@Composable
fun ZellaChip(
    text: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .heightIn(min = ZellaSpacing.MinTap)
                .background(ZellaColors.Accent, RoundedCornerShape(ZellaRadius.Chip))
                .padding(horizontal = ZellaSpacing.M),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = ZellaColors.Ink) }
}

package com.nishith.usageguard.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.nishith.usageguard.UsageRepo

object C {
    val Ground = Color(0xFFF4F3EF)
    val Ink = Color(0xFF16181D)
    val Muted = Color(0xFF5A5E66)
    val Soft = Color(0xFFB9BCC4)
    val Accent = Color(0xFFF07A2F)
    val AccentText = Color(0xFFB14A0E)
    val Line = Color(0xFFEFEEE9)
    val Track = Color(0xFFECEBE6)
    val Green = Color(0xFF1E6B45)
    val GreenBg = Color(0xFFE3F2EA)
    val Slate = Color(0xFF5B6170)
}

@Composable
fun GuardTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = C.Ink,
            onPrimary = Color.White,
            secondary = C.Accent,
            background = C.Ground,
            surface = Color.White,
            onSurface = C.Ink,
            onBackground = C.Ink,
            surfaceVariant = C.Track,
            outline = C.Muted,
        ),
        content = content,
    )
}

@Composable
fun ScreenTitle(text: String) {
    Text(text, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = C.Ink)
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        color = C.Muted, letterSpacing = 0.8.sp,
    )
}

@Composable
fun WhiteCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content,
    )
}

@Composable
fun Divider() = HorizontalDivider(color = C.Line, thickness = 1.dp)

@Composable
fun LabelBlock(title: String, sub: String?, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = C.Ink)
        if (sub != null) Text(sub, fontSize = 12.sp, color = C.Muted)
    }
}

/** The "−  2 min  +" control from the design. */
@Composable
fun Stepper(
    title: String,
    sub: String?,
    value: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    enabled: Boolean = true,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        LabelBlock(title, sub, Modifier.weight(1f))
        Row(
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(C.Ground)
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StepButton("−", "Decrease $title", enabled, onMinus)
            Text(
                value, Modifier.widthIn(min = 64.dp), textAlign = TextAlign.Center,
                fontSize = 17.sp, fontWeight = FontWeight.Bold,
                color = if (enabled) C.Ink else C.Muted,
            )
            StepButton("+", "Increase $title", enabled, onPlus)
        }
    }
}

@Composable
private fun StepButton(symbol: String, label: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(44.dp),
        colors = IconButtonDefaults.iconButtonColors(containerColor = Color.White),
    ) {
        Text(symbol, fontSize = 20.sp, color = if (enabled) C.Ink else C.Soft)
    }
}

@Composable
fun AppIcon(pkg: String, size: Dp = 40.dp) {
    val ctx = LocalContext.current
    val bmp = remember(pkg) {
        UsageRepo.icon(ctx, pkg)?.toBitmap(128, 128)?.asImageBitmap()
    }
    if (bmp != null) {
        Image(bmp, contentDescription = null, modifier = Modifier.size(size).clip(RoundedCornerShape(12.dp)))
    } else {
        Box(Modifier.size(size).clip(RoundedCornerShape(12.dp)).background(C.Slate))
    }
}

@Composable
fun ProgressBar(fraction: Float, color: Color, height: Dp = 6.dp) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(C.Track)
    ) {
        if (fraction > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(fraction.coerceIn(0.02f, 1f))
                    .height(height)
                    .clip(RoundedCornerShape(height / 2))
                    .background(color)
            )
        }
    }
}

@Composable
fun VSpace(h: Dp) = Spacer(Modifier.height(h))

/** Step through a fixed list of choices (seconds). */
fun stepIn(options: List<Int>, current: Int, dir: Int): Int {
    val idx = options.indexOfFirst { it >= current }.let { if (it < 0) options.lastIndex else it }
    val exact = options.getOrNull(idx) == current
    val next = when {
        dir > 0 -> if (exact) idx + 1 else idx
        else -> idx - 1
    }
    return options[next.coerceIn(0, options.lastIndex)]
}

val LIMIT_OPTIONS = listOf(30, 60, 120, 180, 300, 600, 900, 1200, 1800, 2700, 3600)
val COOLDOWN_OPTIONS = listOf(60, 300, 600, 900, 1200, 1800, 2700, 3600, 5400, 7200)
val GAP_OPTIONS = listOf(10, 30, 60, 120, 300)

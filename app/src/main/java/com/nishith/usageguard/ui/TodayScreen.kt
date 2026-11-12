package com.nishith.usageguard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nishith.usageguard.AppStat
import com.nishith.usageguard.DayStats
import com.nishith.usageguard.GuardPrefs
import com.nishith.usageguard.UsageRepo
import com.nishith.usageguard.formatDuration
import com.nishith.usageguard.formatShort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Date

@Composable
fun TodayScreen(refresh: Int, stoppedAt: Long?, onEditLimits: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { GuardPrefs(ctx) }
    var stats by remember { mutableStateOf<DayStats?>(null) }
    var cooldowns by remember { mutableStateOf(0) }

    var recent by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }

    LaunchedEffect(refresh) {
        while (true) {
            stats = withContext(Dispatchers.IO) { UsageRepo.today(ctx, prefs.resetGapSec * 1000L) }
            cooldowns = prefs.totalCooldownsToday()
            delay(30_000)
        }
    }
    // The last-5-minutes figures move quickly, so refresh them more often.
    LaunchedEffect(refresh) {
        while (true) {
            recent = withContext(Dispatchers.IO) { UsageRepo.recentUsage(ctx) }
            delay(5_000)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, MMM d")),
                    fontSize = 13.sp, color = C.Muted,
                )
                ScreenTitle("Today")
            }
            GuardChip(prefs.guardOn)
        }

        if (stoppedAt != null) StoppedBanner(stoppedAt)

        SummaryCard(stats, cooldowns)

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Apps used today", Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = onEditLimits) {
                Text("Edit limits", color = C.Ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
        }

        val apps = stats?.apps
        when {
            apps == null -> Text("Loading…", color = C.Muted)
            apps.isEmpty() -> WhiteCard { Text("No app use recorded yet today.", color = C.Muted) }
            else -> Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.White)
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                val max = apps.first().totalMs.coerceAtLeast(1)
                apps.forEachIndexed { i, a ->
                    AppRow(a, max, prefs, recent[a.pkg] ?: 0L)
                    if (i != apps.lastIndex) Divider()
                }
            }
        }
    }
}

/** Shown when Android had shut the guard down while the app was closed. */
@Composable
private fun StoppedBanner(stoppedAt: Long) {
    val ctx = LocalContext.current
    val batteryOk = remember { UsageRepo.ignoresBatteryOptimizations(ctx) }
    val time = if (android.text.format.DateUtils.isToday(stoppedAt))
        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(stoppedAt))
    else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(stoppedAt))
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFFFCEBDD))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Android stopped the guard", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = C.AccentText)
        Text(
            "It was last running around $time, so no limits were enforced after that. It's running again now." +
                if (batteryOk) " If this keeps happening, check your phone's battery settings for Usage Guard."
                else " To stop this, let Usage Guard use the battery without restrictions. " + UsageRepo.BATTERY_STEPS,
            fontSize = 13.sp, color = C.Ink, lineHeight = 18.sp,
        )
        if (!batteryOk) {
            androidx.compose.material3.Button(
                onClick = { UsageRepo.openBatterySettings(ctx) },
                shape = RoundedCornerShape(12.dp),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = C.Ink, contentColor = Color.White),
            ) { Text("Open battery settings", fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun GuardChip(on: Boolean) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (on) C.GreenBg else C.Track)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (on) Color(0xFF1E8A55) else C.Muted))
        Text(
            if (on) "Guard on" else "Guard off",
            fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            color = if (on) C.Green else C.Muted,
        )
    }
}

@Composable
private fun SummaryCard(stats: DayStats?, cooldowns: Int) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(C.Ink)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Screen time today", fontSize = 13.sp, color = C.Soft)
                Text(
                    stats?.let { formatDuration(it.totalMs) } ?: "—",
                    fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Color.White, lineHeight = 40.sp,
                )
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Cooldowns", fontSize = 13.sp, color = C.Soft)
                Text("$cooldowns", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = C.Accent, lineHeight = 28.sp)
            }
        }
        val hourly = stats?.hourly ?: LongArray(24)
        val maxH = (hourly.maxOrNull() ?: 0L).coerceAtLeast(60_000L)
        val nowHour = LocalTime.now().hour
        Row(
            Modifier.fillMaxWidth().height(48.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            for (h in 0 until 24) {
                val f = (hourly[h].toFloat() / maxH).coerceIn(0.04f, 1f)
                val color = when {
                    h > nowHour -> Color(0xFF2A2D35)
                    hourly[h] >= 20 * 60_000L -> C.Accent
                    else -> C.Slate
                }
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight(if (h > nowHour) 0.04f else f)
                        .clip(RoundedCornerShape(2.dp))
                        .background(color)
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("12 AM", "6 AM", "12 PM", "6 PM", "12 AM").forEach {
                Text(it, fontSize = 11.sp, color = C.Soft)
            }
        }
    }
}

@Composable
private fun AppRow(a: AppStat, max: Long, prefs: GuardPrefs, recentMs: Long) {
    val excluded = !prefs.isLimited(a.pkg)
    val rule = prefs.ruleFor(a.pkg)
    val locked = prefs.lockedUntil(rule.key).takeIf { it > System.currentTimeMillis() }
    val cds = prefs.cooldownsToday(rule.key)

    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(a.pkg)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text(a.label, Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                Text(formatDuration(a.totalMs), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            ProgressBar(a.totalMs.toFloat() / max, if (cds > 0) C.Accent else Color(0xFF9A9EA6))
            Row(Modifier.fillMaxWidth()) {
                Text(
                    when {
                        excluded -> "Not limited"
                        rule.group != null -> "${rule.group.name} · ${a.sessions} sessions"
                        else -> "${a.sessions} sessions · longest ${formatDuration(a.longestMs)}"
                    },
                    Modifier.weight(1f), fontSize = 12.sp, color = C.Muted,
                )
                val (flag, color) = when {
                    locked != null -> "Locked until " +
                        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(locked)) to C.AccentText
                    excluded -> "" to C.Muted
                    cds > 0 -> "$cds cooldown${if (cds == 1) "" else "s"}" +
                        (if (rule.group != null) " (group)" else "") to C.AccentText
                    else -> "Within limit" to C.Green
                }
                Text(flag, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = color)
            }
            if (!excluded) RecentLine(recentMs)
        }
    }
}

/** "Last 5 min: 1m 40s" with a small bar out of 5 minutes. Shown for limited apps. */
@Composable
fun RecentLine(recentMs: Long) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Last 5 min: ${formatShort(recentMs)}",
            fontSize = 12.sp,
            fontWeight = if (recentMs > 0) FontWeight.SemiBold else FontWeight.Normal,
            color = if (recentMs > 0) C.AccentText else C.Muted,
        )
        Box(Modifier.weight(1f)) {
            ProgressBar(recentMs / (5 * 60_000f), C.Accent, height = 4.dp)
        }
    }
}

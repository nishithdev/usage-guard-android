package com.nishith.usageguard

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.telecom.TelecomManager
import java.time.LocalDate
import java.time.ZoneId

data class AppStat(
    val pkg: String,
    val label: String,
    val totalMs: Long,
    val sessions: Int,
    val longestMs: Long,
)

data class DayStats(
    val totalMs: Long,
    val hourly: LongArray,
    val apps: List<AppStat>,
)

data class LaunchableApp(val pkg: String, val label: String)

object UsageRepo {

    // ---------- permissions ----------

    fun hasUsageAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun canDrawOverlays(ctx: Context) = Settings.canDrawOverlays(ctx)

    /** What to tap once the battery settings are open. */
    const val BATTERY_STEPS =
        "On the App info screen, tap App battery usage (or Battery) and choose Unrestricted."

    /**
     * Opens Usage Guard's own App info page, where the battery setting lives on Android 12+.
     * We don't ask for the exemption directly: that needs a permission Google Play restricts.
     */
    fun openBatterySettings(ctx: Context) {
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(details) }.onFailure { runCatching { ctx.startActivity(list) } }
    }

    fun ignoresBatteryOptimizations(ctx: Context): Boolean {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }

    // ---------- which apps count ----------

    /** Launchers, the system UI, the phone dialer and this app are never limited. */
    fun alwaysIgnored(ctx: Context): Set<String> {
        val pm = ctx.packageManager
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val homes = pm.queryIntentActivities(home, 0).map { it.activityInfo.packageName }
        val dialer = runCatching {
            (ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager).defaultDialerPackage
        }.getOrNull()
        return (homes + listOfNotNull(dialer) + listOf(ctx.packageName, "com.android.systemui", "android")).toSet()
    }

    fun launchableApps(ctx: Context): List<LaunchableApp> {
        val pm = ctx.packageManager
        val ignored = alwaysIgnored(ctx)
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it !in ignored }
            .map { LaunchableApp(it, label(ctx, it)) }
            .sortedBy { it.label.lowercase() }
    }

    fun label(ctx: Context, pkg: String): String = runCatching {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    fun icon(ctx: Context, pkg: String): Drawable? = runCatching {
        ctx.packageManager.getApplicationIcon(pkg)
    }.getOrNull()

    // ---------- today's stats ----------

    /**
     * Rebuilds today's usage from the raw foreground/background events, so the
     * totals, hourly chart and session counts all come from the same data.
     */
    fun today(ctx: Context, resetGapMs: Long): DayStats {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val zone = ZoneId.systemDefault()
        val dayStart = LocalDate.now().atStartOfDay(zone).toInstant().toEpochMilli()
        val now = System.currentTimeMillis()
        val ignored = alwaysIgnored(ctx)
        val pm = ctx.packageManager

        val totals = HashMap<String, Long>()
        val sessions = HashMap<String, Int>()
        val curSession = HashMap<String, Long>()
        val longest = HashMap<String, Long>()
        val lastEnd = HashMap<String, Long>()
        val hourly = LongArray(24)

        fun close(pkg: String, start: Long, end: Long) {
            if (end <= start || pkg in ignored) return
            totals[pkg] = (totals[pkg] ?: 0) + (end - start)

            if (start - (lastEnd[pkg] ?: Long.MIN_VALUE / 2) > resetGapMs) {
                sessions[pkg] = (sessions[pkg] ?: 0) + 1
                curSession[pkg] = 0
            }
            val len = (curSession[pkg] ?: 0) + (end - start)
            curSession[pkg] = len
            if (len > (longest[pkg] ?: 0)) longest[pkg] = len
            lastEnd[pkg] = end

            var s = start
            while (s < end) {
                val hour = ((s - dayStart) / 3_600_000L).toInt().coerceIn(0, 23)
                val hourEnd = dayStart + (hour + 1) * 3_600_000L
                val e = minOf(end, hourEnd)
                hourly[hour] += e - s
                if (e <= s) break
                s = e
            }
        }

        forEachForegroundInterval(usm, dayStart, now) { pkg, start, end -> close(pkg, start, end) }

        val apps = totals.keys
            .filter { pm.getLaunchIntentForPackage(it) != null }
            .map { AppStat(it, label(ctx, it), totals[it]!!, sessions[it] ?: 0, longest[it] ?: 0) }
            .filter { it.totalMs >= 30_000 }
            .sortedByDescending { it.totalMs }

        return DayStats(apps.sumOf { it.totalMs }, hourly, apps)
    }

    /**
     * Calls [block] for each stretch of time an app was in front, from raw usage events.
     * A pause only ends the stretch if it's for the screen that's actually in front:
     * apps like Instagram open stories/reels/DMs as new screens, and Android reports the
     * new one as resumed before the old one is paused or stopped.
     */
    fun forEachForegroundInterval(
        usm: UsageStatsManager,
        from: Long,
        to: Long,
        block: (pkg: String, start: Long, end: Long) -> Unit,
    ) {
        val events = usm.queryEvents(from, to)
        val ev = UsageEvents.Event()
        var curPkg: String? = null
        var curClass: String? = null
        var curStart = 0L
        while (events.hasNextEvent()) {
            events.getNextEvent(ev)
            val ts = ev.timeStamp
            when (ev.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    if (curPkg != ev.packageName) {
                        curPkg?.let { block(it, curStart, ts) }
                        curPkg = ev.packageName
                        curStart = ts
                    }
                    curClass = ev.className
                }
                UsageEvents.Event.ACTIVITY_PAUSED -> {
                    if (ev.packageName == curPkg && ev.className == curClass) {
                        block(curPkg!!, curStart, ts)
                        curPkg = null
                        curClass = null
                    }
                }
                SCREEN_NON_INTERACTIVE, DEVICE_SHUTDOWN -> {
                    curPkg?.let { block(it, curStart, ts) }
                    curPkg = null
                    curClass = null
                }
            }
        }
        curPkg?.let { block(it, curStart, to) }
    }

    /** Time each app was in front during the last [windowMs] (default 5 minutes). */
    fun recentUsage(ctx: Context, windowMs: Long = 5 * 60_000L): Map<String, Long> {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val from = now - windowMs
        val out = HashMap<String, Long>()
        // Look back further so an app that was already open when the window started is counted.
        forEachForegroundInterval(usm, from - 30 * 60_000L, now) { pkg, start, end ->
            val s = maxOf(start, from)
            if (end > s) out[pkg] = (out[pkg] ?: 0L) + (end - s)
        }
        return out
    }

    // Event type constants (named here so they read clearly across API levels).
    const val ACTIVITY_STOPPED = 23
    const val SCREEN_NON_INTERACTIVE = 16
    const val DEVICE_SHUTDOWN = 26
}

fun formatDuration(ms: Long): String {
    val totalMin = ms / 60_000
    val h = totalMin / 60
    val m = totalMin % 60
    return when {
        h > 0 -> "${h}h ${m}m"
        totalMin > 0 -> "${m}m"
        else -> "<1m"
    }
}

fun formatClock(ms: Long): String {
    val s = (ms.coerceAtLeast(0) + 999) / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

/** "45s", "1m 40s", "5m 0s" */
fun formatShort(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s < 60) "${s}s" else "${s / 60}m ${s % 60}s"
}

fun formatSeconds(sec: Int): String = when {
    sec < 60 -> "$sec sec"
    sec % 60 == 0 -> "${sec / 60} min"
    else -> "${sec / 60}m ${sec % 60}s"
}

fun ordinal(n: Int): String {
    val suffix = if (n % 100 in 11..13) "th" else when (n % 10) {
        1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th"
    }
    return "$n$suffix"
}

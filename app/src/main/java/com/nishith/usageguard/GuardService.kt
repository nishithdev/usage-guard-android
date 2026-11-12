package com.nishith.usageguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Runs in the foreground, checks once a second which app is open,
 * counts continuous time per app (or per group) and, when the limit is hit,
 * covers the app with the lock shield and opens the full-screen lock.
 */
class GuardService : Service() {

    private lateinit var prefs: GuardPrefs
    private lateinit var usm: UsageStatsManager
    private lateinit var power: PowerManager
    private lateinit var audio: AudioManager
    private lateinit var shield: LockShield
    private val handler = Handler(Looper.getMainLooper())

    // Which app (and which of its screens) is in front, from usage events.
    private var currentPkg: String? = null
    private var currentClass: String? = null
    private var lastEventTs = 0L

    private var lastTick = 0L
    private var lastLockLaunch = 0L
    private var suppressUntil = 0L
    private var lastHeartbeat = 0L
    private var focus: AudioFocusRequest? = null
    private var ignored: Set<String> = emptySet()
    private var ignoredRefreshedAt = 0L

    private val sessionMs = HashMap<String, Long>()
    private val lastSeen = HashMap<String, Long>()

    private val loop = object : Runnable {
        override fun run() {
            runCatching { tick() }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = GuardPrefs(this)
        usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        power = getSystemService(Context.POWER_SERVICE) as PowerManager
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        shield = LockShield(this) { goHome() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        if (!prefs.guardOn || !UsageRepo.hasUsageAccess(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running) {
            val now = System.currentTimeMillis()
            lastEventTs = now - 10 * 60_000 // look back to learn which app is open right now
            lastTick = now
            handler.removeCallbacks(loop)
            handler.post(loop)
            running = true
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(loop)
        hideShield()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------------------------------------------------------------

    private fun tick() {
        val now = System.currentTimeMillis()
        val delta = (now - lastTick).coerceIn(0, 3000)
        lastTick = now

        if (now - lastHeartbeat > 30_000) {
            prefs.heartbeat = now
            lastHeartbeat = now
        }
        if (now - ignoredRefreshedAt > 5 * 60_000) {
            ignored = UsageRepo.alwaysIgnored(this) // includes this app, so the lock screen itself is never counted
            ignoredRefreshedAt = now
        }

        readForegroundEvents(now)

        val pkg = currentPkg.takeIf { power.isInteractive }
        // Opt-in: only apps you added (or that are in a group) are timed.
        // Anything else in front (home screen, our lock screen, an unlimited app) means no shield.
        if (pkg == null || pkg in ignored || !prefs.isLimited(pkg)) {
            hideShield()
            return
        }

        // Apps in a group share one timer and one lock (rule.key), single apps use their own.
        val rule = prefs.ruleFor(pkg)
        val key = rule.key

        // Still cooling down but the app is in front again? Lock it again.
        val until = prefs.lockedUntil(key)
        if (now < until) {
            lock(rule, pkg, until)
            return
        }
        hideShield()

        // Count continuous time. Leaving for longer than the reset gap starts a new session.
        // For a group, hopping between its apps counts as staying.
        val gapMs = prefs.resetGapSec * 1000L + 2000
        val used = if (now - (lastSeen[key] ?: 0L) <= gapMs) (sessionMs[key] ?: 0L) else 0L
        val newUsed = used + delta
        lastSeen[key] = now

        if (newUsed >= rule.limitSec * 1000L) {
            sessionMs[key] = 0
            val lockUntil = prefs.startCooldown(key, rule.cooldownSec, now)
            lastLockLaunch = 0L
            lock(rule, pkg, lockUntil)
        } else {
            sessionMs[key] = newUsed
        }
    }

    /**
     * Follows resume/pause events to know which app is in front.
     *
     * Apps like Instagram open new screens (stories, reels, DMs) as separate activities.
     * Android reports the new screen as resumed and *then* the old one as paused/stopped,
     * so we only treat a pause as "left the app" when it's for the screen that's actually
     * in front. (Before v0.5 any pause or stop from the same app cleared it, which made
     * the timer stop counting partway through a session.)
     */
    private fun readForegroundEvents(now: Long) {
        val events = usm.queryEvents(lastEventTs - 2000, now)
        val ev = UsageEvents.Event()
        var newest = lastEventTs
        while (events.hasNextEvent()) {
            events.getNextEvent(ev)
            if (ev.timeStamp <= lastEventTs) continue
            newest = maxOf(newest, ev.timeStamp)
            when (ev.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    currentPkg = ev.packageName
                    currentClass = ev.className
                }
                UsageEvents.Event.ACTIVITY_PAUSED ->
                    if (ev.packageName == currentPkg && ev.className == currentClass) {
                        currentPkg = null
                        currentClass = null
                    }
                UsageRepo.SCREEN_NON_INTERACTIVE, UsageRepo.DEVICE_SHUTDOWN -> {
                    currentPkg = null
                    currentClass = null
                }
            }
        }
        lastEventTs = newest
    }

    /** Cover the app with the shield right away, then open the full lock screen on top. */
    private fun lock(rule: Rule, pkg: String, until: Long) {
        val now = System.currentTimeMillis()
        if (now < suppressUntil) return // just sent you home; the home screen's event can lag a moment
        val info = lockInfo(rule, pkg, until)
        shield.show(info)
        takeAudioFocus()

        if (now - lastLockLaunch < 4000) return // the lock screen is on its way
        lastLockLaunch = now
        // Launch a moment after the shield appears: Android 15+ only allows it while our window is visible.
        handler.postDelayed({
            runCatching { startActivity(LockActivity.intent(this, info)) }
        }, 250)
    }

    private fun lockInfo(rule: Rule, pkg: String, until: Long): LockInfo {
        val group = rule.group
        val title: String
        val lockedApps: String
        if (group != null) {
            title = group.name
            val names = group.members.map { UsageRepo.label(this, it) }
            lockedApps = when {
                names.size <= 1 -> names.firstOrNull() ?: group.name
                names.size <= 3 -> names.dropLast(1).joinToString(", ") + " and " + names.last()
                else -> names.take(2).joinToString(", ") + " and ${names.size - 2} more"
            }
        } else {
            title = UsageRepo.label(this, pkg)
            lockedApps = title
        }
        return LockInfo(
            title = title,
            lockedApps = lockedApps,
            plural = group != null && group.members.size > 1,
            until = until,
            lengthMs = prefs.lockedLength(rule.key).takeIf { it > 0 } ?: (rule.cooldownSec * 1000L),
            limitSec = rule.limitSec,
            countToday = prefs.cooldownsToday(rule.key).coerceAtLeast(1),
        )
    }

    private fun hideShield() {
        if (!shield.isShowing) return
        shield.hide()
        focus?.let { runCatching { audio.abandonAudioFocusRequest(it) } }
    }

    /** Pauses whatever the locked app was playing, even if the lock screen can't open. */
    private fun takeAudioFocus() {
        val req = focus ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            .setOnAudioFocusChangeListener { }
            .build()
            .also { focus = it }
        runCatching { audio.requestAudioFocus(req) }
    }

    private fun goHome() {
        hideShield()
        suppressUntil = System.currentTimeMillis() + 1500
        currentPkg = null
        currentClass = null
        runCatching {
            startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    // ---------------------------------------------------------------------

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Guard status", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shows that Usage Guard is tracking app time"
                    setShowBadge(false)
                }
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_guard)
            .setContentTitle("Guard on")
            .setContentText("Tracking app time · limits active")
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, n, type)
    }

    companion object {
        private const val CHANNEL = "guard"
        private const val NOTIF_ID = 1

        /** True while the guard is running in this app process. */
        @Volatile
        var running = false
            private set

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, GuardService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, GuardService::class.java))
        }
    }
}

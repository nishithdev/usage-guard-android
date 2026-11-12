package com.nishith.usageguard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID
import kotlin.random.Random

/** Apps that share one session timer and one cooldown. */
data class AppGroup(
    val id: String,
    val name: String,
    val members: List<String>,
    val limitSec: Int,
    val cooldownSec: Int,
)

/**
 * The rule that applies to an app right now.
 * [key] identifies whose timer/lock it is: the package itself, or "g:<id>" for a group.
 */
data class Rule(
    val key: String,
    val limitSec: Int,
    val cooldownSec: Int,
    val group: AppGroup?,
)

/**
 * All settings and lock state, kept on the phone in SharedPreferences.
 * Times are stored in seconds; lock end times as epoch millis.
 */
class GuardPrefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("guard", Context.MODE_PRIVATE)

    var setupDone: Boolean
        get() = sp.getBoolean("setup_done", false)
        set(v) = sp.edit().putBoolean("setup_done", v).apply()

    var guardOn: Boolean
        get() = sp.getBoolean("guard_on", false)
        set(v) = sp.edit().putBoolean("guard_on", v).apply()

    /** When the guard last checked in; tells us roughly when Android stopped it. */
    var heartbeat: Long
        get() = sp.getLong("heartbeat", 0L)
        set(v) = sp.edit().putLong("heartbeat", v).apply()

    /** Continuous time in one app before it locks. */
    var defaultLimitSec: Int
        get() = sp.getInt("default_limit", 120)
        set(v) = sp.edit().putInt("default_limit", v).apply()

    /** How long the app stays locked after the limit. */
    var defaultCooldownSec: Int
        get() = sp.getInt("default_cooldown", 15 * 60)
        set(v) = sp.edit().putInt("default_cooldown", v).apply()

    /** Time away from an app before its session timer starts over. */
    var resetGapSec: Int
        get() = sp.getInt("reset_gap", 30)
        set(v) = sp.edit().putInt("reset_gap", v).apply()

    // ---------------- which apps are limited (opt-in) ----------------

    /** Apps you switched on individually. Apps in a group are limited through the group. */
    var limitedApps: Set<String>
        get() = sp.getStringSet("limited", emptySet())!!.toSet()
        set(v) = sp.edit().putStringSet("limited", v).apply()

    /** Only apps you added (or put in a group) are ever timed and locked. */
    fun isLimited(pkg: String) = pkg in limitedApps || groupOf(pkg) != null

    /** Turning an app off also takes it out of its group. */
    fun setLimited(pkg: String, on: Boolean) {
        limitedApps = if (on) limitedApps + pkg else limitedApps - pkg
        if (!on) groupOf(pkg)?.let { g -> saveGroup(g.copy(members = g.members - pkg)) }
    }

    /**
     * v0.2 limited every app unless excluded. Moving to opt-in: keep only apps that
     * had their own limit set; everything else starts off.
     */
    private fun migrateToOptIn() {
        if (sp.getBoolean("opt_in_migrated", false)) return
        val custom = sp.all.keys
            .filter { it.startsWith("limit_") || it.startsWith("cooldown_") }
            .map { it.substringAfter('_') }
            .toSet()
        val excluded = sp.getStringSet("excluded", emptySet())!!
        sp.edit()
            .putStringSet("limited", custom - excluded)
            .remove("excluded")
            .putBoolean("opt_in_migrated", true)
            .apply()
    }

    init {
        migrateToOptIn()
    }

    // ---------------- per-app overrides (null = default) ----------------

    fun customLimit(pkg: String): Int? = sp.getInt("limit_$pkg", -1).takeIf { it > 0 }
    fun customCooldown(pkg: String): Int? = sp.getInt("cooldown_$pkg", -1).takeIf { it > 0 }

    fun setCustomLimit(pkg: String, sec: Int?) =
        sp.edit().apply { if (sec == null) remove("limit_$pkg") else putInt("limit_$pkg", sec) }.apply()

    fun setCustomCooldown(pkg: String, sec: Int?) =
        sp.edit().apply { if (sec == null) remove("cooldown_$pkg") else putInt("cooldown_$pkg", sec) }.apply()

    fun limitFor(pkg: String) = customLimit(pkg) ?: defaultLimitSec
    fun cooldownFor(pkg: String) = customCooldown(pkg) ?: defaultCooldownSec

    // ---------------- groups ----------------

    private var cachedRaw: String? = null
    private var cachedGroups: List<AppGroup> = emptyList()

    var groups: List<AppGroup>
        get() {
            val raw = sp.getString("groups", "[]") ?: "[]"
            if (raw != cachedRaw) {
                cachedGroups = parseGroups(raw)
                cachedRaw = raw
            }
            return cachedGroups
        }
        set(v) = sp.edit().putString("groups", groupsToJson(v)).apply()

    fun groupOf(pkg: String): AppGroup? = groups.firstOrNull { pkg in it.members }

    fun groupById(id: String): AppGroup? = groups.firstOrNull { it.id == id }

    /** Adds or replaces a group. An app lives in one group at most, so members move here. */
    fun saveGroup(g: AppGroup) {
        val others = groups.filter { it.id != g.id }.map { it.copy(members = it.members - g.members.toSet()) }
        groups = (others + g).sortedBy { it.name.lowercase() }
    }

    fun deleteGroup(id: String) {
        groups = groups.filter { it.id != id }
    }

    fun newGroupId(): String = UUID.randomUUID().toString().take(8)

    /** Which timer, limit and cooldown apply to this app. */
    fun ruleFor(pkg: String): Rule {
        val g = groupOf(pkg)
        return if (g != null) Rule("g:${g.id}", g.limitSec, g.cooldownSec, g)
        else Rule(pkg, limitFor(pkg), cooldownFor(pkg), null)
    }

    /** First launch: create a Social group from the social apps that are installed. */
    fun seedDefaults(ctx: Context) {
        if (sp.getBoolean("seeded_social", false)) return
        val pm = ctx.packageManager
        val members = SOCIAL_APPS.filter { pm.getLaunchIntentForPackage(it) != null }
        if (groups.none { it.name.equals("Social", ignoreCase = true) }) {
            saveGroup(AppGroup(newGroupId(), "Social", members, defaultLimitSec, defaultCooldownSec))
        }
        sp.edit().putBoolean("seeded_social", true).apply()
    }

    // ---------------- cooldown lock state (by rule key) ----------------

    fun lockedUntil(key: String): Long = sp.getLong("locked_$key", 0L)

    fun lockedLength(key: String): Long = sp.getLong("locklen_$key", 0L)

    fun startCooldown(key: String, cooldownSec: Int, now: Long): Long {
        val lenMs = cooldownSec * 1000L
        val until = now + lenMs
        val countKey = countKey(key)
        sp.edit()
            .putLong("locked_$key", until)
            .putLong("locklen_$key", lenMs)
            .putInt(countKey, sp.getInt(countKey, 0) + 1)
            .apply()
        return until
    }

    fun cooldownsToday(key: String): Int = sp.getInt(countKey(key), 0)

    fun totalCooldownsToday(): Int {
        val prefix = "cd_${LocalDate.now()}_"
        return sp.all.filterKeys { it.startsWith(prefix) }.values.sumOf { (it as? Int) ?: 0 }
    }

    private fun countKey(key: String) = "cd_${LocalDate.now()}_$key"

    // ---------------- motivational videos ----------------

    var videoOn: Boolean
        get() = sp.getBoolean("video_on", false)
        set(v) = sp.edit().putBoolean("video_on", v).apply()

    var videoShuffle: Boolean
        get() = sp.getBoolean("video_shuffle", true)
        set(v) = sp.edit().putBoolean("video_shuffle", v).apply()

    /** Whether the lock-screen video starts with the sound off. */
    var videoMuted: Boolean
        get() = sp.getBoolean("video_muted", false)
        set(v) = sp.edit().putBoolean("video_muted", v).apply()

    /** Content URIs of the chosen gallery videos, in the order picked. */
    var videos: List<String>
        get() = runCatching {
            val a = JSONArray(sp.getString("videos", "[]"))
            (0 until a.length()).map { a.getString(it) }
        }.getOrDefault(emptyList())
        set(v) = sp.edit().putString("videos", JSONArray(v).toString()).apply()

    /** Which video to start with: random when shuffling, otherwise the next one in turn. */
    fun nextVideoIndex(count: Int): Int {
        if (count <= 0) return -1
        if (videoShuffle) return Random.nextInt(count)
        val i = sp.getInt("video_idx", 0).mod(count)
        sp.edit().putInt("video_idx", i + 1).apply()
        return i
    }

    // ---------------- json ----------------

    private fun parseGroups(raw: String): List<AppGroup> = runCatching {
        val arr = JSONArray(raw)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val m = o.getJSONArray("members")
            AppGroup(
                id = o.getString("id"),
                name = o.getString("name"),
                members = (0 until m.length()).map { m.getString(it) },
                limitSec = o.getInt("limit"),
                cooldownSec = o.getInt("cooldown"),
            )
        }
    }.getOrDefault(emptyList())

    private fun groupsToJson(list: List<AppGroup>): String = JSONArray().apply {
        list.forEach { g ->
            put(JSONObject().apply {
                put("id", g.id)
                put("name", g.name)
                put("members", JSONArray(g.members))
                put("limit", g.limitSec)
                put("cooldown", g.cooldownSec)
            })
        }
    }.toString()

    companion object {
        val SOCIAL_APPS = listOf(
            "com.instagram.android",
            "com.instagram.barcelona", // Threads
            "com.facebook.katana",
            "com.facebook.lite",
            "com.twitter.android", // X
            "com.snapchat.android",
            "com.zhiliaoapp.musically", // TikTok
            "com.ss.android.ugc.trill", // TikTok (some regions)
            "com.reddit.frontpage",
            "com.pinterest",
            "com.linkedin.android",
        )
    }
}

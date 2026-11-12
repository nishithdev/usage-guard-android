package com.nishith.usageguard

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import kotlin.random.Random

/** Everything the lock screen needs to show. */
data class LockInfo(
    val title: String,
    val lockedApps: String,
    val plural: Boolean,
    val until: Long,
    val lengthMs: Long,
    val limitSec: Int,
    val countToday: Int,
)

/**
 * The cooldown lock. It's a real full-screen activity, so the limited app is pushed
 * to the background (its videos/reels pause), and it takes audio focus so anything
 * still playing in the background stops too. The only way out is the home screen.
 * If motivational videos are on, one of your gallery videos plays here.
 */
class LockActivity : ComponentActivity() {

    private var info by mutableStateOf<LockInfo?>(null)
    private var focusRequest: AudioFocusRequest? = null
    private lateinit var prefs: GuardPrefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = GuardPrefs(this)
        info = readInfo(intent)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goHome()
        })
        setContent {
            info?.let { LockScreen(it, prefs, onHome = ::goHome) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        info = readInfo(intent)
    }

    override fun onResume() {
        super.onResume()
        silenceOtherAudio()
    }

    override fun onDestroy() {
        focusRequest?.let { (getSystemService(Context.AUDIO_SERVICE) as AudioManager).abandonAudioFocusRequest(it) }
        super.onDestroy()
    }

    /** Asking for permanent audio focus makes well-behaved players pause and stay paused. */
    private fun silenceOtherAudio() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            .setOnAudioFocusChangeListener { }
            .build()
            .also { focusRequest = it }
        runCatching { am.requestAudioFocus(req) }
    }

    private fun goHome() {
        runCatching {
            startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        finish()
    }

    private fun readInfo(i: Intent): LockInfo? {
        val until = i.getLongExtra(EXTRA_UNTIL, 0L)
        if (until <= 0L) return null
        return LockInfo(
            title = i.getStringExtra(EXTRA_TITLE) ?: "This app",
            lockedApps = i.getStringExtra(EXTRA_APPS) ?: "It",
            plural = i.getBooleanExtra(EXTRA_PLURAL, false),
            until = until,
            lengthMs = i.getLongExtra(EXTRA_LENGTH, 0L),
            limitSec = i.getIntExtra(EXTRA_LIMIT, 0),
            countToday = i.getIntExtra(EXTRA_COUNT, 1),
        )
    }

    companion object {
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_APPS = "apps"
        private const val EXTRA_PLURAL = "plural"
        private const val EXTRA_UNTIL = "until"
        private const val EXTRA_LENGTH = "length"
        private const val EXTRA_LIMIT = "limit"
        private const val EXTRA_COUNT = "count"

        fun intent(ctx: Context, info: LockInfo): Intent =
            Intent(ctx, LockActivity::class.java)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
                .putExtra(EXTRA_TITLE, info.title)
                .putExtra(EXTRA_APPS, info.lockedApps)
                .putExtra(EXTRA_PLURAL, info.plural)
                .putExtra(EXTRA_UNTIL, info.until)
                .putExtra(EXTRA_LENGTH, info.lengthMs)
                .putExtra(EXTRA_LIMIT, info.limitSec)
                .putExtra(EXTRA_COUNT, info.countToday)
    }
}

private val Bg = Color(0xFF0E0F12)
private val Muted = Color(0xFFC9CCD3)
private val Track = Color(0xFF2A2D35)
private val Chip = Color(0xFF1E2026)
private val Accent = Color(0xFFF07A2F)
private val Scrim = Color(0x99000000)

@Composable
private fun LockScreen(info: LockInfo, prefs: GuardPrefs, onHome: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(info.until) {
        while (true) {
            now = System.currentTimeMillis()
            if (now >= info.until) { onHome(); break } // cooldown over: release the lock
            delay(1000)
        }
    }
    val left = (info.until - now).coerceAtLeast(0)
    val fraction = if (info.lengthMs > 0) (left.toFloat() / info.lengthMs).coerceIn(0f, 1f) else 0f
    val opens = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(info.until))
    val verb = if (info.plural) "open" else "opens"
    val reason = "You hit your ${formatSeconds(info.limitSec)} limit. ${info.lockedApps} $verb again at $opens."

    // ---- motivational video playlist ----
    val videos = remember { if (prefs.videoOn) prefs.videos.map(Uri::parse) else emptyList() }
    val shuffle = remember { prefs.videoShuffle }
    var index by remember { mutableIntStateOf(prefs.nextVideoIndex(videos.size)) }
    var plays by remember { mutableIntStateOf(0) }
    var failed by remember { mutableStateOf(emptySet<Int>()) }
    var muted by remember { mutableStateOf(prefs.videoMuted) }

    fun pickNext(from: Int, skip: Set<Int>): Int {
        val ok = videos.indices.filter { it !in skip }
        if (ok.isEmpty()) return -1
        if (shuffle && ok.size > 1) return ok.filter { it != from }.random(Random)
        return ok.firstOrNull { it > from } ?: ok.first()
    }

    if (index >= 0 && index < videos.size) {
        VideoLock(
            info = info,
            reason = reason,
            left = left,
            fraction = fraction,
            opens = opens,
            uri = videos[index],
            playKey = "$index-$plays",
            position = "video ${index + 1} of ${videos.size}",
            muted = muted,
            onToggleMute = { muted = !muted },
            onNext = { index = pickNext(index, failed); plays++ },
            onEnded = { index = pickNext(index, failed); plays++ },
            onError = {
                failed = failed + index
                index = pickNext(index, failed)
                plays++
            },
            onHome = onHome,
        )
    } else {
        RingLock(info, reason, left, fraction, onHome)
    }
}

/** The plain lock: countdown ring and the way out. */
@Composable
private fun RingLock(info: LockInfo, reason: String, left: Long, fraction: Float, onHome: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .systemBarsPadding()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(40.dp))
        Box(Modifier.size(200.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 12.dp.toPx()
                val inset = stroke / 2
                val arcSize = Size(size.width - stroke, size.height - stroke)
                drawArc(Track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                drawArc(
                    Accent, -90f, 360f * fraction, false, Offset(inset, inset), arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("COOLDOWN", color = Muted, fontSize = 12.sp, letterSpacing = 1.sp)
                Text(formatClock(left), color = Color.White, fontSize = 46.sp, fontWeight = FontWeight.Bold)
                Text("of ${formatClock(info.lengthMs)}", color = Muted, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(32.dp))
        Text(
            "${info.title} is cooling down",
            color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center, lineHeight = 30.sp,
        )
        Spacer(Modifier.height(10.dp))
        Text(reason, color = Muted, fontSize = 15.sp, textAlign = TextAlign.Center, lineHeight = 21.sp)
        Spacer(Modifier.height(24.dp))
        Row(
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(Chip)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Filled.Lock, contentDescription = null, tint = Muted, modifier = Modifier.size(16.dp))
            Text("Reopening it brings you back here until the timer ends", color = Muted, fontSize = 13.sp)
        }
        Spacer(Modifier.weight(1f))
        HomeButton(onHome)
        Spacer(Modifier.height(12.dp))
        Text("${ordinal(info.countToday)} cooldown for ${info.title} today", color = Muted, fontSize = 13.sp)
    }
}

/** The lock with a gallery video on top and the countdown underneath. */
@Composable
private fun VideoLock(
    info: LockInfo,
    reason: String,
    left: Long,
    fraction: Float,
    opens: String,
    uri: Uri,
    playKey: String,
    position: String,
    muted: Boolean,
    onToggleMute: () -> Unit,
    onNext: () -> Unit,
    onEnded: () -> Unit,
    onError: () -> Unit,
    onHome: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .systemBarsPadding(),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.Black)
        ) {
            LockVideo(uri, playKey, muted, onEnded, onError, Modifier.fillMaxSize())

            Row(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Scrim)
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Filled.Lock, contentDescription = null, tint = Accent, modifier = Modifier.size(14.dp))
                Text("${info.title} locked", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            Row(
                Modifier.align(Alignment.TopEnd).padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                IconButton(
                    onClick = onToggleMute,
                    modifier = Modifier.size(44.dp),
                    colors = IconButtonDefaults.iconButtonColors(containerColor = Scrim),
                ) {
                    Icon(
                        painterResource(if (muted) R.drawable.ic_volume_off else R.drawable.ic_volume_up),
                        contentDescription = if (muted) "Unmute video" else "Mute video",
                        tint = Color.White,
                    )
                }
                IconButton(
                    onClick = onNext,
                    modifier = Modifier.size(44.dp),
                    colors = IconButtonDefaults.iconButtonColors(containerColor = Scrim),
                ) {
                    Icon(painterResource(R.drawable.ic_skip_next), contentDescription = "Next video", tint = Color.White)
                }
            }
        }

        Column(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text("COOLDOWN", color = Muted, fontSize = 12.sp, letterSpacing = 1.sp)
                    Text(
                        formatClock(left), color = Color.White, fontSize = 40.sp,
                        fontWeight = FontWeight.Bold, lineHeight = 42.sp,
                    )
                }
                Text("Opens at $opens", color = Muted, fontSize = 13.sp)
            }
            Box(
                Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Track)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth((1f - fraction).coerceIn(0.02f, 1f))
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(4.dp))
                        .background(Accent)
                )
            }
            Text("$reason · $position", color = Muted, fontSize = 14.sp, lineHeight = 20.sp)
            Spacer(Modifier.height(4.dp))
            HomeButton(onHome)
        }
    }
}

@Composable
private fun HomeButton(onHome: () -> Unit) {
    Button(
        onClick = onHome,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color(0xFF16181D)),
    ) { Text("Go to home screen", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
}

/**
 * Plays one gallery video, fitted inside the box. A new [playKey] restarts playback,
 * which is how "next" and looping a single video work.
 */
@Composable
private fun LockVideo(
    uri: Uri,
    playKey: String,
    muted: Boolean,
    onEnded: () -> Unit,
    onError: () -> Unit,
    modifier: Modifier,
) {
    val ended by rememberUpdatedState(onEnded)
    val errored by rememberUpdatedState(onError)
    val mutedNow by rememberUpdatedState(muted)
    val player = remember { mutableStateOf<MediaPlayer?>(null) }

    AndroidView(
        factory = { ctx ->
            FrameLayout(ctx).apply {
                val vv = VideoView(ctx)
                vv.setOnPreparedListener { mp ->
                    player.value = mp
                    val v = if (mutedNow) 0f else 1f
                    runCatching { mp.setVolume(v, v) }
                    vv.start()
                }
                vv.setOnCompletionListener { ended() }
                vv.setOnErrorListener { _, _, _ -> errored(); true }
                addView(
                    vv,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER,
                    )
                )
            }
        },
        update = { frame ->
            val vv = frame.getChildAt(0) as VideoView
            if (vv.tag != playKey) {
                vv.tag = playKey
                player.value = null
                runCatching { vv.setVideoURI(uri) }.onFailure { errored() }
            }
        },
        onRelease = { frame -> runCatching { (frame.getChildAt(0) as VideoView).stopPlayback() } },
        modifier = modifier,
    )

    LaunchedEffect(muted) {
        player.value?.let { mp ->
            val v = if (muted) 0f else 1f
            runCatching { mp.setVolume(v, v) }
        }
    }
}

package com.nishith.usageguard.ui

import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nishith.usageguard.GuardPrefs
import com.nishith.usageguard.formatClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MAX_VIDEOS = 20

/** Settings card: pick gallery videos to play on the cooldown lock. */
@Composable
fun VideoSettingsCard(prefs: GuardPrefs) {
    val ctx = LocalContext.current
    var on by remember { mutableStateOf(prefs.videoOn) }
    var videos by remember { mutableStateOf(prefs.videos) }
    var shuffle by remember { mutableStateOf(prefs.videoShuffle) }
    var muted by remember { mutableStateOf(prefs.videoMuted) }

    // The system photo picker: no gallery permission needed, you only share the videos you pick.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        uris.forEach { uri ->
            // Keep access after a restart, so the lock screen can still play it later.
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        videos = (videos + uris.map { it.toString() }).distinct().take(MAX_VIDEOS)
        prefs.videos = videos
        on = true
        prefs.videoOn = true
    }

    WhiteCard {
        SectionLabel("Motivational video")
        Row(verticalAlignment = Alignment.CenterVertically) {
            LabelBlock(
                "Play a video on the lock screen",
                "Optional · picked from your gallery",
                Modifier.weight(1f),
            )
            Switch(
                checked = on,
                onCheckedChange = { on = it; prefs.videoOn = it },
                colors = SwitchDefaults.colors(checkedTrackColor = C.Ink),
            )
        }

        if (videos.isNotEmpty()) {
            videos.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { s ->
                        VideoThumb(
                            uri = Uri.parse(s),
                            modifier = Modifier.weight(1f),
                            onRemove = {
                                runCatching {
                                    ctx.contentResolver.releasePersistableUriPermission(
                                        Uri.parse(s), Intent.FLAG_GRANT_READ_URI_PERMISSION
                                    )
                                }
                                videos = videos - s
                                prefs.videos = videos
                                if (videos.isEmpty()) { on = false; prefs.videoOn = false }
                            },
                        )
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        } else if (on) {
            Text("Pick at least one video so the lock screen has something to play.", fontSize = 13.sp, color = C.AccentText)
        }

        OutlinedButton(
            onClick = {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
            },
            enabled = videos.size < MAX_VIDEOS,
            modifier = Modifier.fillMaxWidth().height(44.dp),
            shape = RoundedCornerShape(12.dp),
        ) { Text("+ Choose videos from gallery", color = C.Ink, fontWeight = FontWeight.Medium) }

        if (videos.isNotEmpty()) {
            Divider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Order", Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Row(
                    Modifier.clip(RoundedCornerShape(12.dp)).background(C.Ground).padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Segment("Shuffle", shuffle) { shuffle = true; prefs.videoShuffle = true }
                    Segment("In order", !shuffle) { shuffle = false; prefs.videoShuffle = false }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                LabelBlock("Start muted", "You can unmute on the lock screen", Modifier.weight(1f))
                Switch(
                    checked = muted,
                    onCheckedChange = { muted = it; prefs.videoMuted = it },
                    colors = SwitchDefaults.colors(checkedTrackColor = C.Ink),
                )
            }
        }
    }
}

@Composable
private fun Segment(label: String, selected: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.height(40.dp),
        shape = RoundedCornerShape(9.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) Color.White else Color.Transparent,
            contentColor = if (selected) C.Ink else C.Muted,
        ),
        elevation = null,
    ) { Text(label, fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal) }
}

private data class Thumb(val frame: Bitmap?, val durationMs: Long?)

@Composable
private fun VideoThumb(uri: Uri, modifier: Modifier, onRemove: () -> Unit) {
    val ctx = LocalContext.current
    val thumb by produceState<Thumb?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(ctx, uri)
                Thumb(
                    frame = r.getFrameAtTime(1_000_000)?.let { Bitmap.createScaledBitmap(it, 240, (240f * it.height / it.width).toInt().coerceAtLeast(1), true) },
                    durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull(),
                )
            } catch (e: Exception) {
                Thumb(null, null)
            } finally {
                runCatching { r.release() }
            }
        }
    }

    Box(
        modifier
            .height(110.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF3A4152)),
        contentAlignment = Alignment.Center,
    ) {
        val frame = thumb?.frame
        if (frame != null) {
            Image(
                frame.asImageBitmap(), contentDescription = "Chosen video",
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
            )
        }
        if (thumb != null && frame == null && thumb?.durationMs == null) {
            Text("Unavailable", color = Color.White, fontSize = 11.sp)
        } else {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
        }
        thumb?.durationMs?.let {
            Text(
                formatClock(it),
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color(0x99000000))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
                color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            )
        }
        IconButton(
            onClick = onRemove,
            modifier = Modifier.align(Alignment.TopEnd).size(36.dp).padding(4.dp),
            colors = IconButtonDefaults.iconButtonColors(containerColor = Color(0x99000000)),
        ) {
            Icon(Icons.Filled.Close, contentDescription = "Remove video", tint = Color.White, modifier = Modifier.size(16.dp))
        }
    }
}
